package com.virtualcamerapro.camera

import android.content.*
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Build
import android.content.pm.PackageManager
import android.util.Log
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.engine.OutputCapabilities
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
    @Volatile private var bound = false
    @Volatile private var waiting: CountDownLatch? = null
    private val intent get(): Intent {
        // Known guest profiles use the internal process; existing emulator/AOSP
        // installations retain their original external platform backend.
        val integrated = com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry.profileMatches()
        return Intent().setComponent(ComponentName(if (integrated) context.packageName else "com.virtualcamerapro.broker",
            "com.virtualcamerapro.broker.BrokerService"))
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { broker = IVirtualCameraBroker.Stub.asInterface(service); waiting?.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { broker = null }
        override fun onNullBinding(name: ComponentName) { waiting?.countDown() }
        override fun onBindingDied(name: ComponentName) { broker = null; waiting?.countDown(); if (bound) { context.unbindService(this); bound = false } }
    }
    @Synchronized private fun connect(): IVirtualCameraBroker? {
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
        } else if (intent.component?.packageName != context.packageName &&
            context.checkSelfPermission("com.virtualcamerapro.permission.CONTROL") != PackageManager.PERMISSION_GRANTED) {
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
        if (native != null && nativePhase == "active" && available.optBoolean("supported")) {
            synchronized(settingsLock) {
                adoptVerifiedOutput(native,available)
                val actual = state.settingsJson()
                for (key in actual.keys()) s.put(key,actual.get(key))
            }
        }
        if (available.optBoolean("supported") && available.optString("backend") == "vmos_camera_proxy" &&
            nativePhase !in setOf("active","activating")) synchronized(settingsLock) {
            val adjusted = adaptOutput(state.settings,available)
            if (adjusted != state.settings) {
                state.settings = adjusted
                val normalized = state.settingsJson(adjusted)
                for (key in normalized.keys()) s.put(key,normalized.get(key))
            }
        }
        s.put("phase",if (!available.optBoolean("supported") && nativePhase !in listOf("active","activating","error","stopped")) "integration_required" else nativePhase ?: "integration_required")
        s.put("message",native?.optString("message") ?: "System Integration Required")
        for (key in listOf("virtualId","frames","streams","stage","code","recovery","proof","backend","cameraIds","replacementMode","fallback")) if (native?.has(key) == true) s.put(key,native.get(key))
        if (native == null && !available.optBoolean("supported")) {
            for (key in listOf("message","code","recovery")) if (available.has(key)) s.put(key,available.get(key))
        }
        return s
    }
    fun activate(isCurrent: () -> Boolean = { true }): Boolean {
        if (!isCurrent()) return false
        check(state.uri.isNotEmpty()) { "Select an image or video first" }
        val readiness = support()
        check(readiness.getBoolean("supported")) { "${readiness.optString("code")}: ${readiness.optString("message")}. ${readiness.optString("recovery")}" }
        if (readiness.optString("backend") == "vmos_camera_proxy") synchronized(settingsLock) {
            val adapted = adaptOutput(state.settings,readiness)
            if (adapted != state.settings) updateLocked(adapted)
        }
        val service = requireNotNull(connect()) { "System Integration Required: install the custom AOSP image" }
        if (!isCurrent()) return false
        Log.i("VirtualCameraController","event=activate cameraId=${state.settings.cameraId}")
        context.startForegroundService(intent)
        val activated = descriptor().use {
            if (!isCurrent()) return false
            service.activate(it,state.type,state.settingsJson().toString())
        }
        // Deactivate may arrive immediately before the remote Activate call. Its
        // local cancellation intent still wins when that call returns.
        if (!isCurrent()) { service.deactivate(); return false }
        check(activated) { "System camera activation failed" }
        val native = JSONObject(service.status)
        synchronized(settingsLock) { adoptVerifiedOutput(native,readiness) }
        val proof = native.optJSONObject("proof")
        val delivered = proof != null && when (proof.optString("backend")) {
            "android_emulator_environment" -> {
                readiness.optString("backend") == "android_emulator_environment" &&
                    proof.optBoolean("sourceVerified") && proof.optBoolean("rendererConfigured") &&
                    proof.optString("hostGeneration").isNotBlank() && proof.optString("sourceSha256") == selectedSourceHash()
            }
            "vmos_camera_proxy" -> {
                val expectedIds = if (state.settings.replacementMode == "both") readiness.optJSONArray("bothCameraIds") else null
                val bothVerified = if (state.settings.replacementMode == "single") true else {
                    val cameras = proof.optJSONArray("cameras")
                    val ids = proof.optJSONArray("cameraIds")
                    expectedIds != null && cameras != null && ids != null && expectedIds.length() >= 2 &&
                        proof.optString("replacementMode") == "both" && cameras.length() == expectedIds.length() && ids.length() == expectedIds.length() &&
                        (0 until expectedIds.length()).map { expectedIds.getString(it) }.toSet() ==
                            (0 until ids.length()).map { ids.getString(it) }.toSet() &&
                        (0 until expectedIds.length()).map { expectedIds.getString(it) }.toSet() ==
                            (0 until cameras.length()).map { cameras.getJSONObject(it).optString("cameraId") }.toSet() &&
                        (0 until cameras.length()).all { index -> cameras.getJSONObject(index).let { camera ->
                            camera.optBoolean("sourceVerified") && camera.optInt("previewFrames") >= 3 && camera.optInt("jpegBytes") > 0 &&
                                camera.optLong("proxyFrames") >= 3 && camera.optLong("lastTimestampNs") > camera.optLong("firstTimestampNs") &&
                                camera.optDouble("bestMeanAbsoluteError") in 0.0..25.0 &&
                                camera.optInt("previewWidth") == state.settings.width && camera.optInt("previewHeight") == state.settings.height &&
                                camera.optInt("jpegWidth") == state.settings.width && camera.optInt("jpegHeight") == state.settings.height
                        } }
                }
                readiness.optString("backend") == "vmos_camera_proxy" &&
                    bothVerified &&
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
    /** The broker may choose a verified smaller output; UI and subsequent proof use that actual output. */
    private fun adoptVerifiedOutput(native: JSONObject,available: JSONObject) {
        if (available.optString("backend") != "vmos_camera_proxy" || native.optString("phase") != "active") return
        val actual = native.optJSONObject("actualSettings") ?: return // Older accepted guest brokers.
        val proof = native.optJSONObject("proof") ?: error("A changed output has no actual frame proof")
        val previous = state.settings
        val adjusted = previous.copy(cameraId=actual.getString("cameraId"),width=actual.getInt("width"),height=actual.getInt("height"))
        check(actual.getString("replacementMode") == previous.replacementMode) { "A fallback cannot weaken the selected replacement mode" }
        val inventory = available.getJSONArray("cameras")
        val devices = (0 until inventory.length()).map { inventory.getJSONObject(it) }
        val selected = devices.single { it.getString("id") == adjusted.cameraId }
        val prior = devices.singleOrNull { it.getString("id") == previous.cameraId }
        if (adjusted.cameraId != previous.cameraId) check(prior != null && prior.getString("facing") in listOf("front","back") &&
            prior.getString("facing") == selected.getString("facing")) { "A fallback cannot switch to a different camera facing" }
        val sizes = if (previous.replacementMode == "both") available.getJSONArray("bothResolutions") else selected.getJSONArray("resolutions")
        check((0 until sizes.length()).any { sizes.getJSONObject(it).let { size -> size.getInt("width") == adjusted.width && size.getInt("height") == adjusted.height } })
        check(proof.optBoolean("sourceVerified") && proof.optString("cameraId") == adjusted.cameraId &&
            proof.optInt("previewWidth") == adjusted.width && proof.optInt("previewHeight") == adjusted.height &&
            proof.optInt("jpegWidth") == adjusted.width && proof.optInt("jpegHeight") == adjusted.height) { "Actual fallback output does not match verified camera pixels" }
        if (adjusted != previous) state.settings = adjusted
    }
    fun deactivate() { connect()?.deactivate() }
    /** Lightweight overlay health read; does not query the camera factory. */
    fun cameraPhase(): String = runCatching { connect()?.let { JSONObject(it.status).optString("phase","unknown") } ?: "unavailable" }.getOrDefault("unavailable")
    fun clearMedia() {
        deactivate()
        val old = state.uri
        if (old.isNotBlank()) File(Uri.parse(old).path ?: "").takeIf {
            it.parentFile?.canonicalFile == File(context.filesDir, "sources").canonicalFile
        }?.delete()
        state.uri = ""; state.name = ""; state.type = "image"
    }
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
        val service = connect()
        if (service != null && JSONObject(service.status).optString("phase") != "active") service.deactivate()
        val dir = File(context.filesDir,"sources").apply { mkdirs() }
        val file = File.createTempFile("source-",if(type == "image") ".image" else ".video",dir)
        try {
            resolver.openInputStream(source).use { input ->
                requireNotNull(input) { "Cannot read selected media" }
                file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count
                        require(size <= 256L * 1024 * 1024) { "Selected media exceeds 256 MiB" }
                        output.write(buffer,0,count)
                    }
                }
            }
            check(file.length() > 0) { "Selected media is empty" }
            var decodeError: Throwable? = null
            CameraOutputManager { decodeError = it }.use { validator ->
                validator.start(file,type,state.settings); validator.awaitReady()
                check(decodeError == null) { decodeError?.message ?: "Media decode failed" }
            }
            var displayName = source.lastPathSegment ?: "Selected media"
            resolver.query(source,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) displayName = it.getString(0) }
            if (service != null && JSONObject(service.status).optString("phase") == "active") {
                ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use { service.updateSource(it,type) }
            }
            val old = state.uri
            state.uri = Uri.fromFile(file).toString(); state.type = type; state.name = displayName
            if (old.isNotEmpty()) File(Uri.parse(old).path!!).takeIf { it.parentFile == dir }?.delete()
        } catch (e: Exception) { file.delete(); throw e }
    }
    fun update(settings: OutputSettings) = synchronized(settingsLock) {
        updateLocked(settings)
    }
    private fun updateLocked(settings: OutputSettings) {
        val previous = state.settings
        val routeChanged = previous.replacementMode != settings.replacementMode ||
            previous.cameraId != settings.cameraId || previous.width != settings.width || previous.height != settings.height
        if (settings.replacementMode == "both" && routeChanged) {
            check(support().optJSONArray("replacementModes")?.let { modes ->
                (0 until modes.length()).any { modes.optString(it) == "both" }
            } == true) { "This camera backend does not support replacing both cameras" }
        }
        connect()?.let {
            if (JSONObject(it.status).optString("phase") != "active") it.deactivate()
            val adjusted = if (routeChanged && JSONObject(it.status).optString("phase") != "active") {
                val available = JSONObject(it.support)
                if (available.optBoolean("supported") && available.optString("backend") == "vmos_camera_proxy") adaptOutput(settings,available) else settings
            } else settings
            it.updateSettings(state.settingsJson(adjusted).toString())
            state.settings = adjusted
            return
        }
        state.settings = settings
    }
    private fun adaptOutput(value: OutputSettings,available: JSONObject): OutputSettings {
        fun sizes(array: org.json.JSONArray?) = if (array == null) emptyList() else (0 until array.length()).map {
            array.getJSONObject(it).let { size -> size.getInt("width") to size.getInt("height") }
        }
        val array = available.getJSONArray("cameras")
        val cameras = (0 until array.length()).map { array.getJSONObject(it).let { camera ->
            OutputCapabilities.Camera(camera.getString("id"),sizes(camera.optJSONArray("resolutions")))
        } }
        val both = available.optJSONArray("bothCameraIds")
        val ids = if (both == null) emptyList() else (0 until both.length()).map { both.getString(it) }
        return OutputCapabilities.resolve(value,cameras,ids,sizes(available.optJSONArray("bothResolutions")))
    }
    /** UI and optional overlay share a single settings transaction in this process. */
    fun updateSettings(change: (OutputSettings) -> OutputSettings) = synchronized(settingsLock) {
        updateLocked(change(state.settings))
    }
    fun applySourceTransform(zoom: Double, panX: Double, panY: Double) = updateSettings {
        it.copy(zoom=zoom,panX=panX,panY=panY)
    }
    fun setReplacementMode(mode: String) = synchronized(settingsLock) {
        require(mode in setOf("single","both")) { "Choose a single camera or both cameras" }
        val current = state.settings
        var next = current.copy(replacementMode=mode)
        if (mode == "both") {
            val available = support()
            val ids = available.optJSONArray("bothCameraIds") ?: error("Both-camera replacement is unavailable")
            check(ids.length() >= 2) { "Both-camera replacement requires detected front and back cameras" }
            val choices = available.optJSONArray("bothResolutions") ?: error("The cameras have no common output resolution")
            check(choices.length() > 0) { "The cameras have no common output resolution" }
            val resolutions = (0 until choices.length()).map { choices.getJSONObject(it) }
            val compatible = resolutions.firstOrNull { it.getInt("width") == current.width && it.getInt("height") == current.height }
                ?: resolutions.minBy { kotlin.math.abs(it.getInt("width").toLong()*it.getInt("height") - current.width.toLong()*current.height) }
            val cameraId = current.cameraId.takeIf { id -> (0 until ids.length()).any { ids.getString(it) == id } } ?: ids.getString(0)
            next = next.copy(cameraId=cameraId,width=compatible.getInt("width"),height=compatible.getInt("height"))
        }
        updateLocked(next)
    }
    override fun close() { if (bound) { context.unbindService(connection); bound = false }; broker = null }
    companion object { private val settingsLock = Any() }
}
