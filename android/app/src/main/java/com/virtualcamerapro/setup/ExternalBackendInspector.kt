package com.virtualcamerapro.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.virtualcamerapro.camera.VirtualCameraController
import org.json.JSONObject

/** Keeps the previously configured emulator/AOSP control path available without VMOS setup. */
class ExternalBackendInspector(private val context: Context) {
    data class Inspection(val ready: Boolean, val support: JSONObject, val status: JSONObject, val identity: JSONObject)

    /** Binding is read-only. A support response is setup readiness, never selected-media Active. */
    fun inspect(): Inspection? {
        val manager = context.packageManager
        val installed = try { manager.getPackageInfo(PACKAGE, 0) }
        catch (_: PackageManager.NameNotFoundException) { return null }
        val component = ComponentName(PACKAGE, "com.virtualcamerapro.broker.BrokerService")
        val service = manager.resolveService(Intent().setComponent(component), 0)?.serviceInfo
        val sameController = manager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH
        val samePlatform = manager.checkSignatures("android", PACKAGE) == PackageManager.SIGNATURE_MATCH
        val controlGranted = context.checkSelfPermission(CONTROL) == PackageManager.PERMISSION_GRANTED
        val identity = JSONObject().put("package", PACKAGE).put("version", installed.versionName ?: "unknown")
            .put("controllerSignatureMatches", sameController).put("platformSignatureMatches", samePlatform)
            .put("controlPermissionGranted", controlGranted).put("serviceVisible", service != null)
        if ((!sameController && !samePlatform) || !controlGranted || service == null || !service.exported || service.permission != CONTROL) {
            return Inspection(false, JSONObject().put("supported", false).put("protocol", 2)
                .put("code", "EXTERNAL_BROKER_AUTHORIZATION_REQUIRED")
                .put("message", "The existing camera backend is not available through a trusted, authorized controller connection")
                .put("recovery", "Use the matching signed controller and the existing emulator or AOSP backend configuration. Guest root and VMOS camera preparation do not apply to this backend."),
                JSONObject(), identity)
        }
        return VirtualCameraController(context).use { controller ->
            val support = controller.support()
            val responseMatches = support.optInt("protocol") == 2 && support.optString("brokerPackage") == PACKAGE
            val ready = support.optBoolean("supported") && responseMatches
            val observed = if (ready) controller.status() else JSONObject()
            // Controller status also contains selected-media preferences. Do not collect those
            // into environment diagnostics or turn their persisted state into setup evidence.
            val health = JSONObject()
            for (key in listOf("phase", "stage", "code", "message", "backend", "frames", "streams"))
                if (observed.has(key)) health.put(key, observed.get(key))
            if (support.optBoolean("supported") && !responseMatches) {
                support.put("supported", false).put("code", "EXTERNAL_BROKER_IDENTITY_MISMATCH")
                    .put("message", "The existing backend did not return the expected protocol and package identity")
                    .put("recovery", "Recheck the matching external backend and controller installation")
            }
            Inspection(ready, support, health, identity)
        }
    }

    companion object {
        const val PACKAGE = "com.virtualcamerapro.broker"
        const val CONTROL = "com.virtualcamerapro.permission.CONTROL"
    }
}
