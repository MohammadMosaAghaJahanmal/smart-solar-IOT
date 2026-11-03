package com.virtualcamerapro.broker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.Context
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
import com.virtualcamerapro.engine.CompatibilityFallback
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
    @Volatile private var fallback = JSONObject()
    @Volatile private var destroyed = false
    private var selected: Source? = null
    private var settings = OutputSettings()
    private var revision = 0L
    private val sessions by lazy { BrokerSessionStore(this) }
    private var recoveryFailures = 0
    private var nextRecoveryMs = 0L
    private class ResumeCancelled : java.util.concurrent.CancellationException("A user action cancelled camera resumption")
    private fun recoveryPreference(key: String, default: Boolean) =
        runCatching { getSharedPreferences("virtual-camera-unified-setup-v1",Context.MODE_PRIVATE).getBoolean(key,default) }.getOrDefault(false)
    private fun rememberSession() = synchronized(transition) {
        if (phase == "active" && producer != null) selected?.let { sessions.save(it.file,it.type,it.sha256,settings) }
    }
    private fun explicitlyStop() = synchronized(transition) { sessions.inactive(); stopCamera() }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "VirtualCameraPro:VMOS-guest-camera").apply { setReferenceCounted(false) }
    }

    private fun authorize() {
        if (packageName == "com.virtualcamerapro" && Binder.getCallingUid() == Process.myUid()) return
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
            failUnlessStopped(GuestFailure("VMOS_ACTIVATION_TIMEOUT", "The guest camera operation exceeded 90 seconds", "Inspect the guest transport and reactivate."))
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

    private fun cameras(factory: String): Map<String, GuestCameraMapping.FactoryCamera> =
        GuestCameraMapping.resolve(this, factory, CameraInventory.query(this))
    private fun availableResolutions(cameraId: String): List<Pair<Int, Int>> {
        val manager = getSystemService(CameraManager::class.java)
        if (cameraId !in manager.cameraIdList) return emptyList()
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
        fun sizes(format: Int) = map.getOutputSizes(format)?.map { it.width to it.height }?.toSet() ?: emptySet()
        val yuv = sizes(ImageFormat.YUV_420_888); val jpeg = sizes(ImageFormat.JPEG)
        return yuv.intersect(jpeg).filter { (w,h) -> w in 32..4096 && h in 32..4096 && w % 2 == 0 && h % 2 == 0 &&
            w.toLong() * h <= 8_847_360 }.sortedBy { it.first.toLong() * it.second }
    }
    private fun bothCameraIds(mapping: Map<String, GuestCameraMapping.FactoryCamera>): List<String> {
        val front = mapping.filterValues { it.facing == "front" }.keys
        val back = mapping.filterValues { it.facing == "back" }.keys
        return if (front.isNotEmpty() && back.isNotEmpty()) (back + front).toList() else emptyList()
    }
    private fun commonResolutions(ids: List<String>): List<Pair<Int, Int>> =
        if (ids.isEmpty()) emptyList() else ids.map { availableResolutions(it).toSet() }
            .reduce { answer, next -> answer.intersect(next) }.sortedBy { it.first.toLong() * it.second }
    private fun replacementCameras(value: OutputSettings, mapping: Map<String, GuestCameraMapping.FactoryCamera>): List<String> {
        if (value.replacementMode == "single") return listOf(value.cameraId)
        val ids = bothCameraIds(mapping)
        if (ids.isEmpty() || value.cameraId !in ids) throw GuestFailure("BOTH_CAMERAS_UNAVAILABLE",
            "Both front and back cameras need an unambiguous verified guest transport mapping",
            "Choose a single mapped camera or inspect the detected camera capabilities.")
        return ids
    }
    private fun support(): JSONObject = try {
        requireIdentity()
        val factory = if (phase == "active" || producer != null) {
            val checkedAt = epoch.get()
            try { runtime.checkTransport() }
            catch (error: Throwable) { fail(error,checkedAt); throw error }
        } else runtime.checkTransport()
        val mapping = cameras(factory)
        if (mapping.isEmpty()) throw GuestFailure("VMOS_CAMERA_MAPPING_UNVERIFIED", "The real factory did not expose an unambiguous matching guest camera", "Inspect the actual factory names and Camera2 facing before configuring this guest.")
        val inventory = CameraInventory.query(this)
        val usable = JSONArray()
        for (i in 0 until inventory.length()) inventory.getJSONObject(i).let { if (it.getString("id") in mapping && availableResolutions(it.getString("id")).isNotEmpty()) usable.put(it) }
        if (usable.length() == 0) throw GuestFailure("RESOLUTION_UNAVAILABLE", "No mapped guest camera advertises a supported common YUV/JPEG output", "Check the camera capabilities in Diagnostics.")
        val id = usable.getJSONObject(0).getString("id")
        val choices = JSONArray()
        availableResolutions(id).forEach { choices.put(JSONObject().put("width", it.first).put("height", it.second)) }
        val bothIds = bothCameraIds(mapping)
        val bothSizes = commonResolutions(bothIds)
        val bothChoices = JSONArray()
        bothSizes.forEach { bothChoices.put(JSONObject().put("width",it.first).put("height",it.second)) }
        val modes = JSONArray().put("single")
        if (bothSizes.isNotEmpty()) modes.put("both")
        identity(JSONObject().put("protocol", 2).put("supported", true).put("code", "READY")
            .put("component", COMPONENT).put("message", "VMOS guest camera bridge connected")
            .put("recovery", "").put("cameras", usable).put("cameraId", id).put("resolutions", choices)
            .put("replacementModes",modes).put("bothCameraIds",JSONArray(bothIds)).put("bothResolutions",bothChoices))
    } catch (error: Throwable) {
        val failure = failure(error)
        identity(JSONObject().put("protocol", 2).put("supported", false).put("code", failure.code)
            .put("component", COMPONENT).put("message", failure.message).put("recovery", failure.recovery))
    }
    private fun parse(text: String): OutputSettings {
        val json = JSONObject(text)
        return OutputSettings(json.getInt("width"), json.getInt("height"), json.getBoolean("loop"),
            json.getInt("rotation"), json.getString("scaleMode"), json.getString("cameraId"),
            json.optDouble("zoom", 1.0), json.optDouble("panX", 0.0), json.optDouble("panY", 0.0),
            json.optString("replacementMode","single"))
    }
    private fun checkConfiguration(value: OutputSettings, mapping: Map<String, GuestCameraMapping.FactoryCamera>) {
        if (value.cameraId !in mapping) throw GuestFailure("CAMERA_ID_UNAVAILABLE", "This guest camera has no unambiguous actual transport mapping", "Select one of the mapped cameras reported by the broker.")
        val ids = replacementCameras(value,mapping)
        if (value.width to value.height !in commonResolutions(ids)) throw GuestFailure("RESOLUTION_UNAVAILABLE", "The selected guest cameras do not share ${value.width}x${value.height} YUV and JPEG output", "Select an advertised common output resolution.")
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
            .put("replacementMode",current?.settings?.replacementMode ?: settings.replacementMode)
            .put("cameraIds",if (valid) proof?.optJSONArray("cameraIds") ?: JSONArray() else JSONArray())
            .put("frames", if (valid) current?.frames ?: 0 else 0).put("streams", 0).put("sourceRevision", revision)
            .put("actualSettings",outputJson(settings)).put("fallback",JSONObject(fallback.toString()))
            .put("proof", if (valid) proof else null)).toString()
    }
    private val binder = object : IVirtualCameraBroker.Stub() {
        override fun getSupport(): String { authorize(); return serial { support().toString() } }
        override fun getStatus(): String { authorize(); return state() }
        override fun activate(fd: ParcelFileDescriptor, type: String, text: String): Boolean {
            authorize()
            val requestedAt = epoch.get()
            return fd.use { serial {
                check(phase !in listOf("active", "activating")) { "Deactivate the existing guest camera first" }
                var next: Source? = null
                try {
                    next = copySource(fd, type,requestedAt)
                    configure(next, parse(text),expectedEpoch=requestedAt)
                    selected?.file?.delete(); selected = next; rememberSession(); true
                } catch (error: Throwable) {
                    next?.file?.delete()
                    if (!failUnlessStopped(error)) return@serial false
                    throw IllegalStateException(failure(error).message, error)
                }
            } }
        }
        override fun deactivate() { authorize(); explicitlyStop() }
        override fun updateSource(fd: ParcelFileDescriptor, type: String) {
            authorize(); val requestedAt = epoch.get(); fd.use { serial {
                check(phase == "active") { "Activate the guest camera before replacing its source" }
                val next = copySource(fd, type,requestedAt)
                try { configure(next, settings,expectedEpoch=requestedAt); selected?.file?.delete(); selected = next; rememberSession() }
                catch (error: Throwable) {
                    next.file.delete()
                    if (!failUnlessStopped(error)) return@serial
                    throw IllegalStateException(failure(error).message, error)
                }
            } }
        }
        override fun updateSettings(text: String) { authorize(); serial {
            val value = parse(text)
            if (phase == "active") {
                check(value.width == settings.width && value.height == settings.height && value.cameraId == settings.cameraId &&
                    value.replacementMode == settings.replacementMode) { "Deactivate before changing the camera replacement mode or resolution" }
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
                } catch (error: Throwable) {
                    if (!failUnlessStopped(error)) return@serial
                    throw IllegalStateException(failure(error).message, error)
                }
            } else settings = value
            if (phase == "active") rememberSession()
        } }
    }

    private fun copySource(fd: ParcelFileDescriptor, type: String, expectedEpoch: Long): Source {
        require(type in listOf("image", "video")) { "Invalid selected media type" }
        val file = File.createTempFile("source-", ".media", filesDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input -> file.outputStream().use { output ->
                val bytes = ByteArray(65536)
                while (true) {
                    if (epoch.get() != expectedEpoch) throw ResumeCancelled()
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
    private fun outputJson(value: OutputSettings) = JSONObject().put("cameraId",value.cameraId)
        .put("width",value.width).put("height",value.height).put("replacementMode",value.replacementMode)
        .put("loop",value.loop).put("rotation",value.rotation).put("scaleMode",value.scaleMode)
        .put("zoom",value.zoom).put("panX",value.panX).put("panY",value.panY)

    private fun configure(source: Source, value: OutputSettings, expectedEpoch: Long? = null, resuming: Boolean = false) {
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 75_000L // Leave time for cleanup before the Binder's 90-second limit.
        val transaction = synchronized(transition) {
            if (expectedEpoch != null && epoch.get() != expectedEpoch || resuming && !sessions.requested()) throw ResumeCancelled()
            val next = epoch.incrementAndGet()
            phase = "activating"; stage = "prerequisites"; lastError = null; proof = null; fallback = JSONObject()
            next
        }
        requireIdentity(); runtime.installed()
        val recovered = runtime.recoverInstalled { GuestRoot(this).restartProvider() }
        val factory = recovered.getString("cameraFactory")
        val inventory = CameraInventory.query(this)
        val mapping = GuestCameraMapping.resolve(this,factory,inventory)
        val capabilities = (0 until inventory.length()).map { inventory.getJSONObject(it) }
            .filter { it.getString("id") in mapping }.map { item ->
                CompatibilityFallback.Camera(item.getString("id"),item.getString("facing"),availableResolutions(item.getString("id")))
            }
        val identity = GuestCameraMapping.environmentIdentity(this,factory,inventory)
        val store = GuestFallbackStore(this)
        val cached = store.load(identity,source.type,value)
        val candidates = CompatibilityFallback.plan(value,capabilities,bothCameraIds(mapping),cached?.settings,maxAttempts=5)
        if (candidates.isEmpty()) throw GuestFailure("NO_COMPATIBLE_OUTPUT", "No verified camera routes offer an implemented output configuration",
            "Check guest setup and current camera capabilities. Both-camera mode is never downgraded to a single camera.")
        val attempts = JSONArray()
        fun publishAttempts() {
            fallback = JSONObject().put("requested",outputJson(value)).put("attempts",JSONArray(attempts.toString()))
                .put("elapsedMs",SystemClock.elapsedRealtime()-started).put("maximumAttempts",8)
        }
        var last: Throwable? = null
        for (candidate in candidates) {
            val strategies = if (cached?.settings?.let { it.cameraId == candidate.output.cameraId && it.width == candidate.output.width && it.height == candidate.output.height } == true)
                listOf(cached.strategy,"combined","sequential").distinct() else listOf("combined","sequential")
            for (strategy in strategies) {
                if (epoch.get() != transaction || Thread.currentThread().isInterrupted) throw ResumeCancelled()
                if (attempts.length() >= 8 || SystemClock.elapsedRealtime() >= deadline-3000) break
                val attempt = outputJson(candidate.output).put("streamStrategy",strategy).put("reason",candidate.reason)
                    .put("status","testing")
                attempts.put(attempt); publishAttempts()
                val attemptStarted = SystemClock.elapsedRealtime()
                try {
                    configureAttempt(source,candidate.output,mapping,transaction,strategy,deadline)
                    attempt.put("status","verified").put("elapsedMs",SystemClock.elapsedRealtime()-attemptStarted)
                    var saved = true
                    runCatching { store.save(identity,source.type,value,candidate.output,strategy) }
                        .onFailure { saved = false; Log.w(TAG,"Verified working output could not be cached",it) }
                    publishAttempts()
                    fallback = JSONObject(fallback.toString()).put("actual",outputJson(candidate.output))
                        .put("streamStrategy",strategy).put("saved",saved).put("verified",true)
                        .put("used",candidate.output.cameraId != value.cameraId || candidate.output.width != value.width ||
                            candidate.output.height != value.height || strategy != "combined" || attempts.length() > 1)
                    return
                } catch (error: Exception) {
                    if (epoch.get() != transaction || error is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted) throw ResumeCancelled()
                    last = error
                    attempt.put("status","failed").put("elapsedMs",SystemClock.elapsedRealtime()-attemptStarted)
                        .put("message",error.message?.take(512) ?: error.javaClass.simpleName)
                    publishAttempts()
                    if (!retryable(error) || SystemClock.elapsedRealtime() >= deadline-3000) throw error
                    // A transient camera-in-use or closing session needs a brief, bounded yield.
                    Thread.sleep(250)
                }
            }
        }
        throw GuestFailure("COMPATIBILITY_EXHAUSTED", "No working camera configuration was verified after ${attempts.length()} bounded attempts: ${last?.message?.take(500) ?: "time budget exhausted"}",
            "Release cameras held by other applications, then retry. Diagnostics retain every attempted supported configuration.")
    }

    private fun retryable(error: Throwable): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is SecurityException || cause is InterruptedException || cause is java.util.concurrent.CancellationException || cause is Error) return false
            if (cause is android.hardware.camera2.CameraAccessException && cause.reason == android.hardware.camera2.CameraAccessException.CAMERA_DISABLED) return false
            if (cause is GuestFailure && cause.code != "VMOS_SOURCE_PIXELS_MISMATCH" && cause.code != "RESOLUTION_UNAVAILABLE") return false
            cause = cause.cause
        }
        return true
    }

    private fun configureAttempt(source: Source, value: OutputSettings, mapping: Map<String,GuestCameraMapping.FactoryCamera>,
        transaction: Long, strategy: String, deadline: Long) {
        checkConfiguration(value,mapping)
        val cameraIds = replacementCameras(value,mapping)
        val cameraNames = cameraIds.map { mapping.getValue(it).name }
        lateinit var next: GuestFrameProducer
        next = GuestFrameProducer(runtime.stateDir, source.sha256, mapping.getValue(value.cameraId).name, value, cameraNames) { error ->
            runCatching { cleanup.execute { synchronized(transition) { if (producer === next && phase == "active") fail(error) } } }
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
            val captures = JSONArray()
            var primary: JSONObject? = null
            var worstError = 0.0
            for (id in cameraIds) {
                check(transaction == epoch.get() && producer === next && !Thread.currentThread().isInterrupted) { "Activation was cancelled during camera verification" }
                stage = "camera2_yuv_jpeg_verification:$id"
                val remaining = deadline-SystemClock.elapsedRealtime()
                check(remaining > 1000) { "Activation verification time budget exhausted" }
                val captured = CameraFrameProbe(this).capture(id,400,value.width,value.height,strategy,minOf(remaining,12_400L))
                check(captured.previewFrames >= 3 && captured.lastTimestamp > captured.firstTimestamp && captured.jpeg.isNotEmpty()) { "Guest camera $id did not receive actual increasing YUV frames and a JPEG" }
                check(captured.previewWidth == value.width && captured.previewHeight == value.height && captured.jpegWidth == value.width && captured.jpegHeight == value.height) { "Guest camera $id delivered a different resolution from the selected output" }
                val error = next.jpegError(captured.jpeg)
                if (error > 25.0) throw GuestFailure("VMOS_SOURCE_PIXELS_MISMATCH", "The actual camera $id JPEG does not match the decoded selected media (MAE ${"%.2f".format(error)})", "Inspect the measured camera mapping and proxy conversion; Active was refused.")
                val evidence = captured.metrics().put("cameraId",id).put("sourceVerified",true).put("bestMeanAbsoluteError",error)
                captures.put(evidence)
                if (id == value.cameraId) primary = evidence
                worstError = maxOf(worstError,error)
            }
            stage = "native_delivery_verification"
            val delivery = runtime.status()
            check(delivery.optInt("protocol") in 1..2 && delivery.optBoolean("configured") && delivery.optString("sourceId") == next.generation &&
                delivery.optString("sourceSha256") == source.sha256 && delivery.optString("cameraName") == mapping.getValue(value.cameraId).name &&
                delivery.optLong("sourceFrames") >= 3 && delivery.optLong("lastFrameBootNs") > SystemClock.elapsedRealtimeNanos() - 3_000_000_000L &&
                delivery.optLong("expiresBootNs") > SystemClock.elapsedRealtimeNanos()) { "The native proxy did not acknowledge fresh frame delivery for the exact selected generation" }
            if (value.replacementMode == "both") {
                val acknowledged = delivery.optJSONArray("cameraNames")
                val actualNames = if (acknowledged == null) emptySet() else (0 until acknowledged.length()).map { acknowledged.getString(it) }.toSet()
                val delivered = delivery.optJSONObject("cameraFrames")
                check(actualNames == cameraNames.toSet() && cameraNames.all { (delivered?.optLong(it,0) ?: 0) >= 3 }) {
                    "The native proxy did not acknowledge actual frames for every selected camera route: $delivery"
                }
            }
            for (index in 0 until captures.length()) {
                val item = captures.getJSONObject(index)
                val name = mapping.getValue(item.getString("cameraId")).name
                item.put("cameraName",name).put("proxyFrames",delivery.optJSONObject("cameraFrames")?.optLong(name,0) ?: delivery.optLong("sourceFrames"))
            }
            synchronized(transition) {
                check(transaction == epoch.get() && producer === next && next.expiresNs > SystemClock.elapsedRealtimeNanos() && !Thread.currentThread().isInterrupted) { "Activation was cancelled during verification" }
                revision++; settings = value
                proof = JSONObject(requireNotNull(primary).toString()).put("backend", GuestRuntime.BACKEND).put("sourceVerified", true)
                    .put("rendererConfigured", true).put("sourceSha256", source.sha256).put("generation", next.generation)
            .put("producerFrames", next.frames).put("proxyFrames", delivery.getLong("sourceFrames"))
                    .put("proxyPid", delivery.getInt("pid")).put("bestMeanAbsoluteError", worstError)
                    .put("replacementMode",value.replacementMode).put("cameraIds",JSONArray(cameraIds)).put("cameras",captures)
                    .put("streamStrategy",strategy).put("sourceRevision", revision).put("verifiedAtElapsedMs", SystemClock.elapsedRealtime())
                phase = "active"; stage = "running"; showNotification()
            }
        } catch (error: Throwable) {
            try {
                synchronized(transition) { if (producer === next) { producer = null; next.suspendPublication(); runtime.deactivate() } }
                next.close()
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
                throw GuestFailure("COMPATIBILITY_CLEANUP_FAILED","A failed camera attempt could not be completely closed",
                    "Stop the virtual camera before retrying. No further configuration was attempted.")
            }
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
    private fun pauseForSetup() {
        // Keep this service alive while the setup worker owns the shared lease.
        // A delayed onDestroy must not remove the verifier's new source.cfg.
        val stopped = synchronized(transition) {
            sessions.inactive()
            val previous = stopTransport()
            phase = "ready"; stage = "setup"; lastError = null
            previous
        }
        stopped?.close()
    }
    private fun failure(error: Throwable) = error as? GuestFailure ?: GuestFailure("VMOS_PIPELINE_FAILURE",
        error.cause?.message ?: error.message ?: "The guest camera pipeline failed", "Inspect the guest bridge, source decoder and Camera2 verification before reactivating.")
    private fun failUnlessStopped(error: Throwable): Boolean = synchronized(transition) {
        if (error is ResumeCancelled || phase == "stopped" && !sessions.requested()) false
        else { fail(error); true }
    }
    private fun fail(error: Throwable, expectedEpoch: Long? = null) = synchronized(transition) {
        if (expectedEpoch != null && epoch.get() != expectedEpoch) return@synchronized
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
        control.execute {
            synchronized(guestOperations) {
                val retained = sessions.load()?.file?.name
                filesDir.listFiles()?.filter { it.name.startsWith("source-") && it.name != retained }?.forEach { it.delete() }
            }
        }
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
                if (destroyed || setupInProgress.get() || !runtime.hasInstallationRecord() ||
                    !recoveryPreference("autoRecover",true) || SystemClock.elapsedRealtime() < nextRecoveryMs) return@synchronized
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
                    if (sessions.requested() && recoveryPreference("autoResume",false)) {
                        val requestedAt = epoch.get()
                        val saved = sessions.load() ?: throw GuestFailure("SAVED_MEDIA_UNAVAILABLE", "The previously selected media is missing or changed", "Select media and activate again.")
                        val next = Source(saved.file,saved.type,saved.hash)
                        configure(next,saved.settings,expectedEpoch=requestedAt,resuming=true)
                        selected = next
                        rememberSession()
                    }
                }
                recoveryFailures = 0; nextRecoveryMs = 0L
            } } catch (error: Throwable) {
                synchronized(transition) {
                    if (error is ResumeCancelled || !sessions.requested() && phase == "stopped") return@execute
                    recoveryFailures = minOf(recoveryFailures+1,6)
                    nextRecoveryMs = SystemClock.elapsedRealtime() + minOf(300_000L,5_000L * (1L shl recoveryFailures))
                    if (!destroyed) { stage = "transport_recovery"; fail(error) }
                }
            } finally { recoveryQueued.set(false) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryQueued.set(false) }
    }
    private fun showNotification() {
        if (destroyed) return
        val stop = PendingIntent.getService(this, 0, Intent(this, BrokerService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 1, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(this, SetupActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(when (phase) { "active" -> "VMOS virtual camera active"; "error" -> "VMOS guest camera needs attention"; else -> "VMOS guest camera service" })
            .setContentText(when (phase) {
                "active" -> if (settings.replacementMode == "both") "Front and back guest cameras use verified selected media" else "Guest camera ${settings.cameraId} uses verified selected media"
                "error" -> lastError?.message ?: "Open guest setup to inspect camera recovery"
                else -> "Keeping the installed guest camera bridge available"
            }).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Deactivate", stop).build()).build()
        startForeground(7713, notice)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { explicitlyStop() } else {
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
        cleanup.execute { stopped?.close(); selected = null }
        cleanup.shutdown(); super.onDestroy()
    }
    companion object {
        @Volatile private var liveService: BrokerService? = null
        private val setupInProgress = AtomicBoolean()
        private val guestOperations = Any()
        internal fun <T> withGuestSetup(context: Context? = null, operation: () -> T): T {
            synchronized(guestOperations) {
                check(setupInProgress.compareAndSet(false, true)) { "A guest setup operation is already running" }
                try {
                    val service = liveService
                    if (service != null) service.pauseForSetup()
                    else context?.let { BrokerSessionStore(it).inactive() }
                    return operation()
                }
                finally {
                    liveService?.let { service ->
                        if (!service.runtime.hasInstallationRecord()) {
                            synchronized(service.transition) {
                                service.phase = "stopped"; service.stage = "idle"
                                service.stopForeground(STOP_FOREGROUND_REMOVE)
                            }
                        }
                    }
                    setupInProgress.set(false)
                }
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
        /** Recheck the live session while holding the same lock as activation. */
        internal fun repairIfIdle(operation: () -> Unit): Boolean = synchronized(guestOperations) {
            val service = liveService
            if (setupInProgress.get() || service?.producer != null || service?.phase == "activating") false
            else { operation(); true }
        }
        internal const val ACTION_RECOVER_TRANSPORT = "com.virtualcamerapro.broker.RECOVER_GUEST_TRANSPORT"
        internal fun setupHealth(): JSONObject {
            val service = liveService
            return if (service == null) JSONObject().put("running",false)
            else JSONObject(service.state()).put("running",!service.destroyed)
        }
        internal fun verifySetup(context: Context, progress: (String,String) -> Unit): JSONObject =
            withGuestSetup(context) { GuestSetupVerifier.verify(context,progress) }
        internal fun clearSavedSelection(context: Context) {
            synchronized(guestOperations) {
                // Clearing media is a user Stop, not a temporary setup pause.
                // Preserve Stopped after deleting the recoverable selection.
                liveService?.explicitlyStop()
                liveService?.selected = null
                BrokerSessionStore(context).clear()
            }
        }
        private const val TAG = "VirtualCameraVMOS"
        private const val CHANNEL = "virtual-camera-vmos"
        private const val COMPONENT = "VMOS Android 9 guest camera bridge"
    }
}
