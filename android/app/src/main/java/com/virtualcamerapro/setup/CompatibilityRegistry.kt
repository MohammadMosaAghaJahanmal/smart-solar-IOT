package com.virtualcamerapro.setup

import android.content.Context
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Exact artifacts stay in versioned guest profiles; physical phone models never select a patch.
 * JPEG preparation is checked by the provisioner against the device-verified importer repair and
 * original encoder compatibility symlink. The green-frame repair belongs to the transport HAL;
 * a theoretical color-range conversion is not accepted as evidence of a working JPEG encoder.
 */
class CompatibilityRegistry(private val context: Context) {
    data class Match(val profile: JSONObject?, val supported: Boolean, val reason: String,
        val components: JSONObject, val identity: String, val repairConsentIdentity: String)

    fun resolve(environment: JSONObject): Match {
        val guest = environment.getJSONObject("guest")
        val components = JSONObject()
        val profile = JSONObject().put("id", GuestAdapterRegistry.PROFILE_ID)
            .put("name", "VMOS ARM64 camera3 guest").put("version", GuestAdapterRegistry.PROFILE_VERSION)
            .put("validatedGuestSdk", 28).put("validation", "Measured guest binaries; other physical hosts require validation")
        val wrapper = runCatching { context.assets.open("camera.vphw90.crop-wrapper.so.sha256")
            .bufferedReader().use { it.readText().trim() } }.getOrDefault("")
        val hal = File("/vendor/lib64/hw/camera.vphw90.so")
        val currentHash = if (hal.isFile) EnvironmentDetector.digest(hal) else null
        components.put("cameraHal", JSONObject().put("present", hal.isFile)
            .put("sha256", currentHash ?: JSONObject.NULL)
            .put("recognized", currentHash in (GuestAdapterRegistry.knownHalHashes + wrapper).filter { it.length == 64 }))
        val abis = guest.getJSONArray("abis")
        val arm64 = (0 until abis.length()).any { abis.getString(it) == "arm64-v8a" }
        val shape = GuestAdapterRegistry.profileMatches()
        val hashMatches = currentHash != null && currentHash in (GuestAdapterRegistry.knownHalHashes + wrapper).filter { it.length == 64 }
        val provider = File(GuestAdapterRegistry.PROVIDER)
        val providerHash = if (shape && provider.isFile) EnvironmentDetector.digest(provider) else null
        components.put("cameraProvider", JSONObject().put("present", provider.isFile)
            .put("sha256", providerHash ?: JSONObject.NULL).put("recognized", providerHash == GuestAdapterRegistry.PROVIDER_SHA))
        val supported = shape && hashMatches && providerHash == GuestAdapterRegistry.PROVIDER_SHA
        val reason = when {
            !arm64 -> "No bundled camera adapter has been validated for the guest CPU architecture"
            !shape -> "No validated camera-adapter profile matches this guest framework and build"
            !hashMatches -> "The guest camera HAL differs from the preserved, measured adapter profile"
            providerHash != GuestAdapterRegistry.PROVIDER_SHA -> "The guest camera provider does not match this adapter's audited restart target"
            else -> "The guest framework and camera binaries match a validated adapter profile"
        }
        val proxy = runCatching { context.assets.open("vmos-camera-proxy.sha256")
            .bufferedReader().use { it.readText().trim() } }.getOrDefault("")
        val cameraTopology = environment.optJSONArray("cameras")?.let { cameras ->
            (0 until cameras.length()).map { cameras.getJSONObject(it) }.sortedBy { it.optString("id") }
                .joinToString(";") { camera ->
                    // The proof includes PRIVATE-preview to still transitions and standard
                    // crop behavior, so changed metadata must invalidate it as well.
                    listOf("id", "facing", "sensorOrientation", "yuvSizes", "jpegSizes", "previewSizes",
                        "activeArray", "maxDigitalZoom", "croppingType", "fpsRanges", "capabilities", "hardwareLevel", "requestKeys", "accessible")
                        .joinToString(":") { key -> camera.opt(key)?.toString() ?: "null" }
                }
        } ?: ""
        val identity = listOf(guest.optString("fingerprint"), guest.optString("hardware"), guest.optInt("sdk"),
            if (supported) profile.optString("id") else "unmatched", profile.optInt("version"), wrapper, proxy, cameraTopology,
            environment.optJSONObject("executionEnvironment")?.let { listOf(it.optString("kernelRelease"), it.optString("kernelMachine"),
                it.optBoolean("process64Bit"),it.optJSONObject("processSecurity")?.toString()).joinToString(":") } ?: "").joinToString("|")
        // Approval names the fixed privileged guest repair plan, rather than a
        // playback topology or a private worker version. Changing system bytes
        // or the privileged plan requires renewed consent; ordinary health
        // checks and private worker updates do not.
        val consent = listOf("guest-camera-repair-plan-1",guest.optString("fingerprint"),guest.optString("hardware"),guest.optInt("sdk"),
            profile.optString("id"),profile.optInt("version"),GuestAdapterRegistry.FACTORY_HAL,GuestAdapterRegistry.REPAIRED_TRANSPORT,
            GuestAdapterRegistry.IMPORTER_ORIGINAL,GuestAdapterRegistry.IMPORTER_REPAIRED,GuestAdapterRegistry.JPEG_SHA,
            GuestAdapterRegistry.PROVIDER_SHA,wrapper).joinToString("|")
        return Match(if (shape) profile else null, supported, reason, components, identity,consent)
    }

    fun profiles(): JSONArray = JSONArray().put(JSONObject().put("id", GuestAdapterRegistry.PROFILE_ID)
        .put("version", GuestAdapterRegistry.PROFILE_VERSION).put("guestSdk", 28).put("abi", "arm64-v8a")
        .put("requiresExactBinaryHashes", true).put("hostVersionsUniversallyValidated", false))

}
