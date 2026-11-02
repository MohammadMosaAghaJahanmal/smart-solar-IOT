package com.virtualcamerapro.camera

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Describes the controller's host, not a successful camera integration.
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
        .put("referenceBackendSdk", REFERENCE_BACKEND_SDK)
        .put("referenceBackendApiMatches", sdk == REFERENCE_BACKEND_SDK)

    /** Static evidence scopes; only the broker's live support/proof can mark a route ready. */
    fun backendSupport() = JSONArray()
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
        val recovery = if (sdk < REFERENCE_BACKEND_SDK) {
            "The controller can run here, but the supplied system broker requires Android 16 (API 36). " +
                "$androidName needs a camera provider and CameraService integration built for this device's firmware. " +
                "Installing either APK alone cannot replace its system cameras."
        } else {
            "This device needs a platform-signed broker, a compatible camera provider and CameraService routing integration in its firmware. " +
                "The PC emulator backend only supports the configured Android emulator. " +
                "Installing a broker APK alone cannot add camera routing to a stock OS."
        }
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
