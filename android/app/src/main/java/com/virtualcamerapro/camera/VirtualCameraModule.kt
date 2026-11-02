package com.virtualcamerapro.camera

import android.app.Activity
import android.content.Intent
import com.facebook.react.bridge.*
import com.virtualcamerapro.specs.NativeVirtualCameraSpec
import org.json.JSONObject
import java.util.concurrent.Executors

class VirtualCameraModule(private val context: ReactApplicationContext) : NativeVirtualCameraSpec(context) {
    private val controller = VirtualCameraController(context)
    private val worker = Executors.newSingleThreadExecutor()
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
    private fun task(promise: Promise, action: () -> Any?) { worker.execute { try { promise.resolve(action()) } catch (e: Exception) { promise.reject("CAMERA_ENGINE",e.cause?.message ?: e.message,e) } } }
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
    override fun activate(promise: Promise) = task(promise) { controller.activate() }
    override fun deactivate(promise: Promise) = task(promise) { controller.deactivate(); null }
    override fun setLoop(enabled: Boolean, promise: Promise) = task(promise) { controller.update(controller.state.settings.copy(loop=enabled)); null }
    override fun setResolution(width: Double, height: Double, promise: Promise) = task(promise) { controller.update(controller.state.settings.copy(width=width.toInt(),height=height.toInt())); null }
    override fun setOutput(rotation: Double, scaleMode: String, cameraId: String, promise: Promise) = task(promise) { controller.update(controller.state.settings.copy(rotation=rotation.toInt(),scaleMode=scaleMode,cameraId=cameraId)); null }
    override fun setSourceTransform(zoom: Double, panX: Double, panY: Double, promise: Promise) = task(promise) { controller.update(controller.state.settings.copy(zoom=zoom,panX=panX,panY=panY)); null }
    override fun getStatus(promise: Promise) = task(promise) { controller.status().toString() }
    override fun getCameras(promise: Promise) = task(promise) { controller.cameras() }
    override fun checkSystemSupport(promise: Promise) = task(promise) { controller.support().toString() }
    override fun invalidate() { context.removeActivityEventListener(listener); pickerPromise?.reject("CLOSED","App closed"); pickerPromise = null; worker.execute { controller.close() }; worker.shutdown(); super.invalidate() }
    companion object { const val NAME = "VirtualCamera" }
}
