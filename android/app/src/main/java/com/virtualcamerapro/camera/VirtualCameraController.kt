package com.virtualcamerapro.camera

import android.content.*
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.engine.CameraOutputManager
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraInventory
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

class VirtualCameraController(private val context: Context) : AutoCloseable {
    val state = CameraStateManager(context)
    @Volatile private var broker: IVirtualCameraBroker? = null
    private var bound = false
    private var waiting: CountDownLatch? = null
    private val intent get() = Intent().setComponent(ComponentName("com.virtualcamerapro.broker","com.virtualcamerapro.broker.BrokerService"))
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { broker = IVirtualCameraBroker.Stub.asInterface(service); waiting?.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { broker = null }
        override fun onNullBinding(name: ComponentName) { waiting?.countDown() }
        override fun onBindingDied(name: ComponentName) { broker = null; waiting?.countDown(); if (bound) { context.unbindService(this); bound = false } }
    }
    private fun connect(): IVirtualCameraBroker? {
        broker?.let { if (it.asBinder().isBinderAlive) return it }
        if (context.packageManager.resolveService(intent,0) == null) return null
        if (!bound) {
            waiting = CountDownLatch(1)
            bound = context.bindService(intent,connection,Context.BIND_AUTO_CREATE)
        }
        if (bound) waiting?.await(4,TimeUnit.SECONDS)
        return broker
    }
    private fun unavailable(code: String, component: String, message: String, recovery: String) = JSONObject()
        .put("supported",false).put("protocol",2).put("code",code).put("component",component)
        .put("message",message).put("recovery",recovery).put("androidSdk",Build.VERSION.SDK_INT)
        .put("architecture",Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("device",DeviceCompatibility.current().diagnostics())
        .put("backendSupport",DeviceCompatibility.current().backendSupport())
    fun support(): JSONObject = try {
        if (context.packageManager.resolveService(intent,0) == null) {
            DeviceCompatibility.current().missingBroker()
        } else if (context.checkSelfPermission("com.virtualcamerapro.permission.CONTROL") != PackageManager.PERMISSION_GRANTED) {
            unavailable("CONTROLLER_CERTIFICATE_REJECTED","controller signing permission","Android has not granted access to the privileged broker",
                "Rebuild the reference image with this controller APK's SHA-256 signing certificate, then reinstall the matching APK.")
        } else {
            val service = connect()
            if (service == null) unavailable("BROKER_BIND_FAILED","platform broker Binder","The installed broker did not return a Binder connection",
                "Check VirtualCameraBroker logcat and restart the platform broker or reboot the reference image.")
            else JSONObject(service.support).also {
                if (it.optInt("protocol") != 2) throw IllegalStateException("Controller and system integration protocol do not match; rebuild both at protocol 2")
                it.put("device",DeviceCompatibility.current().diagnostics())
                it.put("backendSupport",DeviceCompatibility.current().backendSupport())
            }
        }
    } catch (e: Exception) { unavailable("INTEGRATION_CHECK_FAILED","system Binder connection",e.cause?.message ?: e.message ?: "System service unavailable","Collect scripts/device-diagnostics.py output and check the broker signing identity, SELinux policy and installed system version.") }
    fun cameras(): String = CameraInventory.query(context).toString()
    fun status(): JSONObject {
        val s = state.settingsJson().put("uri",state.uri).put("type",state.type).put("name",state.name)
        val native = try { connect()?.let { JSONObject(it.status) } } catch (_: Exception) { null }
        val available = support()
        val nativePhase = native?.optString("phase")
        s.put("phase",if (!available.optBoolean("supported") && nativePhase !in listOf("active","activating","error","stopped")) "integration_required" else nativePhase ?: "integration_required")
        s.put("message",native?.optString("message") ?: "System Integration Required")
        for (key in listOf("virtualId","frames","streams","stage","code","recovery","proof","backend")) if (native?.has(key) == true) s.put(key,native.get(key))
        if (native == null && !available.optBoolean("supported")) {
            for (key in listOf("message","code","recovery")) if (available.has(key)) s.put(key,available.get(key))
        }
        return s
    }
    fun activate(): Boolean {
        check(state.uri.isNotEmpty()) { "Select an image or video first" }
        val readiness = support()
        check(readiness.getBoolean("supported")) { "${readiness.optString("code")}: ${readiness.optString("message")}. ${readiness.optString("recovery")}" }
        val service = requireNotNull(connect()) { "System Integration Required: install the custom AOSP image" }
        Log.i("VirtualCameraController","event=activate cameraId=${state.settings.cameraId}")
        context.startForegroundService(intent)
        descriptor().use { check(service.activate(it,state.type,state.settingsJson().toString())) { "System camera activation failed" } }
        val native = JSONObject(service.status)
        val proof = native.optJSONObject("proof")
        val delivered = proof != null && when (proof.optString("backend")) {
            "android_emulator_environment" -> {
                readiness.optString("backend") == "android_emulator_environment" &&
                    proof.optBoolean("sourceVerified") && proof.optBoolean("rendererConfigured") &&
                    proof.optString("hostGeneration").isNotBlank() && proof.optString("sourceSha256") == selectedSourceHash()
            }
            "vmos_camera_proxy" -> {
                readiness.optString("backend") == "vmos_camera_proxy" &&
                    proof.optBoolean("sourceVerified") && proof.optBoolean("rendererConfigured") &&
                    proof.optString("generation").matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) &&
                    proof.optString("sourceSha256") == selectedSourceHash() &&
                    proof.optLong("producerFrames") > 0 && proof.optLong("proxyFrames") >= 3 && proof.optInt("proxyPid") > 1 &&
                    proof.optString("cameraId") == state.settings.cameraId &&
                    proof.optInt("previewWidth") == state.settings.width && proof.optInt("previewHeight") == state.settings.height &&
                    proof.optInt("jpegWidth") == state.settings.width && proof.optInt("jpegHeight") == state.settings.height &&
                    proof.optLong("firstTimestampNs") > 0 && proof.optLong("lastTimestampNs") > proof.optLong("firstTimestampNs") &&
                    proof.optDouble("bestMeanAbsoluteError") in 0.0..25.0
            }
            else -> proof.optLong("producerFrames") > 0
        }
        check(native.optString("phase") == "active" && proof != null && proof.optInt("previewFrames") >= 3 && proof.optInt("jpegBytes") > 0 && delivered) { "System camera returned no verified frame-delivery evidence" }
        Log.i("VirtualCameraController","event=activation_verified cameraId=${state.settings.cameraId}")
        return true
    }
    fun deactivate() { connect()?.deactivate() }
    private fun selectedSourceHash(): String {
        val hash = MessageDigest.getInstance("SHA-256")
        File(Uri.parse(state.uri).path!!).inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; hash.update(buffer, 0, count) }
        }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun descriptor(): ParcelFileDescriptor = ParcelFileDescriptor.open(File(Uri.parse(state.uri).path!!),ParcelFileDescriptor.MODE_READ_ONLY)
    fun select(uri: String, type: String) {
        require(type in listOf("image","video")) { "Unsupported media type" }
        val source = Uri.parse(uri)
        val resolver = context.contentResolver
        val actualType = resolver.getType(source)
        require(actualType == null || actualType.startsWith("$type/")) { "Selected media has the wrong type" }
        val dir = File(context.filesDir,"sources").apply { mkdirs() }
        val file = File.createTempFile("source-",if(type == "image") ".image" else ".video",dir)
        try {
            resolver.openInputStream(source).use { input -> requireNotNull(input) { "Cannot read selected media" }; file.outputStream().use { input.copyTo(it) } }
            check(file.length() > 0) { "Selected media is empty" }
            var decodeError: Throwable? = null
            CameraOutputManager { decodeError = it }.use { validator ->
                validator.start(file,type,state.settings); validator.awaitReady()
                check(decodeError == null) { decodeError?.message ?: "Media decode failed" }
            }
            var displayName = source.lastPathSegment ?: "Selected media"
            resolver.query(source,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) displayName = it.getString(0) }
            val service = connect()
            if (service != null && JSONObject(service.status).optString("phase") == "active") {
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { service.updateSource(it,type) }
            }
            val old = state.uri
            state.uri = Uri.fromFile(file).toString(); state.type = type; state.name = displayName
            if (old.isNotEmpty()) File(Uri.parse(old).path!!).takeIf { it.parentFile == dir }?.delete()
        } catch (e: Exception) { file.delete(); throw e }
    }
    fun update(settings: OutputSettings) {
        connect()?.let { it.updateSettings(state.settingsJson(settings).toString()) }
        state.settings = settings
    }
    override fun close() { if (bound) { context.unbindService(connection); bound = false }; broker = null }
}
