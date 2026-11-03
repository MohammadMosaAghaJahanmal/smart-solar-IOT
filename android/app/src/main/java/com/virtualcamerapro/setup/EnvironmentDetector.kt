package com.virtualcamerapro.setup

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.system.Os
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Ordinary-app discovery. This class never invokes su or changes guest/host state. */
class EnvironmentDetector(private val context: Context) {
    fun inspect(): JSONObject {
        val properties = readProperties(listOf("ro.hardware", "ro.product.cpu.abi", "ro.build.id",
            "ro.build.version.sdk", "ro.build.version.release", "ro.build.display.id", "ro.vmos.version",
            "ro.vmos.rom.version", "ro.vmos.rom.id", "vmos.camera.enable", "ro.build.selinux",
            "init.svc.vendor.camera-provider-2-4"))
        val discovery = cameraCapabilities()
        val cameras = discovery.cameras
        val permissions = permissions()
        // Package visibility and virtualization can both hide the physical host.
        // A guest PackageManager result must not be presented as the host version.
        val host = JSONObject().put("known", false).put("androidVersion", JSONObject.NULL)
            .put("apiLevel", JSONObject.NULL).put("vmosVersion", JSONObject.NULL)
            .put("reason", "The guest cannot authenticate physical-host Android or VMOS package information")
        val kernelEnforce = runCatching {
            val enforce = File("/sys/fs/selinux/enforce")
            when { enforce.isFile -> if (enforce.readText().trim() == "1") "enforcing" else "permissive"
                !File("/sys/fs/selinux").exists() -> "unavailable"
                else -> "unknown" }
        }.getOrDefault("unknown")
        val selinux = runCatching {
            val process = ProcessBuilder("/system/bin/getenforce").redirectErrorStream(true).start()
            if (!process.waitFor(600, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); "unknown" }
            else process.inputStream.bufferedReader().use { it.readText().trim().lowercase().take(32) }
        }.getOrDefault("unknown")
        return JSONObject().put("inspectedAt", System.currentTimeMillis())
            .put("guest", JSONObject().put("sdk", Build.VERSION.SDK_INT).put("androidVersion", Build.VERSION.RELEASE)
                .put("hardware", Build.HARDWARE).put("buildId", Build.ID).put("display", Build.DISPLAY)
                .put("fingerprint", Build.FINGERPRINT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("abis32", JSONArray(Build.SUPPORTED_32_BIT_ABIS.toList()))
                .put("abis64", JSONArray(Build.SUPPORTED_64_BIT_ABIS.toList()))
                .put("properties", properties).put("selinux", selinux).put("kernelSelinuxSignal", kernelEnforce)
                .put("selinuxSource", "guest_getenforce; kernel signal may describe the physical-host security boundary"))
            .put("host", host).put("permissions", permissions).put("cameras", cameras)
            .put("cameraService", discovery.service)
            .put("executionEnvironment", executionEnvironment())
            .put("cameraCount", cameras.length()).put("rootCandidates", JSONArray(rootCandidates()))
            .put("providerInterfaces", JSONArray(providerInterfaces()))
            .put("yuvLayout", JSONObject().put("state", "requires_frame_verification")
                .put("detail", "Row and pixel strides are measured on actual Image planes during verification"))
    }

    fun permissions(): JSONObject {
        fun granted(name: String) = context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED
        val superuserDeclared = runCatching { context.packageManager.getPermissionInfo(SUPERUSER_PERMISSION, 0) }.isSuccess
        val cameraGranted = granted(Manifest.permission.CAMERA)
        val appOp = cameraAppOp()
        return JSONObject().put("camera", cameraGranted)
            .put("cameraAppOp", appOp)
            .put("cameraAccessAllowed",cameraGranted && appOp.optInt("mode",AppOpsManager.MODE_DEFAULT) !in
                setOf(AppOpsManager.MODE_IGNORED,AppOpsManager.MODE_ERRORED))
            .put("overlay", runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false))
            .put("superuserPermissionDeclared", superuserDeclared)
            .put("superuserPermission", !superuserDeclared || granted(SUPERUSER_PERMISSION))
            .put("notifications", Build.VERSION.SDK_INT < 33 || granted("android.permission.POST_NOTIFICATIONS"))
            .put("batteryOptimizationExempt", runCatching {
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
                    ?: false
            }.getOrDefault(false))
            .put("hostCameraPermission", "not_observable_from_guest; verify VMOS host camera access on the physical host")
            .put("cameraPrivacyState", "not_exposed_by_public_query_api; actual frame verification is required")
            .put("mediaAccess", "system_document_picker_and_private_copy")
    }

    private data class CameraDiscovery(val cameras: JSONArray, val service: JSONObject)

    private fun cameraCapabilities(): CameraDiscovery {
        val result = JSONArray()
        val service = JSONObject().put("enumerated", false).put("openVerification", "not_performed_by_metadata_inspection")
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return CameraDiscovery(result, service.put("errorCode", "CAMERA_SERVICE_UNAVAILABLE")
                .put("message", "Android did not expose a CameraManager service"))
        val ids = try { manager.cameraIdList } catch (error: Exception) {
            return CameraDiscovery(result, service.put("errorCode", "CAMERA_ENUMERATION_FAILED")
                .put("exception", error.javaClass.simpleName).put("message", error.message?.take(512) ?: "Camera enumeration failed"))
        }
        service.put("enumerated", true).put("reportedCameraCount", ids.size)
        for (id in ids) {
            val item = JSONObject().put("id", id)
            try {
                val info = manager.getCameraCharacteristics(id)
                val map = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val formats = JSONArray()
                map?.outputFormats?.forEach { format ->
                    formats.put(JSONObject().put("format", format).put("name", when (format) {
                        ImageFormat.YUV_420_888 -> "YUV_420_888"; ImageFormat.JPEG -> "JPEG"; else -> format.toString()
                    }).put("sizes", sizes(map.getOutputSizes(format))))
                }
                val active = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                val fps = JSONArray()
                info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.forEach {
                    fps.put(JSONObject().put("min", it.lower).put("max", it.upper))
                }
                item.put("facing", when (info.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }).put("sensorOrientation", info.get(CameraCharacteristics.SENSOR_ORIENTATION))
                    .put("hardwareLevel", info.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                    .put("formats", formats).put("yuvSizes", sizes(map?.getOutputSizes(ImageFormat.YUV_420_888)))
                    .put("jpegSizes", sizes(map?.getOutputSizes(ImageFormat.JPEG)))
                    .put("previewSizes", sizes(map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)))
                    .put("maxDigitalZoom", info.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
                    .put("croppingType", info.get(CameraCharacteristics.SCALER_CROPPING_TYPE))
                    .put("activeArray", active?.let { JSONObject().put("left", it.left).put("top", it.top)
                        .put("width", it.width()).put("height", it.height()) })
                    .put("fpsRanges", fps)
                    .put("capabilities", JSONArray(info.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList() ?: emptyList<Int>()))
                    .put("requestKeys", JSONArray(info.availableCaptureRequestKeys?.map { it.name } ?: emptyList<String>()))
                    .put("metadataAccessible", true)
                    .put("openVerification", "requires_actual_frame_probe")
                    .put("accessible", true)
            } catch (error: Exception) {
                item.put("accessible", false).put("metadataAccessible", false)
                    .put("error", error.javaClass.simpleName).put("errorMessage", error.message?.take(512) ?: "Camera metadata is unavailable")
            }
            result.put(item)
        }
        service.put("metadataAccessibleCount", (0 until result.length()).count { result.getJSONObject(it).optBoolean("metadataAccessible") })
        return CameraDiscovery(result, service)
    }

    @Suppress("DEPRECATION")
    private fun cameraAppOp(): JSONObject = runCatching {
        val manager = context.getSystemService(AppOpsManager::class.java)
            ?: return@runCatching JSONObject().put("known", false).put("reason", "AppOps service unavailable")
        // Read-only: noteOp/startOp would incorrectly register camera use during health checks.
        val mode = if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 36)
            manager.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_CAMERA, Process.myUid(), context.packageName)
        else manager.checkOpNoThrow(AppOpsManager.OPSTR_CAMERA, Process.myUid(), context.packageName)
        JSONObject().put("known", true).put("mode", mode).put("modeName", when (mode) {
            AppOpsManager.MODE_ALLOWED -> "allowed"
            AppOpsManager.MODE_IGNORED -> "ignored"
            AppOpsManager.MODE_ERRORED -> "errored"
            AppOpsManager.MODE_DEFAULT -> "default"
            AppOpsManager.MODE_FOREGROUND -> "foreground"
            else -> "unknown"
        }).put("isCameraOpenProof", false)
            .put("meaning", "Read-only diagnostic signal; runtime permission, foreground state and actual camera open still determine access")
    }.getOrElse { JSONObject().put("known", false).put("exception", it.javaClass.simpleName) }

    private fun executionEnvironment(): JSONObject {
        val kernel = runCatching { Os.uname() }.getOrNull()
        val security = JSONObject()
        runCatching { File("/proc/self/status").useLines { lines ->
            lines.forEach { line ->
                val key = line.substringBefore(':')
                if (key in setOf("Seccomp", "NoNewPrivs", "CapEff")) security.put(key, line.substringAfter(':').trim().take(64))
            }
        } }
        return JSONObject().put("process64Bit", Process.is64Bit())
            .put("processArchitecture", System.getProperty("os.arch") ?: "unknown")
            .put("kernelRelease", kernel?.release ?: JSONObject.NULL)
            .put("kernelMachine", kernel?.machine ?: JSONObject.NULL)
            .put("kernelIdentityScope", "shared_kernel_signal; does_not_authenticate_physical_host_android_version")
            .put("processSecurity", security)
            .put("selinuxProcessContext", runCatching {
                File("/proc/self/attr/current").readText().trim().take(256)
            }.getOrDefault("unknown"))
            .put("privateStorageFreeBytes", context.filesDir.usableSpace)
    }

    private fun sizes(values: Array<Size>?) = JSONArray().apply {
        values?.distinctBy { it.width to it.height }?.sortedBy { it.width.toLong() * it.height }?.forEach {
            put(JSONObject().put("width", it.width).put("height", it.height))
        }
    }

    private fun rootCandidates(): List<String> {
        val paths = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.startsWith("/") && !it.contains("\n") }.map { "$it/su" } +
            listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su")
        return paths.distinct().filter { runCatching { File(it).isFile && File(it).canExecute() }.getOrDefault(false) }
    }

    private fun providerInterfaces(): List<String> = listOf("/vendor/bin/hw", "/system/bin/hw").flatMap { directory ->
        runCatching { File(directory).listFiles()?.filter { it.name.startsWith("android.hardware.camera.provider@") }
            ?.map { it.name } ?: emptyList() }.getOrDefault(emptyList())
    }.distinct()

    private fun readProperties(names: List<String>): JSONObject {
        val result = JSONObject()
        // These are fixed, read-only arguments, never shell-interpolated input.
        for (name in names) {
            val value = runCatching {
                val type = Class.forName("android.os.SystemProperties")
                type.getMethod("get", String::class.java).invoke(null, name) as String
            }.getOrNull() ?: runCatching {
                val process = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
                if (!process.waitFor(600, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); "" }
                else process.inputStream.bufferedReader().use { it.readText().trim().take(1024) }
            }.getOrDefault("")
            if (value.isNotEmpty()) result.put(name, value)
        }
        return result
    }

    companion object {
        const val SUPERUSER_PERMISSION = "android.permission.ACCESS_SUPERUSER"
        internal fun digest(file: File): String? = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }.getOrNull()
    }
}
