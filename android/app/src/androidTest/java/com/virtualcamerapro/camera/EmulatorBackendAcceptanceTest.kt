package com.virtualcamerapro.camera

import android.content.Intent
import android.content.ContentUris
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraFrameProof
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Actual controller UID -> installed broker -> emulator camera -> independent Camera2/CameraX. */
@RunWith(AndroidJUnit4::class)
class EmulatorBackendAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = File(context.getExternalFilesDir(null), "emulator-acceptance").apply { mkdirs() }
    private val report = JSONObject().put("controllerUid", Process.myUid()).put("controllerPid", Process.myPid())
        .put("passed", false)

    private fun save() = File(evidence, "acceptance.json").writeText(report.toString(2))
    private fun stage(name: String) { report.put("stage", name); save() }

    private fun shell(command: String, timeoutSeconds: Long = 15): String {
        val automation = instrumentation.uiAutomation
        val descriptor = automation.executeShellCommand(command)
        val read = FutureTask {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        Thread(read, "AcceptanceShellReader").apply { isDaemon = true; start() }
        try { return read.get(timeoutSeconds, TimeUnit.SECONDS) }
        finally { runCatching { descriptor.close() }; read.cancel(true) }
    }

    private fun foregroundController() {
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (true) {
            val resumed = shell("dumpsys activity activities").lineSequence()
                .filter { it.contains("ResumedActivity") || it.contains("topResumedActivity") }.joinToString("\n")
            if (resumed.contains("com.virtualcamerapro/.MainActivity")) return
            check(SystemClock.elapsedRealtime() < deadline) { "Controller activity did not become foreground: $resumed" }
            Thread.sleep(100)
        }
    }

    private fun image(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(context.cacheDir, "acceptance-$name.png").also { file ->
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun center(bytes: ByteArray): Int {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Invalid camera JPEG" }
        return try { bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) } finally { bitmap.recycle() }
    }

    private fun verifyColor(bytes: ByteArray, expected: Int) {
        val actual = center(bytes)
        assertTrue("Wrong camera JPEG source: got ${Integer.toHexString(actual)} expected ${Integer.toHexString(expected)}",
            abs(Color.red(actual) - Color.red(expected)) <= 35 &&
                abs(Color.green(actual) - Color.green(expected)) <= 35 &&
                abs(Color.blue(actual) - Color.blue(expected)) <= 35)
    }

    private fun writeFrames(name: String, frames: CameraFrameProof): JSONObject {
        assertTrue("No actual YUV buffers", frames.previewFrames >= 3)
        assertTrue("Camera timestamps did not progress", frames.lastTimestamp > frames.firstTimestamp)
        File(evidence, "$name.jpg").writeBytes(frames.jpeg)
        return frames.metrics().put("centerLuma", JSONArray(frames.centerLuma)).also {
            File(evidence, "$name.json").writeText(it.toString(2)); report.put(name, it); save()
        }
    }

    private fun active(controller: VirtualCameraController, selected: File, name: String): JSONObject {
        val status = controller.status()
        File(evidence, "$name-status.json").writeText(status.toString(2))
        assertEquals("active", status.getString("phase"))
        val proof = status.getJSONObject("proof")
        assertEquals("android_emulator_environment", proof.getString("backend"))
        assertTrue("The host must configure its actual camera renderer", proof.getBoolean("rendererConfigured"))
        assertTrue("The broker must independently verify actual source pixels", proof.getBoolean("sourceVerified"))
        assertEquals(sha256(selected), proof.getString("sourceSha256"))
        assertTrue("No host generation returned", proof.getString("hostGeneration").isNotBlank())
        assertTrue(proof.getInt("previewFrames") >= 3)
        assertTrue(proof.getInt("jpegBytes") > 0)
        report.put("$name-status", status); save()
        return status
    }

    private fun foregroundBroker(name: String) {
        val dump = shell("dumpsys activity services com.virtualcamerapro.broker")
        File(evidence, "$name-service.txt").writeText(dump)
        assertTrue("The broker must remain a real foreground service", dump.contains("isForeground=true"))
    }

    private fun externalClient(name: String, cameraId: String, expectedRgb: String? = null, video: Boolean = false) {
        stage(name)
        shell("input keyevent 3")
        foregroundBroker("$name-before")
        val command = buildString {
            val testClass = "com.virtualcamerapro.client.CameraPipelineTest"
            append("am instrument -w -r -e class ")
            append(if (video) testClass else "$testClass#camera2PreviewAndJpeg,$testClass#cameraXPreviewAndJpeg")
            append(" -e cameraId ").append(cameraId)
            if (expectedRgb != null) append(" -e expectedRgb ").append(expectedRgb)
            if (video) append(" -e video true")
            append(" com.virtualcamerapro.client.test/androidx.test.runner.AndroidJUnitRunner")
        }
        // CAMERA is granted before the run, so CameraPipelineTest uses no UiAutomation
        // and can coexist with this instrumentation's connection to the original app.
        val result = try { shell(command, timeoutSeconds = 90) }
        catch (error: Exception) {
            runCatching { shell("am force-stop com.virtualcamerapro.client") }
            File(evidence, "$name-client.txt").writeText("Nested external consumer timed out or failed: $error")
            throw error
        }
        File(evidence, "$name-client.txt").writeText(result)
        val testCount = if (video) 4 else 2
        assertTrue("The independent Camera2/CameraX app failed: $result", result.contains("OK ($testCount tests)") &&
            !Regex("FAILURES|INSTRUMENTATION_FAILED|Process crashed").containsMatchIn(result))
        foregroundBroker("$name-after")
        val status = shell("dumpsys activity activities").lineSequence()
            .filter { it.contains("ResumedActivity") || it.contains("topResumedActivity") }.joinToString("\n")
        assertFalse("Controller must remain in the background during the external client phase", status.contains("com.virtualcamerapro/.MainActivity"))
        report.put(name, JSONObject().put("passed", true).put("videoLoopsEnabled", video)
            .put("expectedRgb", expectedRgb ?: JSONObject.NULL).put("controllerInBackground", true)); save()
    }

    private fun originalCamera(name: String, expectedColor: Int? = null): ByteArray {
        stage(name)
        val cameraPackage = "com.android.camera2"
        shell("am force-stop $cameraPackage")
        shell("pm grant $cameraPackage android.permission.CAMERA")
        shell("pm grant $cameraPackage android.permission.RECORD_AUDIO")
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        shell("am start -W -a android.media.action.STILL_IMAGE_CAMERA -p $cameraPackage")
        fun waitFor(label: String, timeoutMs: Long = 20000, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (!predicate()) {
                check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for $label" }
                Thread.sleep(100)
            }
        }
        fun tap(node: AccessibilityNodeInfo, label: String, verticalFraction: Float = 0.5f): Rect {
            assertTrue("$label must be visible and enabled", node.isVisibleToUser && node.isEnabled)
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            check(!bounds.isEmpty) { "$label has no screen bounds" }
            val x = bounds.exactCenterX()
            val y = bounds.top + bounds.height() * verticalFraction
            val downTime = SystemClock.uptimeMillis()
            fun inject(action: Int, eventTime: Long) {
                val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                try { assertTrue("Android rejected $label input action $action", automation.injectInputEvent(event, true)) }
                finally { event.recycle() }
            }
            inject(MotionEvent.ACTION_DOWN, downTime)
            Thread.sleep(60)
            inject(MotionEvent.ACTION_UP, SystemClock.uptimeMillis())
            return bounds
        }
        fun control(root: AccessibilityNodeInfo?, id: String) = root?.findAccessibilityNodeInfosByViewId("$cameraPackage:id/$id")
            ?.firstOrNull { it.isVisibleToUser && it.isEnabled }
        fun photoMode(root: AccessibilityNodeInfo?) = root?.findAccessibilityNodeInfosByText("Switch to Camera Mode")
            ?.firstOrNull { it.isVisibleToUser && it.isEnabled && it.contentDescription?.toString() == "Switch to Camera Mode" }
        var photoModeSelections = 0
        var lastModeTapMs = -1L
        fun waitForPhotoShutter(): AccessibilityNodeInfo {
            var readyShutter: AccessibilityNodeInfo? = null
            var drawerClosedSinceMs = -1L
            waitFor("original Camera photo mode, stable closed drawer, and shutter", 30000) {
                val root = automation.rootInActiveWindow
                control(root, "check_box")?.let { if (it.isChecked) it.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                control(root, "aspect_ratio_16x9_button")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                control(root, "confirm_button")?.let {
                    it.performAction(AccessibilityNodeInfo.ACTION_CLICK); drawerClosedSinceMs = -1L; return@waitFor false
                }
                val now = SystemClock.elapsedRealtime()
                // A delayed first-launch hint can reopen this drawer after preview
                // starts. Re-evaluate fresh nodes and reselect Photo with a cooldown.
                photoMode(root)?.let {
                    drawerClosedSinceMs = -1L
                    if (lastModeTapMs < 0 || now - lastModeTapMs >= 300) {
                        tap(it, "original Camera photo mode"); photoModeSelections++; lastModeTapMs = now
                    }
                    return@waitFor false
                }
                val currentShutter = control(root, "shutter_button")
                if (control(root, "mode_list_layout") != null || currentShutter == null) {
                    drawerClosedSinceMs = -1L; return@waitFor false
                }
                if (drawerClosedSinceMs < 0) drawerClosedSinceMs = now
                if (now - drawerClosedSinceMs < 200) return@waitFor false
                readyShutter = currentShutter
                true
            }
            return requireNotNull(readyShutter)
        }
        waitForPhotoShutter()
        fun expected(pixel: Int): Boolean {
            val target = requireNotNull(expectedColor)
            return abs(Color.red(pixel) - Color.red(target)) <= 45 &&
                abs(Color.green(pixel) - Color.green(target)) <= 45 && abs(Color.blue(pixel) - Color.blue(target)) <= 45
        }
        // The visible original-app preview is separate evidence from a saved JPEG.
        waitFor("original Camera preview source") {
            val screenshot = automation.takeScreenshot() ?: return@waitFor false
            try {
                File(evidence, "$name-preview.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (expectedColor == null) true else {
                    var matching = 0; var total = 0
                    for (y in screenshot.height / 4 until screenshot.height * 3 / 4 step 20)
                        for (x in screenshot.width / 4 until screenshot.width * 3 / 4 step 20) {
                            total++; if (expected(screenshot.getPixel(x, y))) matching++
                        }
                    total > 0 && matching.toDouble() / total > 0.7
                }
            } finally { screenshot.recycle() }
        }
        fun latest(): Long {
            return context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, "${MediaStore.Images.Media._ID} DESC")
                ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
        }
        val before = latest()
        // Reacquire the current node after first-run/module animations. ShutterButton
        // dispatchTouchEvent records focus and touch coordinates before performClick.
        // The observed shutter view extends behind the navigation bar, so use its
        // upper third rather than the center of those inclusive view bounds.
        val shutter = waitForPhotoShutter()
        val bounds = tap(shutter, "original Camera shutter", 1f / 3)
        File(evidence, "$name-controls.json").writeText(JSONObject().put("photoModeSelected", photoModeSelections > 0)
            .put("photoModeSelections", photoModeSelections).put("stableDrawerClosedMs", 200)
            .put("shutterBounds", bounds.toShortString()).put("input", "touch-down-up-upper-third")
            .put("mediaStoreIdBefore", before).toString(2))
        var photoId = -1L
        waitFor("original Camera saved photo") { photoId = latest(); photoId > before }
        val photo = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, photoId)
        val bytes = requireNotNull(context.contentResolver.openInputStream(photo)).use { it.readBytes() }
        // Read as the ordinary controller UID with this diagnostic build's explicit
        // READ_MEDIA_IMAGES grant; Android 16 restricts shell-UID media visibility.
        val pixel = center(bytes)
        if (expectedColor != null) verifyColor(bytes, expectedColor)
        File(evidence, "$name-photo.jpg").writeBytes(bytes)
        report.put(name, JSONObject().put("passed", true).put("photoId", photoId).put("jpegBytes", bytes.size)
            .put("mediaReadAccess", "controller READ_MEDIA_IMAGES")
            .put("centerRgb", Integer.toHexString(pixel)).put("expectedRgb", expectedColor?.let { Integer.toHexString(it) } ?: JSONObject.NULL)); save()
        shell("am force-stop $cameraPackage")
        return bytes
    }

    private fun pixelDifference(first: ByteArray, second: ByteArray): Double {
        fun thumbnail(bytes: ByteArray): Bitmap {
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            return try { Bitmap.createScaledBitmap(bitmap, 32, 18, true) } finally { bitmap.recycle() }
        }
        val a = thumbnail(first); val b = thumbnail(second)
        try {
            var difference = 0L
            for (y in 0 until a.height) for (x in 0 until a.width) {
                val p = a.getPixel(x, y); val q = b.getPixel(x, y)
                difference += abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))
            }
            return difference.toDouble() / (a.width * a.height * 3)
        } finally { a.recycle(); b.recycle() }
    }

    @Test fun controllerActivatesSwitchesLoopsInBackgroundAndRestores() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in only on the configured host-backed Android emulator", args.getString("emulatorBackend") == "true")
        assertNotEquals("Controller must use its ordinary app UID", 1000, Process.myUid())
        assertNotEquals("Binder work must not block Android's main thread", Looper.getMainLooper(), Looper.myLooper())
        val cameraId = args.getString("cameraId", "10")
        require(cameraId.matches(Regex("[A-Za-z0-9_]{1,32}"))) { "Invalid configured camera ID" }
        assertEquals("This test never targets a physical device", "1", shell("getprop ro.kernel.qemu").trim())
        shell("pm grant com.virtualcamerapro android.permission.CAMERA")
        shell("pm grant com.virtualcamerapro android.permission.READ_MEDIA_IMAGES")
        shell("pm grant com.virtualcamerapro.client android.permission.CAMERA")
        val red = image("red", Color.RED)
        val blue = image("blue", Color.BLUE)
        val video = File(context.cacheDir, "acceptance-red-blue-2s.mp4").apply {
            instrumentation.context.assets.open("red-blue-2s.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        VirtualCameraController(context).use { controller ->
            try {
                stage("readiness")
                val support = controller.support()
                report.put("support", support).put("cameraInventory", JSONArray(controller.cameras())); save()
                assertTrue("The installed emulator broker is not ready: $support", support.getBoolean("supported"))
                assertEquals("READY", support.getString("code"))
                val inventory = JSONArray(controller.cameras())
                assertTrue("Configured camera $cameraId is absent", (0 until inventory.length()).any { inventory.getJSONObject(it).getString("id") == cameraId })
                controller.deactivate()
                foregroundController()
                stage("baseline")
                val baseline = CameraFrameProbe(context).capture(cameraId)
                writeFrames("baseline", baseline)
                controller.update(OutputSettings(width = 1280, height = 720, loop = true, cameraId = cameraId))

                stage("red-activation")
                controller.select(Uri.fromFile(red).toString(), "image")
                assertTrue("The real controller activation failed", controller.activate())
                active(controller, red, "red")
                val redFrames = CameraFrameProbe(context).capture(cameraId)
                writeFrames("red", redFrames); verifyColor(redFrames.jpeg, Color.RED)
                externalClient("red-external", cameraId, expectedRgb = "FF0000")
                foregroundBroker("original-camera-red-before")
                originalCamera("original-camera-red", Color.RED)
                foregroundBroker("original-camera-red-after")

                foregroundController()
                stage("blue-source-switch")
                controller.select(Uri.fromFile(blue).toString(), "image")
                active(controller, blue, "blue")
                val blueFrames = CameraFrameProbe(context).capture(cameraId)
                writeFrames("blue", blueFrames); verifyColor(blueFrames.jpeg, Color.BLUE)
                externalClient("blue-external", cameraId, expectedRgb = "0000FF")

                foregroundController()
                stage("video-source-switch")
                controller.select(Uri.fromFile(video).toString(), "video")
                active(controller, video, "video")
                val frames = CameraFrameProbe(context).capture(cameraId, sampleDurationMs = 6500)
                val videoMetrics = writeFrames("video", frames)
                val colors = mutableListOf<Boolean>()
                var pending: Boolean? = null
                var streak = 0
                for (luma in frames.centerLuma) {
                    val redFrame = luma > 55
                    if (redFrame == pending) streak++ else { pending = redFrame; streak = 1 }
                    if (streak >= 3 && colors.lastOrNull() != redFrame) colors.add(redFrame)
                }
                val transitions = (colors.size - 1).coerceAtLeast(0)
                videoMetrics.put("stableTransitions", transitions)
                File(evidence, "video.json").writeText(videoMetrics.toString(2)); save()
                assertTrue("No repeated loops in actual YUV frames", transitions >= 4)
                assertTrue("Video observation is too short", frames.lastTimestamp - frames.firstTimestamp >= 6000000000L)
                val videoPixel = center(frames.jpeg)
                assertTrue("Video JPEG lacks the actual red/blue source", (Color.red(videoPixel) >= 170 && Color.blue(videoPixel) <= 100) ||
                    (Color.blue(videoPixel) >= 170 && Color.red(videoPixel) <= 100))
                externalClient("video-background-external", cameraId, video = true)
                active(controller, video, "video-after-background")

                foregroundController()
                stage("deactivation-and-restore")
                controller.deactivate()
                val stopped = controller.status()
                assertEquals("stopped", stopped.getString("phase"))
                report.put("stopped", stopped); save()
                val restored = CameraFrameProbe(context).capture(cameraId)
                writeFrames("restored", restored)
                val restoredVsBaseline = pixelDifference(baseline.jpeg, restored.jpeg)
                val restoredVsRed = pixelDifference(redFrames.jpeg, restored.jpeg)
                val restoredVsBlue = pixelDifference(blueFrames.jpeg, restored.jpeg)
                report.put("restoration", JSONObject().put("baselinePixelMad", restoredVsBaseline)
                    .put("redPixelMad", restoredVsRed).put("bluePixelMad", restoredVsBlue)); save()
                assertTrue("Camera pixels were not restored to their prior source: MAD=$restoredVsBaseline", restoredVsBaseline < 20)
                assertTrue("Restored camera still contains the selected red or blue image", restoredVsRed > 35 && restoredVsBlue > 35)
                val originalRestored = originalCamera("original-camera-restored")
                val originalRestoredVsRed = pixelDifference(redFrames.jpeg, originalRestored)
                report.put("originalCameraRestoredPixelMadVsRed", originalRestoredVsRed); save()
                assertTrue("Original Camera still receives the selected red source after deactivation", originalRestoredVsRed > 35)
                report.put("passed", true); stage("complete")
            } catch (error: Throwable) {
                report.put("failure", error.toString()); save(); throw error
            } finally {
                runCatching { shell("am force-stop com.android.camera2") }
                runCatching { controller.deactivate() }
                red.delete(); blue.delete(); video.delete()
            }
        }
    }
}
