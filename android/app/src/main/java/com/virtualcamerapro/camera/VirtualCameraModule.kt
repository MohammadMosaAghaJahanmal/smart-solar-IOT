package com.virtualcamerapro.camera

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.*
import com.virtualcamerapro.specs.NativeVirtualCameraSpec
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import com.virtualcamerapro.setup.UnifiedSetupClient
import com.facebook.react.modules.core.PermissionAwareActivity
import com.facebook.react.modules.core.PermissionListener
import android.content.pm.PackageManager
import com.virtualcamerapro.overlay.FloatingCameraController

class VirtualCameraModule(private val context: ReactApplicationContext) : NativeVirtualCameraSpec(context) {
    private val controller = VirtualCameraController(context)
    private val worker = Executors.newSingleThreadExecutor()
    // Stop must reach the Binder while the ordinary worker waits for decoding or
    // Camera2 verification. Its generation also cancels Activate calls still queued.
    private val stopWorker = Executors.newSingleThreadExecutor()
    private val mediaGeneration = AtomicLong()
    private val setup = UnifiedSetupClient(context)
    private var permissionPromise: Promise? = null
    private var pickerPromise: Promise? = null
    private val listener = object : BaseActivityEventListener() {
        override fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
            if (requestCode != 7120) return
            val promise = pickerPromise ?: return; pickerPromise = null
            val uri = data?.data
            if (resultCode != Activity.RESULT_OK || uri == null) { promise.reject("PICK_CANCELLED","Media selection cancelled"); return }
            try { context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { /* copy immediately */ }
            promise.resolve(JSONObject().put("uri",uri.toString()).toString())
        }
    }
    init { context.addActivityEventListener(listener) }
    override fun getName() = NAME
    private fun task(promise: Promise, executor: ExecutorService = worker, action: () -> Any?) { executor.execute { try { promise.resolve(action()) } catch (e: Exception) { promise.reject("CAMERA_ENGINE",e.cause?.message ?: e.message,e) } } }
    override fun pickMedia(type: String, promise: Promise) {
        if (type !in listOf("image","video")) { promise.reject("MEDIA_TYPE","Choose image or video"); return }
        val activity = context.currentActivity ?: run { promise.reject("NO_ACTIVITY","Open the app before selecting media"); return }
        activity.runOnUiThread {
            if (pickerPromise != null) { promise.reject("PICK_BUSY","Gallery is already open"); return@runOnUiThread }
            pickerPromise = promise
            try { activity.startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("$type/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),7120) }
            catch (e: Exception) { pickerPromise = null; promise.reject("PICK_FAILED",e) }
        }
    }
    override fun selectMedia(uri: String, type: String, promise: Promise) = task(promise) { controller.select(uri,type); null }
    override fun activate(promise: Promise) {
        val generation = mediaGeneration.get()
        task(promise) {
            try { controller.activate { generation == mediaGeneration.get() } }
            catch (error: Exception) {
                if (generation == mediaGeneration.get()) throw error
                controller.deactivate()
                false
            }
        }
    }
    override fun deactivate(promise: Promise) {
        mediaGeneration.incrementAndGet()
        task(promise, stopWorker) { controller.deactivate(); null }
    }
    override fun setLoop(enabled: Boolean, promise: Promise) = task(promise) { controller.updateSettings { it.copy(loop=enabled) }; null }
    override fun setResolution(width: Double, height: Double, promise: Promise) = task(promise) { controller.updateSettings { it.copy(width=width.toInt(),height=height.toInt()) }; null }
    override fun setOutput(rotation: Double, scaleMode: String, cameraId: String, promise: Promise) = task(promise) { controller.updateSettings { it.copy(rotation=rotation.toInt(),scaleMode=scaleMode,cameraId=cameraId) }; null }
    override fun setSourceTransform(zoom: Double, panX: Double, panY: Double, promise: Promise) = task(promise) { controller.applySourceTransform(zoom,panX,panY); null }
    override fun setReplacementMode(mode: String, promise: Promise) = task(promise) { controller.setReplacementMode(mode); null }
    override fun getOverlayStatus(promise: Promise) = task(promise) { FloatingCameraController.status(context).toString() }
    override fun requestOverlayPermission(promise: Promise) = task(promise) { FloatingCameraController.requestPermission(context).toString() }
    override fun setOverlayVisible(visible: Boolean, promise: Promise) = task(promise) { FloatingCameraController.setVisible(context,visible).toString() }
    override fun getStatus(promise: Promise) = task(promise) { controller.status().toString() }
    override fun getCameras(promise: Promise) = task(promise) { controller.cameras() }
    override fun checkSystemSupport(promise: Promise) = task(promise) { controller.support().toString() }
    override fun getSetupStatus(promise: Promise) = task(promise) { setup.getStatus().toString() }
    override fun runSetup(action: String, approved: Boolean, promise: Promise) = task(promise) { setup.run(action,approved).toString() }
    override fun clearMedia(promise: Promise) {
        mediaGeneration.incrementAndGet()
        stopWorker.execute { runCatching { controller.deactivate() } }
        task(promise) {
            controller.clearMedia()
            if (com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry.profileMatches()) setup.run("clear_media",false)
            null
        }
    }
    override fun setRecoveryPreferences(autoRecover: Boolean, autoResume: Boolean, promise: Promise) = task(promise) { setup.setRecoveryPreferences(autoRecover,autoResume); null }
    override fun exportDiagnostics(promise: Promise) = task(promise) { setup.exportDiagnostics() }
    override fun requestSetupPermissions(promise: Promise) {
        val activity = context.currentActivity
        if (activity !is PermissionAwareActivity) { promise.reject("NO_ACTIVITY", "Open Virtual Camera Pro to grant guest permissions"); return }
        activity.runOnUiThread {
            if (permissionPromise != null) { promise.reject("PERMISSION_BUSY", "A permission request is already open"); return@runOnUiThread }
            val missing = mutableListOf<String>()
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) missing.add(android.Manifest.permission.CAMERA)
            val info = runCatching { context.packageManager.getPermissionInfo("android.permission.ACCESS_SUPERUSER",0) }.getOrNull()
            if (info != null && info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS &&
                context.checkSelfPermission(info.name) != PackageManager.PERMISSION_GRANTED) missing.add(info.name)
            if (missing.isEmpty()) { worker.execute { try { promise.resolve(setup.run("inspect",false).toString()) } catch (e: Exception) { promise.reject("SETUP",e) } }; return@runOnUiThread }
            permissionPromise = promise
            activity.requestPermissions(missing.toTypedArray(),7121,PermissionListener { code, _, _ ->
                if (code != 7121) false else {
                    val result = permissionPromise; permissionPromise = null
                    if (result != null) worker.execute { try { result.resolve(setup.run("inspect",false).toString()) } catch (e: Exception) { result.reject("SETUP",e) } }
                    true
                }
            })
        }
    }
    override fun invalidate() {
        context.removeActivityEventListener(listener)
        pickerPromise?.reject("CLOSED","App closed"); pickerPromise = null
        permissionPromise?.reject("CLOSED","App closed"); permissionPromise=null
        mediaGeneration.incrementAndGet()
        stopWorker.shutdown()
        worker.execute { stopWorker.awaitTermination(5,TimeUnit.SECONDS); controller.close(); setup.close() }
        worker.shutdown(); super.invalidate()
    }
    companion object { const val NAME = "VirtualCamera" }
}
