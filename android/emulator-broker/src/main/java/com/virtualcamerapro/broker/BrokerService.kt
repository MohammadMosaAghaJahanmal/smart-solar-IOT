package com.virtualcamerapro.broker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraInventory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/** Only for an explicitly provisioned Android Emulator environment camera. */
class BrokerService : Service() {
    private data class Settings(val width: Int, val height: Int, val rotation: Int, val scaleMode: String, val loop: Boolean, val cameraId: String,
        val zoom: Double = 1.0, val panX: Double = 0.0, val panY: Double = 0.0) {
        fun json(type: String) = JSONObject().put("width", width).put("height", height).put("rotation", rotation)
            .put("scaleMode", scaleMode).put("loop", loop).put("cameraId", cameraId).put("type", type)
            .put("zoom", zoom).put("panX", panX).put("panY", panY)
    }
    private data class Source(val file: File, val type: String, val sha256: String)
    private data class PendingActivation(val connection: EmulatorConnection, val requestId: String)
    private class Session(val connection: EmulatorConnection, val generation: String, val sha256: String) {
        @Volatile var leaseDeadlineMs = 0L
    }
    private val control = Executors.newSingleThreadExecutor()
    private val cleanup = Executors.newSingleThreadExecutor()
    // Heartbeats must run during both HTTP source configuration and Camera2 verification.
    private val heartbeat = Executors.newSingleThreadScheduledExecutor()
    private val session = AtomicReference<Session?>()
    private val pendingActivation = AtomicReference<PendingActivation?>()
    private val configurationEpoch = AtomicLong()
    private val transition = Any()
    @Volatile private var phase = "ready"
    @Volatile private var stage = "idle"
    @Volatile private var lastError: BrokerFailure? = null
    @Volatile private var proof: JSONObject? = null
    @Volatile private var sourceRevision = 0L
    private var source: Source? = null
    private var settings = Settings(1280, 720, 0, "fill", true, "10")
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VirtualCameraPro:emulator-camera").apply { setReferenceCounted(false) }
    }

    private fun <T> serial(action: () -> T): T {
        val pending = control.submit(Callable { action() })
        return try { pending.get(220, TimeUnit.SECONDS) }
        catch (e: ExecutionException) { throw IllegalStateException(e.cause?.let { detail(it) } ?: "Emulator camera operation failed", e.cause) }
        catch (e: java.util.concurrent.TimeoutException) {
            pending.cancel(true)
            val timeout = BrokerFailure("ACTIVATION_TIMEOUT", "Emulator camera activation exceeded 220 seconds", "Check the local companion and camera availability.")
            runCatching { stopHost() }.onFailure { Log.e(TAG, "Timed-out activation cancellation failed", it) }
            phase = "error"; proof = null; lastError = timeout; stopForeground(STOP_FOREGROUND_REMOVE)
            throw IllegalStateException("ACTIVATION_TIMEOUT: Emulator camera operation timed out", e)
        }
    }
    private fun detail(error: Throwable) = if (error is BrokerFailure) "${error.code}: ${error.message}. ${error.recovery}" else error.message ?: "Emulator camera operation failed"
    private fun authorize() {
        enforceCallingPermission("com.virtualcamerapro.permission.CONTROL", "Untrusted controller signing certificate")
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()) ?: emptyArray()
        if ("com.virtualcamerapro" in packages) return
        if (property("ro.debuggable") == "1" && "com.virtualcamerapro.systemtests" in packages &&
            packageManager.checkSignatures(packageName, "com.virtualcamerapro.systemtests") == PackageManager.SIGNATURE_MATCH) return
        throw SecurityException("Unknown virtual camera controller package")
    }
    private fun property(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")
    private fun identity(result: JSONObject) = result.put("brokerPackage", packageName)
        .put("processUid", Process.myUid()).put("processPid", Process.myPid())
        .put("platformSigned", packageManager.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH)
        .put("systemApp", applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        .put("backend", EmulatorConnection.BACKEND)
    private fun requireIdentity() {
        if (Build.VERSION.SDK_INT != 36 || property("ro.kernel.qemu") != "1")
            throw BrokerFailure("EMULATOR_ONLY", "This broker requires the explicitly configured Android 16 virtual device", "Use the emulator setup script with its isolated API 36 AVD.")
        if (Process.myUid() != Process.SYSTEM_UID)
            throw BrokerFailure("BROKER_NOT_PRIVILEGED", "The emulator broker must run with system UID 1000", "Install the platform-signed APK through the emulator installer.")
        if (packageManager.checkSignatures("android", packageName) != PackageManager.SIGNATURE_MATCH)
            throw BrokerFailure("BROKER_NOT_PLATFORM_SIGNED", "The broker does not match this emulator's platform certificate", "Use the matching AOSP emulator platform certificate.")
        if (applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0)
            throw BrokerFailure("BROKER_NOT_SYSTEM_APP", "The emulator broker is not installed on the system partition", "Run the emulator installer.")
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw BrokerFailure("PROBE_PERMISSION_MISSING", "Camera permission is required for actual YUV and JPEG verification", "Grant CAMERA to the emulator broker through the setup script.")
    }
    private fun requireHostIdentity(response: JSONObject, connection: EmulatorConnection) {
        if (response.optInt("hostProtocol") != 2) throw BrokerFailure("EMULATOR_HOST_PROTOCOL_MISMATCH", "The companion does not support cancellation protocol 2", "Restart the matching workspace emulator companion with the launcher.")
        check(response.optString("backend") == EmulatorConnection.BACKEND && response.optString("cameraId") == connection.cameraId) {
            "The companion response does not match the explicitly selected emulator camera"
        }
    }
    private fun support(): JSONObject = try {
        requireIdentity()
        val connection = EmulatorConnection.read(filesDir)
        val remote = connection.request("/support")
        requireHostIdentity(remote, connection)
        if (!remote.optBoolean("supported")) throw BrokerFailure(remote.optString("code", "EMULATOR_COMPANION_NOT_READY"),
            remote.optString("message", "The Android Emulator environment camera is not ready"), "Start the matching emulator companion and check its camera configuration.")
        val manager = getSystemService(CameraManager::class.java)
        if (connection.cameraId !in manager.cameraIdList) throw BrokerFailure("CAMERA_ID_UNAVAILABLE", "Configured emulator camera ${connection.cameraId} is not available", "Boot the AVD with its environment video camera configuration.")
        val inventory = CameraInventory.query(this)
        val cameras = JSONArray()
        for (i in 0 until inventory.length()) if (inventory.getJSONObject(i).getString("id") == connection.cameraId) cameras.put(inventory.getJSONObject(i))
        val choices = availableResolutions(connection.cameraId)
        if (choices.isEmpty()) throw BrokerFailure("RESOLUTION_UNAVAILABLE", "The configured camera does not advertise 720p or 1080p in both YUV and JPEG", "Use an emulator camera that advertises a matching YUV and JPEG output size.")
        val resolutions = JSONArray()
        choices.forEach { (width, height) -> resolutions.put(JSONObject().put("width", width).put("height", height)) }
        identity(JSONObject().put("supported", true).put("protocol", 2).put("code", "READY")
            .put("component", COMPONENT).put("message", "Android Emulator environment camera connected")
            .put("recovery", "").put("cameras", cameras).put("cameraId", connection.cameraId).put("resolutions", resolutions))
    } catch (error: Exception) {
        val failure = failure(error, "EMULATOR_COMPANION_UNAVAILABLE")
        identity(JSONObject().put("supported", false).put("protocol", 2).put("code", failure.code)
            .put("component", COMPONENT).put("message", failure.message).put("recovery", failure.recovery))
    }
    private fun configuration(text: String): Settings {
        val json = JSONObject(text)
        val result = Settings(json.getInt("width"), json.getInt("height"), json.getInt("rotation"), json.getString("scaleMode"), json.getBoolean("loop"), json.getString("cameraId"),
            json.optDouble("zoom", 1.0), json.optDouble("panX", 0.0), json.optDouble("panY", 0.0))
        require(result.rotation in listOf(0, 90, 180, 270) && result.scaleMode in listOf("fit", "fill")) { "Invalid output rotation or scale mode" }
        require(result.zoom.isFinite() && result.zoom in 1.0..8.0 && result.panX.isFinite() && result.panY.isFinite() && result.panX in -1.0..1.0 && result.panY in -1.0..1.0) { "Invalid output zoom or pan" }
        requireResolution(result, EmulatorConnection.read(filesDir))
        return result
    }
    private fun availableResolutions(cameraId: String): List<Pair<Int, Int>> {
        val manager = getSystemService(CameraManager::class.java)
        if (cameraId !in manager.cameraIdList) throw BrokerFailure("CAMERA_ID_UNAVAILABLE", "Configured emulator camera $cameraId is not available", "Boot the AVD with its environment video camera configuration.")
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        fun sizes(format: Int) = map.getOutputSizes(format)?.map { it.width to it.height }?.toSet() ?: emptySet()
        val yuv = sizes(ImageFormat.YUV_420_888)
        val jpeg = sizes(ImageFormat.JPEG)
        return listOf(1280 to 720, 1920 to 1080).filter { it in yuv && it in jpeg }
    }
    private fun requireResolution(value: Settings, connection: EmulatorConnection) {
        if (value.cameraId != connection.cameraId) throw BrokerFailure("CAMERA_ID_UNAVAILABLE", "Only configured emulator camera ${connection.cameraId} accepts the selected media", "Select camera ${connection.cameraId}.")
        val choices = availableResolutions(connection.cameraId)
        if (value.width to value.height !in choices) throw BrokerFailure("RESOLUTION_UNAVAILABLE", "Camera ${connection.cameraId} does not advertise ${value.width}x${value.height} in both YUV and JPEG",
            "Select an advertised camera resolution: ${choices.joinToString { "${it.first}x${it.second}" }.ifEmpty { "none available" }}.")
    }
    private fun state(): String {
        val current = session.get()
        val valid = phase != "active" || (current != null && current.leaseDeadlineMs > SystemClock.elapsedRealtime() && proof != null)
        val error = lastError
        return identity(JSONObject().put("phase", if (valid) phase else "error").put("stage", stage)
            .put("message", error?.message ?: if (!valid) "The emulator camera lease expired" else when (phase) {
                "active" -> "Virtual Camera Active"; "activating" -> "Activating…"; "stopped" -> "Virtual Camera Stopped"; else -> "Android Emulator Camera Ready"
            }).put("code", error?.code ?: if (valid) "OK" else "EMULATOR_LEASE_EXPIRED").put("recovery", error?.recovery ?: "")
            .put("virtualId", current?.connection?.cameraId ?: "").put("sourceRevision", sourceRevision)
            .put("frames", if (valid) proof?.optInt("previewFrames") ?: 0 else 0).put("streams", 0)
            .put("proof", if (valid) proof else null)).toString()
    }
    private val binder = object : IVirtualCameraBroker.Stub() {
        override fun getSupport(): String { authorize(); return serial { support().toString() } }
        override fun getStatus(): String { authorize(); return state() }
        override fun activate(fd: ParcelFileDescriptor, type: String, text: String): Boolean {
            authorize()
            return fd.use { serial {
                check(phase != "active" && phase != "activating") { "Deactivate the existing emulator camera first" }
                var candidate: Source? = null
                val previous = source
                try {
                    val next = configuration(text)
                    val prepared = copySource(fd, type)
                    candidate = prepared
                    configure(prepared, next)
                    source = prepared; previous?.file?.delete(); true
                } catch (error: Exception) { candidate?.file?.delete(); rejectActivation(error) }
            } }
        }
        // Cancellation must not wait behind a long source conversion on control.
        override fun deactivate() { authorize(); deactivateCamera() }
        override fun updateSource(fd: ParcelFileDescriptor, type: String) {
            authorize(); fd.use { serial {
                check(phase == "active") { "Activate an emulator camera before switching its source" }
                val candidate = copySource(fd, type)
                val previous = source
                try { configure(candidate, settings); source = candidate; previous?.file?.delete() }
                catch (error: Exception) { candidate.file.delete(); rejectActivation(error) }
            } }
        }
        override fun updateSettings(text: String) { authorize(); serial {
            val next = configuration(text)
            check(phase != "active" || (next.width == settings.width && next.height == settings.height && next.cameraId == settings.cameraId)) { "Deactivate before changing resolution or target camera" }
            if (phase == "active") {
                try { configure(requireNotNull(source), next) } catch (error: Exception) { rejectActivation(error) }
            } else settings = next
        } }
    }
    private fun copySource(fd: ParcelFileDescriptor, type: String): Source {
        require(type in listOf("image", "video")) { "Invalid media type" }
        val file = File.createTempFile("source-", ".media", filesDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Source copy was cancelled" }
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= 256L * 1024 * 1024) { "The emulator companion accepts media up to 256 MiB" }
                    output.write(buffer, 0, count); digest.update(buffer, 0, count)
                }
            } }
            require(size > 0) { "Selected source is empty" }
            return Source(file, type, digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        } catch (error: Exception) { file.delete(); throw error }
    }
    private fun configure(selected: Source, next: Settings) {
        val epoch = configurationEpoch.incrementAndGet()
        try {
            configureSource(selected, next, epoch)
        } catch (error: Exception) {
            if (epoch != configurationEpoch.get() || Thread.currentThread().isInterrupted) throw cancelled()
            throw error
        }
    }
    private fun configureSource(selected: Source, next: Settings, epoch: Long) {
        phase = "activating"; stage = "prerequisites"; proof = null; lastError = null
        requireIdentity()
        val connection = EmulatorConnection.read(filesDir)
        requireResolution(next, connection)
        val readiness = support()
        if (!readiness.getBoolean("supported")) throw BrokerFailure(readiness.getString("code"), readiness.getString("message"), readiness.getString("recovery"))
        // Host conversion happens before the atomic renderer switch. Keep the
        // old source's lease alive throughout that potentially long conversion.
        stage = "renderer_configuration"
        val pending = PendingActivation(connection, UUID.randomUUID().toString())
        synchronized(transition) {
            check(epoch == configurationEpoch.get() && !Thread.currentThread().isInterrupted) { "Activation was cancelled before upload" }
            check(pendingActivation.compareAndSet(null, pending)) { "A previous source request still requires cancellation" }
        }
        val remote = try {
            connection.request("/activate", mapOf("X-Vcam-Settings" to next.json(selected.type).toString(), "X-Vcam-Request" to pending.requestId), file = selected.file)
        } catch (error: Exception) {
            if (epoch != configurationEpoch.get() || Thread.currentThread().isInterrupted) throw cancelled()
            throw error
        }
        val generation = remote.optString("generation")
        if (generation.isBlank()) throw BrokerFailure("EMULATOR_GENERATION_MISSING", "The renderer did not return a source generation", "Check the local companion protocol.")
        val current = Session(connection, generation, selected.sha256)
        val installed = synchronized(transition) {
            if (epoch != configurationEpoch.get() || Thread.currentThread().isInterrupted || pendingActivation.get() !== pending) false
            else {
                session.set(current)
                pendingActivation.compareAndSet(pending, null)
                true
            }
        }
        if (!installed) {
            runCatching { cancelPending(pending) }
            runCatching { connection.request("/deactivate", mapOf("X-Vcam-Generation" to generation), post = true) }
            throw cancelled()
        }
        requireHostIdentity(remote, connection)
        check(remote.optBoolean("rendererConfigured") && remote.optString("sourceSha256") == selected.sha256 &&
            remote.optInt("width") == next.width && remote.optInt("height") == next.height) { "The configured emulator renderer does not match the source hash and output settings" }
        settings = next; sourceRevision++
        renew(current)
        wakeLock.acquire(20000)
        stage = "frame_verification"
        val captured = CameraFrameProbe(this).capture(next.cameraId, preferredWidth = next.width, preferredHeight = next.height)
        check(captured.previewFrames >= 3 && captured.jpeg.isNotEmpty() && captured.lastTimestamp > captured.firstTimestamp) { "Actual emulator Camera2 frame evidence was incomplete" }
        if (captured.previewWidth != next.width || captured.previewHeight != next.height || captured.jpegWidth != next.width || captured.jpegHeight != next.height)
            throw BrokerFailure("CAMERA_RESOLUTION_MISMATCH", "Actual Camera2 YUV or JPEG dimensions differ from requested ${next.width}x${next.height}", "Select an output size advertised by both YUV and JPEG on this emulator camera.")
        val verified = connection.request("/verify", mapOf("X-Vcam-Generation" to generation), bytes = captured.jpeg)
        requireHostIdentity(verified, connection)
        if (!verified.optBoolean("sourceVerified") || verified.optString("generation") != generation)
            throw BrokerFailure("SOURCE_PROOF_MISMATCH", "The actual camera JPEG does not match the selected media source", "Close camera clients and retry activation; inspect the companion's source verification metrics.")
        val verifiedProof = captured.metrics().put("backend", EmulatorConnection.BACKEND).put("rendererConfigured", true)
            .put("sourceVerified", true).put("sourceSha256", selected.sha256).put("hostGeneration", generation)
            .put("sourceRevision", sourceRevision).put("verifiedAtElapsedMs", SystemClock.elapsedRealtime())
        if (verified.has("bestMeanAbsoluteError")) verifiedProof.put("bestMeanAbsoluteError", verified.get("bestMeanAbsoluteError"))
        synchronized(transition) {
            check(epoch == configurationEpoch.get() && session.get() === current && current.leaseDeadlineMs > SystemClock.elapsedRealtime() && !Thread.currentThread().isInterrupted) { "The emulator camera session ended during verification" }
            proof = verifiedProof; phase = "active"; stage = "running"; showNotification()
        }
        Log.i(TAG, "event=emulator_activation_verified cameraId=${next.cameraId} previewFrames=${captured.previewFrames} generation=$generation")
    }
    private fun renew(current: Session) {
        if (current.leaseDeadlineMs != 0L && current.leaseDeadlineMs <= SystemClock.elapsedRealtime()) throw BrokerFailure("EMULATOR_LEASE_EXPIRED", "The emulator camera lease expired", "Reactivate the selected source.")
        val response = current.connection.request("/heartbeat", mapOf("X-Vcam-Generation" to current.generation), post = true)
        requireHostIdentity(response, current.connection)
        val remaining = response.optLong("leaseRemainingMs")
        check(response.optBoolean("rendererConfigured") && response.optString("generation") == current.generation &&
            response.optString("sourceSha256") == current.sha256 && remaining in 1..30000) { "The emulator companion lost the configured source or lease" }
        current.leaseDeadlineMs = SystemClock.elapsedRealtime() + remaining
        if (session.get() === current) wakeLock.acquire(20000)
    }
    private fun stopHost() {
        val stopped = synchronized(transition) {
            configurationEpoch.incrementAndGet(); proof = null
            session.getAndSet(null) to pendingActivation.get()
        }
        if (wakeLock.isHeld) wakeLock.release()
        var error: Throwable? = null
        var requestCancelled = false
        try { stopped.second?.let { cancelPending(it); requestCancelled = true } } catch (failure: Throwable) { error = failure }
        try { stopped.first?.let { it.connection.request("/deactivate", mapOf("X-Vcam-Generation" to it.generation), post = true) } }
        catch (failure: Throwable) {
            // Cancelling an upload may already restore its committed source,
            // replacing the older locally known generation in the process.
            val alreadyRestored = requestCancelled && failure is BrokerFailure && failure.code in listOf("NOT_CONFIGURED", "STALE_GENERATION")
            if (!alreadyRestored && error == null) error = failure
        }
        error?.let { throw it }
    }
    private fun cancelPending(pending: PendingActivation) {
        // Keep the request reachable for another cleanup attempt if HTTP fails.
        // This ID cancels only its own upload/conversion, never a later source.
        val response = pending.connection.request("/cancel", mapOf("X-Vcam-Request" to pending.requestId), post = true)
        requireHostIdentity(response, pending.connection)
        check(response.optBoolean("requestCancelled") && response.optString("cancelledRequest") == pending.requestId) { "The companion did not confirm cancellation of the exact pending source request" }
        pendingActivation.compareAndSet(pending, null)
    }
    private fun cancelled() = BrokerFailure("ACTIVATION_CANCELLED", "Emulator camera activation was cancelled", "Activate again when the camera is ready.")
    private fun rejectActivation(error: Throwable): Nothing {
        if (phase == "stopped" && error is BrokerFailure && error.code == "ACTIVATION_CANCELLED") throw error
        fail(error)
        throw requireNotNull(lastError)
    }
    private fun failure(error: Throwable, code: String = "EMULATOR_PIPELINE_FAILURE"): BrokerFailure = error as? BrokerFailure
        ?: BrokerFailure(code, error.cause?.message ?: error.message ?: "Android Emulator camera operation failed", "Check the emulator companion, source decode, and camera availability; then activate again.")
    private fun fail(error: Throwable) {
        val result = failure(error)
        phase = "error"; lastError = result
        runCatching { stopHost() }.onFailure { Log.e(TAG, "Emulator cleanup failed; host lease will expire", it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.e(TAG, "event=emulator_pipeline_failed code=${result.code} stage=$stage", error)
    }
    private fun heartbeatFailure(current: Session, error: Throwable) {
        synchronized(transition) {
            if (!session.compareAndSet(current, null)) return
            // /activate can install a new generation just before its response
            // arrives. A heartbeat belonging to the old source must not cancel
            // that valid transition; all other heartbeat failures are fatal.
            if (phase == "activating" && error is BrokerFailure && error.code == "STALE_GENERATION") return
            configurationEpoch.incrementAndGet()
            proof = null; phase = "error"; lastError = failure(error, "EMULATOR_HEARTBEAT_FAILED")
        }
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        pendingActivation.get()?.let { runCatching { cancelPending(it) } }
        runCatching { current.connection.request("/deactivate", mapOf("X-Vcam-Generation" to current.generation), post = true) }
        Log.e(TAG, "event=emulator_lease_failed", error)
    }
    private fun deactivateCamera() {
        try { stopHost(); phase = "stopped"; stage = "idle"; lastError = null }
        catch (error: Exception) { fail(error); throw lastError!! }
        finally { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "Android Emulator camera", NotificationManager.IMPORTANCE_LOW))
        filesDir.listFiles()?.filter { it.name.startsWith("source-") }?.forEach { it.delete() }
        heartbeat.scheduleWithFixedDelay({
            session.get()?.let { current -> try { renew(current) } catch (error: Exception) { heartbeatFailure(current, error) } }
        }, 2, 2, TimeUnit.SECONDS)
    }
    private fun showNotification() {
        val stop = PendingIntent.getService(this, 0, Intent(this, BrokerService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(if (phase == "active") "Emulator virtual camera active" else "Android Emulator camera service")
            .setContentText(if (phase == "active") "Camera ${settings.cameraId} uses your verified selected media" else "Waiting for verified emulator camera activation")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Deactivate", stop).build()).build()
        startForeground(7712, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") cleanup.execute { runCatching { deactivateCamera() } }
        else showNotification()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        heartbeat.shutdownNow()
        cleanup.execute { runCatching { stopHost() }; source?.file?.delete(); source = null }
        cleanup.shutdown()
        control.shutdownNow()
        super.onDestroy()
    }
    companion object {
        private const val TAG = "VirtualCameraEmulator"
        private const val CHANNEL = "virtual-camera-emulator"
        private const val COMPONENT = "Android Emulator environment camera"
    }
}
