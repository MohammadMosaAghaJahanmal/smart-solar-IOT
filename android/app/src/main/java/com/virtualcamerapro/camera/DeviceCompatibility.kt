package com.virtualcamerapro.camera

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Describes the Android environment visible to the controller, not the physical
 * host when the controller is installed inside a VM.
 * A matching Android API level must never be treated as delivery evidence.
 */
data class DeviceCompatibility(
    val sdk: Int,
    val release: String,
    val manufacturer: String,
    val model: String,
    val abis: List<String>
) {
    val controllerInstallCompatible get() = sdk >= CONTROLLER_MIN_SDK && abis.any { it in CONTROLLER_ABIS }

    fun diagnostics() = JSONObject()
        .put("androidSdk", sdk)
        .put("androidRelease", release)
        .put("manufacturer", manufacturer)
        .put("model", model)
        .put("supportedAbis", JSONArray(abis))
        .put("controllerMinSdk", CONTROLLER_MIN_SDK)
        .put("controllerAbis", JSONArray(CONTROLLER_ABIS))
        .put("controllerInstallCompatible", controllerInstallCompatible)
        .put("deviceIdentityScope", "current_android_environment; a guest may mask the physical host")
        .put("referenceBackendSdk", REFERENCE_BACKEND_SDK)
        .put("referenceBackendApiMatches", sdk == REFERENCE_BACKEND_SDK)

    /** Static evidence scopes; only the broker's live support/proof can mark a route ready. */
    fun backendSupport() = JSONArray()
        .put(JSONObject()
            .put("backend", "integrated_vmos")
            .put("verification", "verified_inside_one_configured_vmos_guest")
            .put("androidSdks", JSONArray(listOf(28)))
            .put("architectures", JSONArray(listOf("arm64-v8a")))
            .put("verifiedPhysicalHostAndroidSdks", JSONArray(listOf(29)))
            .put("physicalDeviceVerified", true)
            .put("otherPhysicalHostsVerified", false)
            .put("directStockCameraReplacement", false)
            .put("requiresMeasuredGuestProfile", true)
            .put("requiresLiveFrameVerification", true))
        .put(JSONObject()
            .put("backend", "android_emulator_environment")
            .put("verification", "verified_on_configured_pc_emulator")
            .put("androidSdks", JSONArray(listOf(36)))
            .put("architectures", JSONArray(listOf("x86_64")))
            .put("physicalDeviceVerified", false))
        .put(JSONObject()
            .put("backend", "aosp_camera_service")
            .put("verification", "source_integration_not_device_verified")
            .put("androidSdks", JSONArray(listOf(36)))
            .put("physicalDeviceVerified", false)
            .put("perFirmwarePortRequired", true))

    fun missingBroker(): JSONObject {
        val deviceName = listOf(manufacturer, model).filter { it.isNotBlank() }
            .joinToString(" ").ifBlank { "This device" }
        val androidName = "Android $release (API $sdk)"
        val recovery = "Inside VMOS, install this same APK in the guest and use its setup assistant to inspect the actual guest profile, " +
            "permissions, root access and camera transport. The integrated guest service does not need a second broker APK. " +
            "On a stock physical Android OS, system-wide replacement needs a camera-provider and CameraService integration built for its firmware; " +
            "installing an APK alone cannot add that routing. Android version compatibility alone does not establish a working camera bridge."
        return JSONObject()
            .put("supported", false)
            .put("protocol", 2)
            .put("code", "BROKER_MISSING")
            .put("component", "platform broker")
            .put("message", "$deviceName uses $androidName; no compatible system camera broker is installed.")
            .put("recovery", recovery)
            .put("androidSdk", sdk)
            .put("architecture", abis.firstOrNull() ?: "unknown")
            .put("device", diagnostics())
            .put("backendSupport", backendSupport())
    }

    companion object {
        const val CONTROLLER_MIN_SDK = 28
        const val REFERENCE_BACKEND_SDK = 36
        val CONTROLLER_ABIS = listOf("arm64-v8a", "x86_64")

        fun current() = DeviceCompatibility(
            Build.VERSION.SDK_INT, Build.VERSION.RELEASE,
            Build.MANUFACTURER, Build.MODEL, Build.SUPPORTED_ABIS.toList()
        )
    }
}
