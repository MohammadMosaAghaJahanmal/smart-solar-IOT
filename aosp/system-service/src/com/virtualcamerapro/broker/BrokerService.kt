package com.virtualcamerapro.broker

import android.Manifest
import android.app.*
import android.companion.virtualcamera.*
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.util.Log
import android.view.Surface
import com.virtualcamerapro.engine.CameraOutputManager
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraInventory
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Privileged producer; Active requires a verified route and real Camera2 YUV/JPEG output. */
class BrokerService : Service() {
    private val control = Executors.newSingleThreadExecutor()
    private val cameraCallbacks = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val routeLock = Any()
    private var cameraService: IVirtualCameraService? = null
    private var halDeath: IBinder.DeathRecipient? = null
    @Volatile private var cameraToken: Binder? = null
    @Volatile private var virtualId = ""
    @Volatile private var engine: CameraOutputManager? = null
    @Volatile private var settings = OutputSettings()
    private var source: File? = null
    private var mediaType = "image"
    @Volatile private var phase = "ready"
    @Volatile private var stage = "idle"
    @Volatile private var generation = 0L
    @Volatile private var routing = false
    @Volatile private var lastError: IntegrationFailure? = null
    @Volatile private var proof: JSONObject? = null
    private val prefs by lazy { getSharedPreferences("source", MODE_PRIVATE) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VirtualCameraPro:producer")
            .apply { setReferenceCounted(false) }
    }

    private fun event(name: String) { Log.i(TAG, "event=$name stage=$stage cameraId=${settings.cameraId} virtualId=$virtualId generation=$generation") }
    private fun <T> serial(action: () -> T): T {
        val pending = control.submit(Callable { action() })
        return try { pending.get(45, TimeUnit.SECONDS) }
        catch (e: java.util.concurrent.ExecutionException) {
            val cause = e.cause
            val detail = if (cause is IntegrationFailure) "${cause.code}: ${cause.message}. ${cause.recovery}" else cause?.message
            throw IllegalStateException(detail ?: "Camera operation failed", cause)
        } catch (e: java.util.concurrent.TimeoutException) {
            pending.cancel(true)
            throw IllegalStateException("ACTIVATION_TIMEOUT: Camera operation timed out; routing will be cleared", e)
        }
    }
    private fun authorize() {
        enforceCallingPermission("com.virtualcamerapro.permission.CONTROL", "Untrusted controller signing certificate")
        val packages = packageManager.getPackagesForUid(Binder.getCallingUid()) ?: emptyArray()
        if ("com.virtualcamerapro" in packages) return
        // The optional reference-image acceptance APK is platform signed and userdebug only.
        if (PlatformAccess.get("ro.debuggable") == "1" && "com.virtualcamerapro.systemtests" in packages &&
            packageManager.checkSignatures(packageName, "com.virtualcamerapro.systemtests") == PackageManager.SIGNATURE_MATCH) return
        throw SecurityException("Unknown virtual camera controller package")
    }
    private fun configuration(text: String): OutputSettings {
        val j = JSONObject(text)
        return OutputSettings(j.getInt("width"), j.getInt("height"), j.getBoolean("loop"), j.getInt("rotation"), j.getString("scaleMode"), j.getString("cameraId"),
            j.optDouble("zoom", 1.0), j.optDouble("panX", 0.0), j.optDouble("panY", 0.0))
    }
    private fun identity(result: JSONObject): JSONObject = result
        .put("brokerPackage", packageName).put("processUid", Process.myUid()).put("processPid", Process.myPid())
        .put("platformSigned", packageManager.checkSignatures("android", packageName) == PackageManager.SIGNATURE_MATCH)
        .put("systemApp", applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
    private fun support(): JSONObject {
        try {
            if (Build.VERSION.SDK_INT != 36 || PlatformAccess.get("ro.virtualcamera.pro.version") != "2")
                throw IntegrationFailure("SYSTEM_IMAGE_MISSING", "reference system image", "The installed OS is not the Virtual Camera Pro protocol 2 reference image", "Build and boot vcam_cf_x86_64_phone on the supported Cuttlefish target; do not flash that image onto an OEM phone.")
            if (Process.myUid() != Process.SYSTEM_UID)
                throw IntegrationFailure("BROKER_NOT_PRIVILEGED", "platform broker identity", "The broker is not running as the platform system UID", "Install the Soong platform-signed broker through the reference system image.")
            val route = PlatformAccess.cameraRoute(this)
            if (!route.optBoolean("initialized") || !route.optBoolean("responsive"))
                throw IntegrationFailure("CAMERASERVICE_NOT_READY", "CameraService", "CameraService is not initialized or is busy", "Close camera clients and restart the controlled image's camera service.")
            if (PlatformAccess.service("virtual_camera")?.isBinderAlive != true)
                throw IntegrationFailure("PROVIDER_MISSING", "AOSP virtual camera HAL", "The virtual_camera Binder service is unavailable", "Build/install virtual_camera and the supplied init/provider configuration, then reboot the reference image.")
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
                throw IntegrationFailure("PROBE_PERMISSION_MISSING", "camera verification permission", "The broker lacks CAMERA permission for the activation probe", "Install the supplied default-permissions XML or grant CAMERA to com.virtualcamerapro.broker on the controlled test image.")
            return identity(JSONObject().put("supported", true).put("protocol", 2).put("code", "READY")
                .put("component", "CameraService + virtual_camera").put("message", "System camera routing and provider connected")
                .put("recovery", "").put("cameras", CameraInventory.query(this)))
        } catch (e: Exception) {
            val failure = e as? IntegrationFailure ?: IntegrationFailure("INTEGRATION_CHECK_FAILED", "system integration", e.cause?.message ?: e.message ?: "Integration check failed", "Collect diagnostics with scripts/device-diagnostics.py and rebuild the reference image.")
            return identity(JSONObject().put("supported", false).put("protocol", 2).put("code", failure.code)
                .put("component", failure.component).put("message", failure.message).put("recovery", failure.recovery))
        }
    }
    private fun requireSupport() {
        val support = support()
        if (!support.getBoolean("supported")) throw IntegrationFailure(support.getString("code"), support.getString("component"), support.getString("message"), support.getString("recovery"))
    }
    private fun state(): String {
        val valid = phase != "active" || (routeMatches() && engine?.failure == null && SystemClock.elapsedRealtime() - (engine?.lastTickMs ?: 0) < 4000)
        val error = lastError
        return identity(JSONObject().put("phase", if (valid) phase else "error").put("stage", stage)
            .put("message", error?.message ?: if (!valid) "Camera routing or frame producer stopped responding" else when (phase) {
                "active" -> "Virtual Camera Active"; "activating" -> "Activating…"; "stopped" -> "Virtual Camera Stopped"; else -> "Virtual Camera Ready"
            }).put("code", error?.code ?: if (valid) "OK" else "PIPELINE_LOST")
            .put("recovery", error?.recovery ?: "").put("virtualId", virtualId)
            .put("frames", engine?.frames ?: 0).put("streams", engine?.streams ?: 0)
            .put("sourceRevision", engine?.sourceRevision ?: 0).put("proof", proof)).toString()
    }
    private val binder = object : IVirtualCameraBroker.Stub() {
        override fun getSupport(): String { authorize(); return serial { support().toString() } }
        override fun getStatus(): String { authorize(); return state() }
        override fun activate(fd: ParcelFileDescriptor, type: String, config: String): Boolean {
            authorize()
            return fd.use { serial {
                check(phase != "active" && phase != "activating") { "Deactivate the existing virtual camera first" }
                var candidate: File? = null
                val previous = source
                try {
                    phase = "activating"; stage = "prerequisites"; lastError = null; proof = null; event("activation_requested")
                    requireSupport()
                    settings = configuration(config)
                    stage = "source_copy"; candidate = copySource(fd, type)
                    source = candidate; mediaType = type
                    startCamera()
                    prefs.edit().putString("file", candidate.name).putString("type", type).putString("settings", config).commit()
                    previous?.takeIf { it != candidate }?.delete()
                    true
                } catch (e: Exception) { fail(e); candidate?.delete(); source = previous; throw lastError!! }
            } }
        }
        override fun deactivate() { authorize(); serial {
            try { stopCamera(); lastError = null; phase = "stopped"; stage = "idle"; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); event("deactivated") }
            catch (e: Exception) { fail(e); throw lastError!! }
        } }
        override fun updateSource(fd: ParcelFileDescriptor, type: String) {
            authorize(); fd.use { serial {
                check(phase == "active") { "Activate a virtual camera before switching its source" }
                val next = copySource(fd, type)
                try {
                    val producer = requireNotNull(engine)
                    val hasConsumer = producer.streams > 0
                    producer.start(next, type, settings); producer.awaitReady()
                    if (!hasConsumer || !producer.awaitDelivery()) verifyPipeline(producer)
                    proof = requireNotNull(proof).let { JSONObject(it.toString()).put("sourceRevision", producer.sourceRevision) }
                    val previous = source; source = next; mediaType = type; previous?.delete()
                    prefs.edit().putString("file", next.name).putString("type", type).commit(); event("source_switched")
                } catch (e: Exception) { next.delete(); fail(e); throw lastError!! }
            } }
        }
        override fun updateSettings(text: String) { authorize(); serial {
            val next = configuration(text)
            check(phase != "active" || (next.width == settings.width && next.height == settings.height && next.cameraId == settings.cameraId)) { "Deactivate before changing resolution or target camera" }
            engine?.update(next); settings = next; prefs.edit().putString("settings", text).commit()
        } }
    }
    private fun copySource(fd: ParcelFileDescriptor, type: String): File {
        require(type in listOf("image", "video")) { "Invalid media type" }
        val file = File.createTempFile("source-", ".media", filesDir)
        try {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd.fileDescriptor)).use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    check(!Thread.currentThread().isInterrupted) { "Source preparation was cancelled" }
                    val count = input.read(buffer); if (count < 0) break
                    output.write(buffer, 0, count)
                }
            } }
            check(!Thread.currentThread().isInterrupted) { "Source preparation was cancelled" }
            check(file.length() > 0) { "Selected source is empty" }; return file
        } catch (e: Exception) { file.delete(); throw e }
    }
    private fun callbackFor(epoch: Long, producer: CameraOutputManager) = object : IVirtualCameraCallback.Stub() {
        override fun onStreamConfigured(id: Int, surface: Surface, width: Int, height: Int, format: Int) {
            // Never queue this on control: activate() waits there for these very frames.
            if (cameraCallbacks.isShutdown) { surface.release(); return }
            cameraCallbacks.execute {
                if (epoch != generation || engine !== producer) { surface.release(); return@execute }
                try { producer.add(id, surface, width, height); event("consumer_stream_connected") }
                catch (e: Exception) { surface.release(); reportEngineFailure(epoch, e) }
            }
        }
        override fun onProcessCaptureRequest(id: Int, frameId: Int) { if (epoch == generation && engine === producer) producer.request(id) }
        override fun onStreamClosed(id: Int) {
            if (!cameraCallbacks.isShutdown) cameraCallbacks.execute {
                if (epoch == generation && engine === producer) try { producer.remove(id); event("consumer_stream_closed") } catch (e: Exception) { reportEngineFailure(epoch, e) }
            }
        }
    }
    private fun reportEngineFailure(epoch: Long, error: Throwable) {
        val current = synchronized(routeLock) {
            if (epoch != generation) false else { runCatching { clearRoute() }; true }
        }
        if (!current) return
        if (!control.isShutdown) control.execute { if (epoch == generation) fail(error) }
    }
    private fun startCamera() {
        val manager = getSystemService(CameraManager::class.java)
        if (settings.cameraId !in manager.cameraIdList || settings.cameraId.startsWith("v0_"))
            throw IntegrationFailure("CAMERA_ID_UNAVAILABLE", "target camera", "Camera ${settings.cameraId} is not an available physical camera ID", "Select one of the camera IDs reported by Android on this device.")
        val lens = manager.getCameraCharacteristics(settings.cameraId).get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
        stage = "decode"; generation++
        val epoch = generation
        val producer = CameraOutputManager { reportEngineFailure(epoch, it) }
        engine = producer // Assign before initialization so every failure can release it.
        producer.start(requireNotNull(source), mediaType, settings); producer.awaitReady(); event("decoder_ready")
        stage = "provider_registration"
        val hal = IVirtualCameraService.Stub.asInterface(requireNotNull(PlatformAccess.service("virtual_camera")))
        val death = IBinder.DeathRecipient { reportEngineFailure(epoch, IllegalStateException("AOSP virtual_camera service died")) }
        halDeath = death
        hal.asBinder().linkToDeath(death, 0); cameraService = hal
        val token = Binder(); cameraToken = token
        val stream = SupportedStreamConfiguration().apply { width = settings.width; height = settings.height; pixelFormat = Format.RGBA_8888; maxFps = 30 }
        val config = VirtualCameraConfiguration().apply {
            supportedStreamConfigs = arrayOf(stream); virtualCameraCallback = callbackFor(epoch, producer)
            sensorOrientation = SensorOrientation.ORIENTATION_0; lensFacing = lens
        }
        check(hal.registerCamera(token, config, 0)) { "Virtual camera HAL rejected registration or required EGL extensions" }
        virtualId = hal.getCameraId(token)
        check(virtualId.isNotEmpty()) { "Virtual camera HAL returned an empty ID" }
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (virtualId !in manager.cameraIdList) {
            check(SystemClock.elapsedRealtime() < deadline) { "CameraService did not discover $virtualId" }; Thread.sleep(60)
        }
        manager.getCameraCharacteristics(virtualId); event("provider_registered")
        stage = "routing"
        check(!Thread.currentThread().isInterrupted) { "Activation was cancelled" }
        wakeLock.acquire()
        synchronized(routeLock) { routing = true; renewLease() }
        stage = "frame_verification"; verifyPipeline(producer)
        check(!Thread.currentThread().isInterrupted) { "Activation was cancelled" }
        phase = "active"; stage = "running"; showNotification(); event("activation_verified")
    }
    private fun verifyPipeline(producer: CameraOutputManager) {
        fun requireActualRoute(): JSONObject {
            val route = PlatformAccess.cameraRoute(this)
            check(route.optBoolean("registered") && route.optBoolean("routeInstalled") &&
                route.optString("physicalId") == settings.cameraId && route.optString("syntheticId") == virtualId && route.optString("resolvedId") == virtualId) { "CameraService did not resolve camera ${settings.cameraId} to $virtualId" }
            return route
        }
        requireActualRoute()
        val before = producer.frames
        val captured = CameraFrameProbe(this).capture(settings.cameraId)
        check(producer.failure == null && producer.frames > before && producer.deliveredRevision == producer.sourceRevision) { "Camera output arrived without delivery from the selected native source" }
        val route = requireActualRoute()
        proof = captured.metrics().put("producerFrames", producer.frames - before).put("sourceRevision", producer.sourceRevision)
            .put("route", route).put("verifiedAtElapsedMs", SystemClock.elapsedRealtime())
        event("yuv_jpeg_pipeline_verified")
    }
    private fun renewLease() = synchronized(routeLock) {
        if (!routing) return@synchronized
        val producer = requireNotNull(engine)
        check(producer.failure == null && SystemClock.elapsedRealtime() - producer.lastTickMs < 4000) { "Native frame scheduler stopped responding" }
        PlatformAccess.set("sys.vcam.route", "${settings.cameraId},$virtualId,${SystemClock.elapsedRealtime() + 5000}")
    }
    private fun clearRoute() = synchronized(routeLock) {
        val installed = routing || PlatformAccess.get("ro.virtualcamera.pro.version") == "2"
        routing = false
        if (installed) PlatformAccess.set("sys.vcam.route", "")
    }
    private fun routeMatches(): Boolean = runCatching {
        val parts = PlatformAccess.get("sys.vcam.route").split(',')
        parts.size == 3 && parts[0] == settings.cameraId && parts[1] == virtualId && (parts[2].toLongOrNull() ?: 0) > SystemClock.elapsedRealtime()
    }.getOrDefault(false)
    private fun stopCamera() {
        generation++
        var failure: Throwable? = null
        try { clearRoute() } catch (e: Throwable) { failure = e }
        if (wakeLock.isHeld) wakeLock.release()
        val hal = cameraService; val token = cameraToken; val producer = engine; val death = halDeath
        cameraService = null; cameraToken = null; engine = null; virtualId = ""; halDeath = null
        try { if (hal != null && token != null && hal.asBinder().isBinderAlive) hal.unregisterCamera(token) }
        catch (e: Throwable) { if (failure == null) failure = e }
        finally {
            if (hal != null && death != null) runCatching { hal.asBinder().unlinkToDeath(death, 0) }
            try { producer?.close() } catch (e: Throwable) { if (failure == null) failure = e }
        }
        failure?.let { throw IllegalStateException("Camera cleanup failed: ${it.message}", it) }
    }
    private fun fail(error: Throwable) {
        val root = error.cause ?: error
        val detail = error as? IntegrationFailure ?: IntegrationFailure("PIPELINE_FAILURE", stage, root.message ?: "Camera Engine Error", "Check VirtualCameraBroker / VirtualCameraProbe logs, the target camera availability, GPU extensions, and the reference system installation.")
        lastError = detail; phase = "error"; Log.e(TAG, "event=failed code=${detail.code} stage=$stage", error)
        runCatching { stopCamera() }; stopForeground(STOP_FOREGROUND_REMOVE)
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            val epoch = generation
            if (routing) try { renewLease() } catch (e: Exception) { reportEngineFailure(epoch, e) }
            main.postDelayed(this, 1000)
        }
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("virtual-camera", "Virtual camera", NotificationManager.IMPORTANCE_LOW))
        control.execute {
            try {
                if (PlatformAccess.get("ro.virtualcamera.pro.version") == "2") clearRoute()
                else {
                    phase = "integration_required"; stage = "prerequisites"
                    lastError = IntegrationFailure("SYSTEM_IMAGE_MISSING", "reference system image",
                        "The broker is running, but this OS does not contain the protocol 2 camera integration",
                        "Boot the supplied reference image with the CameraService and virtual camera HAL changes.")
                }
                source = prefs.getString("file", null)?.let { File(filesDir, it) }?.takeIf { it.isFile }
                mediaType = prefs.getString("type", "image")!!
                prefs.getString("settings", null)?.let { settings = configuration(it) }
                filesDir.listFiles()?.filter { it.name.startsWith("source-") && it != source }?.forEach { it.delete() }
                event("service_initialized")
            } catch (e: Exception) { fail(e) }
        }
        main.post(heartbeat)
    }
    private fun showNotification() {
        val action = PendingIntent.getService(this, 0, Intent(this, BrokerService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(this, "virtual-camera").setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(when (phase) { "active" -> "Virtual camera active"; "activating" -> "Activating virtual camera"; else -> "Virtual camera service ready" })
            .setContentText(when (phase) { "active" -> "Camera ${settings.cameraId} uses your selected media"; "activating" -> "Validating the native camera pipeline"; else -> "Waiting for a supported camera activation" })
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Deactivate", action).build()).build()
        startForeground(7712, notification)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") control.execute { try { stopCamera(); phase = "stopped"; stage = "idle"; lastError = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } catch (e: Exception) { fail(e) } }
        else showNotification()
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onDestroy() {
        main.removeCallbacks(heartbeat)
        control.execute { runCatching { stopCamera() }; cameraCallbacks.shutdown() }
        control.shutdown(); super.onDestroy()
    }
    companion object { private const val TAG = "VirtualCameraBroker" }
}
