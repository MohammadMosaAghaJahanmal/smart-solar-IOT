package com.virtualcamerapro.systemtests

import android.content.*
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.*
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraFrameProbe
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** No provider doubles or skipped prerequisites: run only on the built reference image. */
@RunWith(AndroidJUnit4::class)
class SystemCameraAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(CameraManager::class.java)
    private val evidence = File(context.getExternalFilesDir(null), "acceptance").apply { mkdirs() }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun waitFor(label: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate()) { check(SystemClock.elapsedRealtime() < end) { "Timed out: $label; see saved evidence" }; Thread.sleep(100) }
    }
    private fun ids() = manager.cameraIdList.filterNot { it.startsWith("v0_") }.also { check(it.isNotEmpty()) { "No physical reference camera IDs" } }
    private fun route() = JSONObject(shell("dumpsys media.camera --virtual-camera-pro")).also { assertEquals(2, it.getInt("protocol")) }
    private fun settings(id: String, width: Int = 1280, height: Int = 720) = JSONObject()
        .put("cameraId", id).put("width", width).put("height", height).put("rotation", 0).put("scaleMode", "fit").put("loop", true).toString()
    private fun image(color: Int): File {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File.createTempFile("fixture-", ".jpg", context.cacheDir).also { file ->
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) }; bitmap.recycle()
        }
    }
    private fun verifyColor(bytes: ByteArray, expected: Int) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            val actual = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            assertTrue("JPEG contains wrong source: ${Integer.toHexString(actual)}", abs(Color.red(actual)-Color.red(expected)) < 35 && abs(Color.green(actual)-Color.green(expected)) < 35 && abs(Color.blue(actual)-Color.blue(expected)) < 35)
        } finally { bitmap.recycle() }
    }
    private fun withBroker(action: (IVirtualCameraBroker) -> Unit) {
        assertEquals("Boot the protocol 2 reference image", "2", shell("getprop ro.virtualcamera.pro.version").trim())
        assertEquals("Acceptance is restricted to userdebug", "1", shell("getprop ro.debuggable").trim())
        val connected = CountDownLatch(1)
        var remote: IVirtualCameraBroker? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { remote = IVirtualCameraBroker.Stub.asInterface(binder); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        val intent = Intent().setComponent(ComponentName("com.virtualcamerapro.broker", "com.virtualcamerapro.broker.BrokerService"))
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) { "Broker binding failed; install the platform-signed image APK" }
        try {
            check(connected.await(10, TimeUnit.SECONDS)) { "Broker did not connect" }
            val broker = requireNotNull(remote)
            val support = JSONObject(broker.support)
            assertTrue("Missing integration: $support", support.getBoolean("supported"))
            assertFalse("Remove stale routing before tests", route().getBoolean("routeInstalled"))
            context.startForegroundService(intent)
            action(broker)
        } finally {
            runCatching { remote?.deactivate() }; context.unbindService(connection)
        }
    }
    private fun activate(broker: IVirtualCameraBroker, file: File, type: String, id: String, width: Int = 1280, height: Int = 720) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { assertTrue(broker.activate(it, type, settings(id, width, height))) }
        val state = JSONObject(broker.status)
        assertEquals("active", state.getString("phase"))
        val proof = state.getJSONObject("proof")
        assertTrue(proof.getInt("previewFrames") >= 3); assertTrue(proof.getInt("jpegBytes") > 0); assertTrue(proof.getInt("producerFrames") > 0)
        File(evidence, "activation-$id-$width.json").writeText(state.toString(2))
    }

    @Test fun selectedImagesSwitchAndPhysicalRestore() = withBroker { broker ->
        val red = image(Color.RED); val blue = image(Color.BLUE)
        try {
            // Exercise each real ID (including 0 and 1 when present) and both advertised input resolutions.
            for (id in ids()) for ((width, height) in listOf(1280 to 720, 1920 to 1080)) {
                context.startForegroundService(Intent().setComponent(ComponentName("com.virtualcamerapro.broker", "com.virtualcamerapro.broker.BrokerService")))
                activate(broker, red, "image", id, width, height)
                val redFrames = CameraFrameProbe(context).capture(id, preferredWidth = width, preferredHeight = height)
                assertEquals(width, redFrames.previewWidth); assertEquals(height, redFrames.previewHeight)
                assertEquals(width, redFrames.jpegWidth); assertEquals(height, redFrames.jpegHeight)
                verifyColor(redFrames.jpeg, Color.RED); File(evidence, "red-$id-$width.jpg").writeBytes(redFrames.jpeg)
                ParcelFileDescriptor.open(blue, ParcelFileDescriptor.MODE_READ_ONLY).use { broker.updateSource(it, "image") }
                val blueFrames = CameraFrameProbe(context).capture(id, preferredWidth = width, preferredHeight = height)
                verifyColor(blueFrames.jpeg, Color.BLUE); File(evidence, "blue-$id-$width.jpg").writeBytes(blueFrames.jpeg)
                broker.deactivate()
                assertFalse(route().getBoolean("routeInstalled"))
                val restored = CameraFrameProbe(context).capture(id)
                assertTrue(restored.previewFrames >= 3)
                File(evidence, "physical-restored-$id-$width.jpg").writeBytes(restored.jpeg)
            }
        } finally { red.delete(); blue.delete() }
    }

    @Test fun selectedVideoLoopsInIndependentCamera2Client() = withBroker { broker ->
        val video = File(context.cacheDir, "red-blue-2s.mp4")
        context.assets.open("red-blue-2s.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
        try {
            val id = ids().first()
            activate(broker, video, "video", id)
            val frames = CameraFrameProbe(context).capture(id, 6500)
            // Red Y is ~76, blue Y is ~29 (full range); limited range is similarly separated.
            val samples = frames.centerLuma
            val transitions = samples.zipWithNext().count { (a, b) -> (a > 55) != (b > 55) }
            assertTrue("No repeated red/blue loops in delivered YUV buffers: $samples", transitions >= 4)
            File(evidence, "video-frames.json").writeText(frames.metrics().put("centerLuma", org.json.JSONArray(samples)).put("transitions", transitions).toString(2))
            File(evidence, "video.jpg").writeBytes(frames.jpeg)
        } finally { video.delete() }
    }

    @Test fun originalAndroidCameraPreviewAndPhoto() = withBroker { broker ->
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val cameraPackage = "com.android.camera2"
        val id = ids().firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
            ?: error("Reference image must provide a back camera for the original Camera app test")
        val red = image(Color.RED)
        try {
            activate(broker, red, "image", id)
            shell("am force-stop $cameraPackage")
            shell("pm grant $cameraPackage android.permission.CAMERA")
            shell("pm grant $cameraPackage android.permission.RECORD_AUDIO")
            val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage(cameraPackage).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            check(context.packageManager.resolveActivity(intent, 0) != null) { "Reference Camera2 app is missing" }
            context.startActivity(intent)
            var shutter: AccessibilityNodeInfo? = null
            waitFor("original Camera shutter after first-run preferences", 30000) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                // Only the pinned AOSP Camera2's own first-run controls are handled.
                fun control(name: String) = root?.findAccessibilityNodeInfosByViewId("$cameraPackage:id/$name")
                    ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                control("check_box")?.let { if (it.isChecked) it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                control("aspect_ratio_16x9_button")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                control("confirm_button")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                shutter = root?.findAccessibilityNodeInfosByViewId("$cameraPackage:id/shutter_button")?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                shutter != null
            }
            // Verify the original app's visible preview separately from its saved JPEG.
            waitFor("original Camera preview pixels") {
                val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
                try {
                    File(evidence, "original-camera-preview.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    var redCount = 0; var count = 0
                    for (y in screenshot.height/4 until screenshot.height*3/4 step 20) for (x in screenshot.width/4 until screenshot.width*3/4 step 20) {
                        val pixel = screenshot.getPixel(x,y); count++
                        if (Color.red(pixel) > 180 && Color.green(pixel) < 70 && Color.blue(pixel) < 70) redCount++
                    }
                    redCount > count * 0.7
                } finally { screenshot.recycle() }
            }
            fun latest() = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
            val before = latest()
            assertTrue("Original Camera shutter action failed", requireNotNull(shutter).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            waitFor("original Camera saved photo") { latest() > before }
            val photo = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, latest())
            val bytes = requireNotNull(context.contentResolver.openInputStream(photo)).use { it.readBytes() }
            verifyColor(bytes, Color.RED); File(evidence, "original-camera-photo.jpg").writeBytes(bytes)
            // Verify restoration through the original app separately from the probe tests.
            shell("am force-stop $cameraPackage")
            broker.deactivate()
            assertFalse(route().getBoolean("routeInstalled"))
            waitFor("synthetic camera removal") { manager.cameraIdList.none { it.startsWith("v0_") } }
            context.startActivity(intent)
            waitFor("original Camera shutter after deactivation") {
                shutter = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByViewId("$cameraPackage:id/shutter_button")
                    ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
                shutter != null
            }
            val restoredBefore = latest()
            assertTrue(requireNotNull(shutter).performAction(AccessibilityNodeInfo.ACTION_CLICK))
            var restoredBytes: ByteArray? = null
            waitFor("original Camera physical photo after deactivation") {
                if (latest() <= restoredBefore) false else runCatching {
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, latest())
                    val data = requireNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return@runCatching false
                    bitmap.recycle(); restoredBytes = data; true
                }.getOrDefault(false)
            }
            File(evidence, "original-camera-physical-restored.jpg").writeBytes(requireNotNull(restoredBytes))
        } finally { shell("am force-stop $cameraPackage"); red.delete() }
    }
}
