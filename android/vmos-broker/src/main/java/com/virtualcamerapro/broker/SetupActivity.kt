package com.virtualcamerapro.broker

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Setup is confined to the guest. A successful private proxy test is not activation. */
class SetupActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var report: TextView
    private val actions = mutableListOf<Button>()
    private var pendingPermissionAction: (() -> Unit)? = null
    private fun LinearLayout.action(label: String, operation: () -> Unit) {
        val button = Button(this@SetupActivity).apply { text = label; setOnClickListener { operation() } }
        actions.add(button); addView(button)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(body) })
        body.addView(TextView(this).apply { text = "VMOS guest camera service"; textSize = 22f })
        body.addView(TextView(this).apply { text = "Development backend for the inspected Android 9 VMOS guest. Setup must verify the real guest camera transport before any camera HAL replacement. The physical phone is outside this backend." })
        body.action("Check guest camera transport") { checkGuest() }
        body.action("Check guest provider access") { runCheck("Checking guest-only superuser and camera-provider identity…", requiresRoot = true) { GuestRoot(this).verifyProvider().put("code", "GUEST_PROVIDER_ACCESS_VERIFIED") } }
        body.action("Install guest camera bridge") { runCheck("Verifying the guest transport, recovery copy and provider access before guest setup…", requiresRoot = true) {
            requireCameraPermission()
            val result = BrokerService.withGuestSetup {
                val root = GuestRoot(this)
                GuestRuntime(this).install { root.restartProvider() }.put("code", "GUEST_BRIDGE_CONFIGURED")
                    .put("message", "Guest camera bridge configured. Open VirtualCameraPro, select media and activate to verify its actual pixels.")
            }
            startSupervision()
            result
        } }
        body.action("Install guest adapter for camera app zoom") { runCheck("Verifying the pinned Android 9 camera3 adapter and reversible guest upgrade…", requiresRoot = true) {
            requireCameraPermission()
            val result = BrokerService.withGuestSetup {
                val root = GuestRoot(this)
                GuestRuntime(this).installCropAdapter { root.restartProvider() }.put("code", "GUEST_CROP_ADAPTER_CONFIGURED")
                    .put("message", "The guest camera3 crop adapter opened and delivered real YUV/JPEG. Camera apps must send standard crop requests; verify zoom using a selected source in the camera app.")
            }
            startSupervision()
            result
        } }
        body.action("Restore original guest camera") { runCheck("Verifying and restoring the exact original guest camera module…", requiresRoot = true) {
            requireCameraPermission()
            BrokerService.withGuestSetup {
                val root = GuestRoot(this)
                val runtime = GuestRuntime(this)
                runtime.restore { root.restartProvider() }
                runtime.identity().put("code", "ORIGINAL_GUEST_CAMERA_RESTORED")
                    .put("message", "The exact original guest camera module was restored and its YUV/JPEG pipeline verified.")
            }
        } }
        report = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        body.addView(report)
        checkGuest()
    }
    private fun checkGuest() {
        runCheck("Checking the measured guest identity and real camera factory…") {
            BrokerService.withGuestTransportCheck { allowRestart ->
                val runtime = GuestRuntime(this)
                val checks = if (runtime.hasInstallationRecord()) {
                    val result = if (allowRestart) runtime.recoverInstalled { GuestRoot(this).restartProvider() }
                    else runtime.identity().put("installed", true).put("proxyFactoryVerified", true)
                        .put("cameraFactory", runtime.checkTransport()).put("providerRestarted", false)
                    startSupervision()
                    result
                } else runtime.preflight()
                checks.put("code", "PREFLIGHT_PASSED")
            }
        }
    }
    private fun startSupervision() {
        startForegroundService(android.content.Intent(this, BrokerService::class.java)
            .setAction(BrokerService.ACTION_RECOVER_TRANSPORT))
    }
    private fun requireCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            throw GuestFailure("PROBE_PERMISSION_MISSING", "Camera permission is required to verify setup or restoration", "Grant Camera permission to this guest service and run the operation again.")
    }
    private fun runCheck(message: String, requiresRoot: Boolean = false, check: () -> JSONObject) {
        // Do not request guest superuser permission or invoke su on a host phone.
        try { GuestRuntime(this).requireGuest() }
        catch (error: GuestFailure) {
            report.text = JSONObject().put("code", error.code).put("message", error.message).put("recovery", error.recovery).toString(2)
            return
        }
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) needed.add(Manifest.permission.CAMERA)
        val rootPermission = runCatching { packageManager.getPermissionInfo(SUPERUSER_PERMISSION, 0) }.getOrNull()
        if (requiresRoot && rootPermission != null && rootPermission.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS &&
            checkSelfPermission(SUPERUSER_PERMISSION) != PackageManager.PERMISSION_GRANTED) needed.add(SUPERUSER_PERMISSION)
        if (needed.isNotEmpty()) {
            pendingPermissionAction = { runCheck(message, requiresRoot, check) }
            report.text = "Grant the requested guest permissions to continue. Superuser access is then checked separately by the guest Superuser manager."
            actions.forEach { it.isEnabled = false }
            requestPermissions(needed.toTypedArray(), PERMISSION_REQUEST)
            return
        }
        report.text = message
        actions.forEach { it.isEnabled = false }
        worker.execute {
            val result = try { check() }
            catch (error: Throwable) {
                JSONObject().put("code", (error as? GuestFailure)?.code ?: "PREFLIGHT_FAILED")
                    .put("message", error.message ?: error.javaClass.simpleName)
                    .put("recovery", (error as? GuestFailure)?.recovery ?: "The guest camera bridge remains inactive.")
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) { report.text = result.toString(2); actions.forEach { it.isEnabled = true } } }
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        val resume = pendingPermissionAction
        pendingPermissionAction = null
        actions.forEach { it.isEnabled = true }
        if (permissions.isNotEmpty() && permissions.size == grantResults.size && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) resume?.invoke()
        else report.text = "The guest permission was not granted. Setup has not changed the camera. Grant Camera and the guest Superuser permission in this app's guest settings, then run the operation again."
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    companion object {
        private const val SUPERUSER_PERMISSION = "android.permission.ACCESS_SUPERUSER"
        private const val PERMISSION_REQUEST = 41
    }
}
