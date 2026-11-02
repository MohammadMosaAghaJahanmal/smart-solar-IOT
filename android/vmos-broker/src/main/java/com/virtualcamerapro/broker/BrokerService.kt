package com.virtualcamerapro.broker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraInventory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean

/** Guest-only binder endpoint. Active requires actual HAL delivery and Camera2 pixels. */
class BrokerService : Service() {
    private data class Source(val file: File, val type: String, val sha256: String)
    private val control = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()
    private val epoch = AtomicLong()
    private val recoveryQueued = AtomicBoolean()
    private val transition = Any()
    private lateinit var runtime: GuestRuntime
    @Volatile private var producer: GuestFrameProducer? = null
    @Volatile private var phase = "ready"
    @Volatile private var stage = "idle"
    @Volatile private var lastError: GuestFailure? = null
    @Volatile private var proof: JSONObject? = null
    @Volatile private var destroyed = false
    private var selected: Source? = null
    private var settings = OutputSettings()
    private var revision = 0L
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "VirtualCameraPro:VMOS-guest-camera").apply { setReferenceCounted(false) }
    }

    private fun authorize() {
        enforceCallingPermission("com.virtualcamerapro.permission.CONTROL", "Controller signature permission is required")
        val names = packageManager.getPackagesForUid(Binder.getCallingUid()) ?: emptyArray()
        if ("com.virtualcamerapro" !in names || packageManager.checkSignatures(packageName, "com.virtualcamerapro") != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Only the matching signed controller may configure this guest camera")
    }
    private fun <T> serial(action: () -> T): T {
        val task = control.submit(Callable { synchronized(guestOperations) { action() } })
        return try { task.get(90, TimeUnit.SECONDS) }
        catch (error: ExecutionException) { throw IllegalStateException(error.cause?.message ?: "VMOS camera operation failed", error.cause) }
        catch (error: java.util.concurrent.TimeoutException) {
            task.cancel(true)
            fail(GuestFailure("VMOS_ACTIVATION_TIMEOUT", "The guest camera operation exceeded 90 seconds", "Inspect the guest transport and reactivate."))
            throw IllegalStateException("VMOS_ACTIVATION_TIMEOUT", error)
        }
    }
    private fun identity(json: JSONObject) = json.put("backend", GuestRuntime.BACKEND).put("brokerPackage", packageName)
        .put("processUid", Process.myUid()).put("processPid", Process.myPid()).put("androidSdk", android.os.Build.VERSION.SDK_INT)
        .put("platformSigned", packageManager.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH)

    private fun requireIdentity() {
        runtime.requireGuest()
        if (setupInProgress.get()) throw GuestFailure("VMOS_SETUP_IN_PROGRESS", "The guest camera bridge is being configured or restored", "Wait for the guest setup operation to finish before activation.")
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw GuestFailure("PROBE_PERMISSION_MISSING", "The guest broker needs Camera permission for independent frame verification", "Grant Camera permission to Virtual Camera VMOS Guest Service inside the guest.")
    }

    private data class FactoryCamera(val name: String, val facing: String)
    private fun cameras(factory: String): Map<String, FactoryCamera> {
        val found = factory.lineSequence().mapNotNull { line ->
            val name = Regex("(?:^|\\s)name=([A-Za-z0-9_.-]{1,96})(?:\\s|$)").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            val facing = Regex("(?:^|\\s)dir=(back|front|external)(?:\\s|$)").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            FactoryCamera(name, facing)
        }.toList()
        val inventory = CameraInventory.query(this)
        val answer = mutableMapOf<String, FactoryCamera>()
        for (index in 0 until inventory.length()) {
            val item = inventory.getJSONObject(index)
            val facing = item.getString("facing")
            val matching = found.filter { it.facing == facing }
            val publicMatching = (0 until inventory.length()).count { inventory.getJSONObject(it).getString("facing") == facing }
            // Never infer a numeric ID from its spelling or list position. A unique
            // facing is a candidate mapping; activation still verifies its pixels.
            if (matching.size == 1 && publicMatching == 1) answer[item.getString("id")] = matching.single()
        }
        return answer
    }
    private fun availableResolutions(cameraId: String): List<Pair<Int, Int>> {
        val manager = getSystemService(CameraManager::class.java)
        if (cameraId !in manager.cameraIdList) return emptyList()
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        fun sizes(format: Int) = map.getOutputSizes(format)?.map { it.width to it.height }?.toSet() ?: emptySet()
        val yuv = sizes(ImageFormat.YUV_420_888); val jpeg = sizes(ImageFormat.JPEG)
        return listOf(1280 to 720, 1920 to 1080).filter { it in yuv && it in jpeg }
    }
    private fun support(): JSONObject = try {
        requireIdentity()
        runtime.installed()
        val factory = if (phase == "active" || producer != null) {
            try { runtime.checkTransport() }
            catch (error: Throwable) { fail(error); throw error }
        } else runtime.recoverInstalled { GuestRoot(this).restartProvider() }.getString("cameraFactory")
        val mapping = cameras(factory)
        if (mapping.isEmpty()) throw GuestFailure("VMOS_CAMERA_MAPPING_UNVERIFIED", "The real factory did not expose an unambiguous matching guest camera", "Inspect the actual factory names and Camera2 facing before configuring this guest.")
        val inventory = CameraInventory.query(this)
        val usable = JSONArray()
        for (i in 0 until inventory.length()) inventory.getJSONObject(i).let { if (it.getString("id") in mapping && availableResolutions(it.getString("id")).isNotEmpty()) usable.put(it) }
        if (usable.length() == 0) throw GuestFailure("RESOLUTION_UNAVAILABLE", "No mapped guest camera advertises 720p or 1080p in both YUV and JPEG", "A lower-resolution guest requires a separate measured output profile.")
        val id = usable.getJSONObject(0).getString("id")
        val choices = JSONArray()
        availableResolutions(id).forEach { choices.put(JSONObject().put("width", it.first).put("height", it.second)) }
        identity(JSONObject().put("protocol", 2).put("supported", true).put("code", "READY")
            .put("component", COMPONENT).put("message", "VMOS guest camera bridge connected")
            .put("recovery", "").put("cameras", usable).put("cameraId", id).put("resolutions", choices))
    } catch (error: Throwable) {
        val failure = failure(error)
        identity(JSONObject().put("protocol", 2).put("supported", false).put("code", failure.code)
            .put("component", COMPONENT).put("message", failure.message).put("recovery", failure.recovery))
    }
    private fun parse(text: String): OutputSettings {
        val json = JSONObject(text)
        return OutputSettings(json.getInt("width"), json.getInt("height"), json.getBoolean("loop"),
            json.getInt("rotation"), json.getString("scaleMode"), json.getString("cameraId"),
            json.optDouble("zoom", 1.0), json.optDouble("panX", 0.0), json.optDouble("panY", 0.0))
    }
    private fun checkConfiguration(value: OutputSettings, mapping: Map<String, FactoryCamera>) {
        if (value.cameraId !in mapping) throw GuestFailure("CAMERA_ID_UNAVAILABLE", "This guest camera has no unambiguous actual transport mapping", "Select one of the mapped cameras reported by the broker.")
        if (value.width to value.height !in availableResolutions(value.cameraId)) throw GuestFailure("RESOLUTION_UNAVAILABLE", "The selected guest camera does not advertise ${value.width}x${value.height} in YUV and JPEG", "Select an advertised output resolution.")
    }
    private fun state(): String {
        val current = producer
        val valid = phase != "active" || (current != null && current.expiresNs > SystemClock.elapsedRealtimeNanos() && proof != null)
        val error = lastError
        return identity(JSONObject().put("phase", if (valid) phase else "error").put("stage", stage)
            .put("code", error?.code ?: if (valid) "OK" else "VMOS_LEASE_EXPIRED")
            .put("message", error?.message ?: if (!valid) "The guest source lease expired" else when (phase) {
                "active" -> "Virtual Camera Active"; "activating" -> "Activating…"; "stopped" -> "Virtual Camera Stopped"; else -> "VMOS Guest Camera Ready"
            }).put("recovery", error?.recovery ?: "").put("virtualId", if (valid && current != null) current.settings.cameraId else "")
            .put("frames", if (valid) current?.frames ?: 0 else 0).put("streams", 0).put("sourceRevision", revision)
            .put("proof", if (valid) proof else null)).toString()
    }
    private val binder = object : IVirtualCameraBroker.Stub() {
        override fun getSupport(): String { authorize(); return serial { support().toString() } }
        override fun getStatus(): String { authorize(); return state() }
        override fun activate(fd: ParcelFileDescriptor, type: String, text: String): Boolean {
            authorize()
            return fd.use { serial {
                check(phase !in listOf("active", "activating")) { "Deactivate the existing guest camera first" }
                var next: Source? = null
                try {
                    next = copySource(fd, type)
                    configure(next, parse(text))
                    selected?.file?.delete(); selected = next; true
                } catch (error: Throwable) { next?.file?.delete(); fail(error); throw IllegalStateException(lastError!!.message, error) }
            } }
        }
        override fun deactivate() { authorize(); stopCamera() }
        override fun updateSource(fd: ParcelFileDescriptor, type: String) {
            authorize(); fd.use { serial {
                check(phase == "active") { "Activate the guest camera before replacing its source" }
                val next = copySource(fd, type)
                try { configure(next, settings); selected?.file?.delete(); selected = next }
                catch (error: Throwable) { next.file.delete(); fail(error); throw IllegalStateException(lastError!!.message, error) }
            } }
        }
        override fun updateSettings(text: String) { authorize(); serial {
            val value = parse(text)
            if (phase == "active") {
                check(value.width == settings.width && value.height == settings.height && value.cameraId == settings.cameraId) { "Deactivate before changing the camera or resolution" }
                try {
                    val current = requireNotNull(producer)
                    val changed = current.updateSettings(value)
                    synchronized(transition) {
                        check(producer === current && phase == "active") { "The guest source ended during its live transform update" }
                        settings = value
                        // Preserve the original Camera2 activation evidence. A live
                        // transform publishes through that already verified source;
                        // it must not pretend a second camera probe was performed.
                        proof = proof?.let { JSONObject(it.toString()) }
                            ?.put("liveTransformRevision", changed.first)?.put("liveTransformSequence", changed.second)
                            ?.put("liveTransformPublishedAtElapsedMs", SystemClock.elapsedRealtime())
                            ?.put("zoom", value.zoom)?.put("panX", value.panX)?.put("panY", value.panY)
                    }
                } catch (error: Throwable) { fail(error); throw IllegalStateException(lastError!!.message, error) }
            } else settings = value
        } }
    }

    private fun copySource(fd: ParcelFileDescriptor, type: String): Source {
        require(type in listOf("image", "video")) { "Invalid selected media type" }
        val file = File.createTempFile("source-", ".media", filesDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input -> file.outputStream().use { output ->
                val bytes = ByteArray(65536)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Media copy cancelled" }
                    val read = input.read(bytes); if (read < 0) break
                    size += read; require(size <= 256L * 1024 * 1024) { "Selected media exceeds 256 MiB" }
                    output.write(bytes, 0, read); digest.update(bytes, 0, read)
                }
            } }
            require(size > 0) { "Selected media is empty" }
            return Source(file, type, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        } catch (error: Throwable) { file.delete(); throw error }
    }
    private fun configure(source: Source, value: OutputSettings) {
        val transaction = epoch.incrementAndGet()
        phase = "activating"; stage = "prerequisites"; lastError = null; proof = null
        requireIdentity(); runtime.installed()
        val recovered = runtime.recoverInstalled { GuestRoot(this).restartProvider() }
        val mapping = cameras(recovered.getString("cameraFactory")); checkConfiguration(value, mapping)
        lateinit var next: GuestFrameProducer
        next = GuestFrameProducer(runtime.stateDir, source.sha256, mapping.getValue(value.cameraId).name, value) { error ->
            runCatching { cleanup.execute { synchronized(transition) { if (producer === next) fail(error) } } }
        }
        var published = false
        try {
            stage = "native_decode"
            next.start(source.file, source.type)
            val old = synchronized(transition) {
                check(transaction == epoch.get() && !setupInProgress.get() && !Thread.currentThread().isInterrupted) { "Activation was cancelled" }
                val previous = producer
                previous?.suspendPublication()
                producer = next; next.publish(); published = true
                previous
            }
            old?.close()
            if (!wakeLock.isHeld) wakeLock.acquire(20000)
            stage = "camera2_yuv_jpeg_verification"
            val captured = CameraFrameProbe(this).capture(value.cameraId, 400, value.width, value.height)
            check(captured.previewFrames >= 3 && captured.lastTimestamp > captured.firstTimestamp && captured.jpeg.isNotEmpty()) { "The guest Camera2 consumer did not receive actual increasing YUV frames and a JPEG" }
            check(captured.previewWidth == value.width && captured.previewHeight == value.height && captured.jpegWidth == value.width && captured.jpegHeight == value.height) { "The guest camera delivered a different resolution from the selected output" }
            val error = next.jpegError(captured.jpeg)
            if (error > 25.0) throw GuestFailure("VMOS_SOURCE_PIXELS_MISMATCH", "The actual Camera2 JPEG does not match the decoded selected media (MAE ${"%.2f".format(error)})", "Inspect the measured camera mapping and proxy conversion; Active was refused.")
            stage = "native_delivery_verification"
            val delivery = runtime.status()
            check(delivery.optInt("protocol") in 1..2 && delivery.optBoolean("configured") && delivery.optString("sourceId") == next.generation &&
                delivery.optString("sourceSha256") == source.sha256 && delivery.optString("cameraName") == mapping.getValue(value.cameraId).name &&
                delivery.optLong("sourceFrames") >= 3 && delivery.optLong("lastFrameBootNs") > SystemClock.elapsedRealtimeNanos() - 3_000_000_000L &&
                delivery.optLong("expiresBootNs") > SystemClock.elapsedRealtimeNanos()) { "The native proxy did not acknowledge fresh frame delivery for the exact selected generation" }
            synchronized(transition) {
                check(transaction == epoch.get() && producer === next && next.expiresNs > SystemClock.elapsedRealtimeNanos() && !Thread.currentThread().isInterrupted) { "Activation was cancelled during verification" }
                revision++; settings = value
                proof = captured.metrics().put("backend", GuestRuntime.BACKEND).put("sourceVerified", true)
                    .put("rendererConfigured", true).put("sourceSha256", source.sha256).put("generation", next.generation)
            .put("producerFrames", next.frames).put("proxyFrames", delivery.getLong("sourceFrames"))
                    .put("proxyPid", delivery.getInt("pid")).put("bestMeanAbsoluteError", error)
                    .put("sourceRevision", revision).put("verifiedAtElapsedMs", SystemClock.elapsedRealtime())
                phase = "active"; stage = "running"; showNotification()
            }
        } catch (error: Throwable) {
            synchronized(transition) { if (producer === next) { producer = null; runtime.deactivate() } }
            next.close()
            if (!published && transaction == epoch.get()) throw error
            throw error
        }
    }
    private fun stopTransport(): GuestFrameProducer? = synchronized(transition) {
        epoch.incrementAndGet(); proof = null
        val stopped = producer; producer = null
        stopped?.suspendPublication(); runtime.deactivate()
        if (wakeLock.isHeld) wakeLock.release()
        stopped
    }
    private fun stopCamera() {
        val stopped = stopTransport()
        phase = "stopped"; stage = "idle"; lastError = null
        cleanup.execute { stopped?.close() }
        // Keep an installed bridge supervised after media is deactivated. Its
        // HAL still points to this proxy even while ordinary cameras are used.
        if (!setupInProgress.get() && runCatching { runtime.installed() }.isSuccess) showNotification()
        else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    private fun failure(error: Throwable) = error as? GuestFailure ?: GuestFailure("VMOS_PIPELINE_FAILURE",
        error.cause?.message ?: error.message ?: "The guest camera pipeline failed", "Inspect the guest bridge, source decoder and Camera2 verification before reactivating.")
    private fun fail(error: Throwable) {
        val result = failure(error)
        val stopped = runCatching { stopTransport() }.getOrNull()
        cleanup.execute { stopped?.close() }
        phase = "error"; lastError = result; showNotification()
        Log.e(TAG, "event=guest_camera_failed code=${result.code} stage=$stage", error)
    }
    override fun onCreate() {
        super.onCreate(); runtime = GuestRuntime(this)
        liveService = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "VMOS guest virtual camera", NotificationManager.IMPORTANCE_LOW))
        // A restarted service must never revive a source selected by a dead process.
        runtime.deactivate()
        filesDir.listFiles()?.filter { it.name.startsWith("source-") }?.forEach { it.delete() }
        heartbeat.scheduleWithFixedDelay({
            val current = producer
            if (current != null) try {
                synchronized(transition) { if (producer === current) { current.renew(); wakeLock.acquire(20000) } }
            } catch (error: Throwable) {
                synchronized(transition) { if (producer === current) fail(error) }
            }
        }, 2, 2, TimeUnit.SECONDS)
        heartbeat.scheduleWithFixedDelay({ queueTransportRecovery() }, 10, 10, TimeUnit.SECONDS)
    }
    private fun queueTransportRecovery() {
        if (destroyed || !recoveryQueued.compareAndSet(false, true)) return
        try { control.execute {
            try { synchronized(guestOperations) {
                if (destroyed || setupInProgress.get() || !runtime.hasInstallationRecord()) return@synchronized
                if (phase == "active" || producer != null) {
                    // A lost live proxy invalidates the old proof. Recovery can
                    // resume pass-through later, but must not claim Active again.
                    runtime.checkTransport()
                } else {
                    runtime.recoverInstalled { GuestRoot(this).restartProvider() }
                    if (stage == "transport_recovery") {
                        phase = "ready"; stage = "idle"; lastError = null
                    }
                    showNotification()
                }
            } } catch (error: Throwable) {
                if (!destroyed) { stage = "transport_recovery"; fail(error) }
            } finally { recoveryQueued.set(false) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryQueued.set(false) }
    }
    private fun showNotification() {
        if (destroyed) return
        val stop = PendingIntent.getService(this, 0, Intent(this, BrokerService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(when (phase) { "active" -> "VMOS virtual camera active"; "error" -> "VMOS guest camera needs attention"; else -> "VMOS guest camera service" })
            .setContentText(when (phase) {
                "active" -> "Guest camera ${settings.cameraId} uses verified selected media"
                "error" -> lastError?.message ?: "Open guest setup to inspect camera recovery"
                else -> "Keeping the installed guest camera bridge available"
            }).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Deactivate", stop).build()).build()
        startForeground(7713, notice)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") stopCamera() else {
            showNotification()
            queueTransportRecovery()
        }
        // A sticky restart restores only pass-through; onCreate clears every
        // old source lease and Active still requires a fresh consumer proof.
        return if (runCatching { runtime.installed() }.isSuccess) START_STICKY else START_NOT_STICKY
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        destroyed = true
        if (liveService === this) liveService = null
        heartbeat.shutdownNow(); control.shutdownNow()
        val stopped = runCatching { stopTransport() }.getOrNull()
        cleanup.execute { stopped?.close(); selected?.file?.delete(); selected = null }
        cleanup.shutdown(); super.onDestroy()
    }
    companion object {
        @Volatile private var liveService: BrokerService? = null
        private val setupInProgress = AtomicBoolean()
        private val guestOperations = Any()
        internal fun <T> withGuestSetup(operation: () -> T): T {
            synchronized(guestOperations) {
                check(setupInProgress.compareAndSet(false, true)) { "A guest setup operation is already running" }
                try { liveService?.stopCamera(); return operation() }
                finally { setupInProgress.set(false) }
            }
        }
        internal fun <T> withGuestTransportCheck(operation: (Boolean) -> T): T = synchronized(guestOperations) {
            val service = liveService
            val idle = service?.producer == null && service?.phase != "activating"
            try { operation(idle) }
            catch (error: Throwable) {
                if (!idle) service?.fail(error)
                throw error
            }
        }
        internal const val ACTION_RECOVER_TRANSPORT = "com.virtualcamerapro.broker.RECOVER_GUEST_TRANSPORT"
        private const val TAG = "VirtualCameraVMOS"
        private const val CHANNEL = "virtual-camera-vmos"
        private const val COMPONENT = "VMOS Android 9 guest camera bridge"
    }
}
