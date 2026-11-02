package com.virtualcamerapro.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
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
import kotlin.math.abs

/** Ordinary controller UID -> real Binder broker -> independent public Camera2 consumer. */
@RunWith(AndroidJUnit4::class)
class VmosBackendAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = File(context.getExternalFilesDir(null), "vmos-acceptance").apply { mkdirs() }
    private val report = JSONObject().put("passed", false).put("controllerUid", Process.myUid())
        .put("controllerPid", Process.myPid()).put("backend", "vmos_camera_proxy")
    private var width = 1280
    private var height = 720
    private fun save() = File(evidence, if (InstrumentationRegistry.getArguments().getString("finiteOnly") == "true") "finite-acceptance.json" else "acceptance.json").writeText(report.toString(2))
    private fun stage(name: String) { report.put("stage", name); save() }
    private fun sha(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val read = input.read(bytes); if (read < 0) break; hash.update(bytes, 0, read) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun image(name: String, color: Int): File {
        val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return File(context.cacheDir, "vmos-test-$name.png").also { file ->
            try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
    private fun capture(cameraId: String, duration: Long) = CameraFrameProbe(context).capture(cameraId, duration, width, height)
    private fun observe(name: String, frames: CameraFrameProof, video: Boolean = false, finite: Boolean = false) {
        assertTrue("Independent Camera2 must receive at least three actual YUV frames", frames.previewFrames >= 3)
        assertTrue("Independent timestamps must increase", frames.lastTimestamp > frames.firstTimestamp)
        assertEquals(width, frames.previewWidth); assertEquals(height, frames.previewHeight)
        assertEquals(width, frames.jpegWidth); assertEquals(height, frames.jpegHeight)
        assertTrue("Independent JPEG must contain real bytes", frames.jpeg.isNotEmpty())
        assertEquals(frames.previewFrames, frames.centerTimestampsNs.size)
        assertTrue(frames.centerTimestampsNs.zipWithNext().all { (a, b) -> b > a })
        File(evidence, "$name.jpg").writeBytes(frames.jpeg)
        val metrics = frames.metrics().put("centerLuma", JSONArray(frames.centerLuma))
            .put("centerTimestampsNs", JSONArray(frames.centerTimestampsNs))
        if (video) {
            assertTrue("Video observation must cover the fixture end or repeated loop periods",
                frames.lastTimestamp - frames.firstTimestamp >= if (finite) 10_500_000_000L else 6_000_000_000L)
            val stable = mutableListOf<String>()
            var pending = ""; var streak = 0
            for (luma in frames.centerLuma) {
                val color = when (luma) { in 60..110 -> "red"; in 20..55 -> "blue"; else -> "" }
                if (color.isEmpty()) { pending = ""; streak = 0; continue }
                if (pending == color) streak++ else { pending = color; streak = 1 }
                if (streak >= 3 && stable.lastOrNull() != color) stable.add(color)
            }
            metrics.put("stableColors", JSONArray(stable)).put("stableTransitions", (stable.size - 1).coerceAtLeast(0))
            if (finite) {
                val finalStart = frames.lastTimestamp - 2_000_000_000L
                val tail = frames.centerTimestampsNs.zip(frames.centerLuma).filter { it.first >= finalStart }.map { it.second }
                metrics.put("tailSamples", tail.size).put("tailBlueSamples", tail.count { it in 20..55 })
                    .put("tailRedSamples", tail.count { it in 60..110 })
                assertEquals("Finite playback must actually advance red to blue exactly once, without wrapping", listOf("red", "blue"), stable)
                assertTrue("Need at least 30 actual buffers in the final two seconds", tail.size >= 30)
                assertTrue("Finite playback must hold only the final blue frame: $tail", tail.all { it in 20..55 })
            } else assertTrue("Independent actual video must keep changing across repeated loops: $stable", stable.size >= 5)
        }
        report.put(name, metrics); save()
    }
    private fun verifyColor(jpeg: ByteArray, expected: Int) {
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            for (y in listOf(bitmap.height / 4, bitmap.height / 2, bitmap.height * 3 / 4))
                for (x in listOf(bitmap.width / 4, bitmap.width / 2, bitmap.width * 3 / 4)) {
                    val color = bitmap.getPixel(x, y)
                    assertTrue("Independent Camera2 JPEG has wrong selected-source pixels", abs(Color.red(color) - Color.red(expected)) < 35 &&
                        abs(Color.green(color) - Color.green(expected)) < 35 && abs(Color.blue(color) - Color.blue(expected)) < 35)
                }
        } finally { bitmap.recycle() }
    }
    private fun active(controller: VirtualCameraController, file: File, name: String): JSONObject {
        val state = controller.status()
        assertEquals("active", state.getString("phase"))
        val proof = state.getJSONObject("proof")
        assertEquals("vmos_camera_proxy", proof.getString("backend"))
        assertTrue(proof.getBoolean("sourceVerified") && proof.getBoolean("rendererConfigured"))
        assertEquals(sha(file), proof.getString("sourceSha256"))
        assertTrue(proof.getLong("producerFrames") > 0 && proof.getLong("proxyFrames") >= 3)
        assertTrue(proof.getString("generation").isNotBlank())
        assertTrue(proof.getInt("previewFrames") >= 3 && proof.getInt("jpegBytes") > 0)
        assertTrue(proof.getDouble("bestMeanAbsoluteError") in 0.0..25.0)
        report.put(name, state); save()
        return proof
    }
    private fun distance(first: ByteArray, second: ByteArray): Double {
        fun sample(bytes: ByteArray): IntArray {
            val original = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            val small = Bitmap.createScaledBitmap(original, 32, 18, true)
            return try { IntArray(32 * 18).also { small.getPixels(it, 0, 32, 0, 0, 32, 18) } }
            finally { if (small !== original) small.recycle(); original.recycle() }
        }
        val a = sample(first); val b = sample(second)
        var sum = 0L
        for (i in a.indices) for (shift in listOf(0, 8, 16)) sum += abs(((a[i] ushr shift) and 255) - ((b[i] ushr shift) and 255))
        return sum.toDouble() / (a.size * 3)
    }

    private fun prepareGuest(): String {
        val args = InstrumentationRegistry.getArguments()
        assertEquals("This test must explicitly target the inspected guest", "true", args.getString("vmosBackend"))
        assertEquals(28, Build.VERSION.SDK_INT); assertEquals("vphw90", Build.HARDWARE)
        assertTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        assertNotEquals(0, Process.myUid()); assertNotEquals(1000, Process.myUid())
        assertNotEquals(Looper.getMainLooper(), Looper.myLooper())
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant com.virtualcamerapro android.permission.CAMERA"))
                .bufferedReader().use { it.readText() }
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        instrumentation.waitForIdleSync()
        val cameraId = args.getString("cameraId", "0")
        require(cameraId.matches(Regex("[A-Za-z0-9_]{1,32}")))
        width = args.getString("width", "1280").toInt()
        height = args.getString("height", "720").toInt()
        OutputSettings(width = width, height = height, cameraId = cameraId)
        report.put("cameraId", cameraId).put("width", width).put("height", height)
        return cameraId
    }
    private fun videoFixture(finite: Boolean = false) = File(context.cacheDir, if (finite) "vmos-test-red-blue-8s.mp4" else "vmos-test-red-blue-2s.mp4").apply {
        instrumentation.context.assets.open(if (finite) "red-blue-8s.mp4" else "red-blue-2s.mp4").use { input -> outputStream().use { input.copyTo(it) } }
    }

    @Test fun imageSwitchLoopingVideoAndDeactivationUseActualGuestCamera() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Finite-only execution uses the separate finite playback test", args.getString("finiteOnly") != "true")
        val cameraId = prepareGuest()
        val leaveActive = args.getString("leaveActive", "")
        require(leaveActive in listOf("", "image", "video")) { "leaveActive must be image, video or omitted" }
        var retained = false
        val red = image("red", Color.RED); val blue = image("blue", Color.BLUE)
        val video = videoFixture()
        VirtualCameraController(context).use { controller ->
            try {
                stage("readiness")
                val support = controller.support(); report.put("support", support); save()
                assertTrue("Guarded VMOS bridge setup must complete before this acceptance: $support", support.getBoolean("supported"))
                assertEquals("vmos_camera_proxy", support.getString("backend"))
                controller.deactivate()
                controller.update(OutputSettings(width = width, height = height, cameraId = cameraId, scaleMode = "fill", loop = true))
                stage("baseline")
                val baseline = capture(cameraId, 300)
                observe("baseline", baseline)
                stage("red-activation")
                controller.select(Uri.fromFile(red).toString(), "image")
                assertTrue(controller.activate())
                val redGeneration = active(controller, red, "red-status").getString("generation")
                // CameraFrameProbe runs in the ordinary controller process, with
                // no access to the broker's private source files or EGL renderer.
                val actualRed = capture(cameraId, 400)
                observe("independent-red", actualRed); verifyColor(actualRed.jpeg, Color.RED)
                stage("blue-live-switch")
                controller.select(Uri.fromFile(blue).toString(), "image")
                val blueGeneration = active(controller, blue, "blue-status").getString("generation")
                assertNotEquals("A live source change must create a new native generation", redGeneration, blueGeneration)
                val actualBlue = capture(cameraId, 400)
                observe("independent-blue", actualBlue); verifyColor(actualBlue.jpeg, Color.BLUE)
                stage("video-live-switch")
                controller.select(Uri.fromFile(video).toString(), "video")
                val videoGeneration = active(controller, video, "video-status").getString("generation")
                assertNotEquals(blueGeneration, videoGeneration)
                val actualVideo = capture(cameraId, 6500)
                observe("independent-video", actualVideo, true)
                assertEquals(videoGeneration, active(controller, video, "video-after-observation").getString("generation"))
                stage("deactivation")
                controller.deactivate()
                assertEquals("stopped", controller.status().getString("phase"))
                val restored = capture(cameraId, 400)
                observe("restored", restored)
                val baselineError = distance(restored.jpeg, baseline.jpeg)
                val redError = distance(restored.jpeg, actualRed.jpeg)
                val blueError = distance(restored.jpeg, actualBlue.jpeg)
                report.put("restoration", JSONObject().put("baselineMeanAbsoluteError", baselineError).put("redMeanAbsoluteError", redError).put("blueMeanAbsoluteError", blueError)); save()
                assertTrue("Deactivation must restore actual original camera pixels", baselineError < 25 && baselineError + 15 < minOf(redError, blueError))
                if (leaveActive.isNotEmpty()) {
                    stage("retaining-verified-source-for-external-client")
                    val fixture = if (leaveActive == "image") red else video
                    controller.select(Uri.fromFile(fixture).toString(), leaveActive)
                    assertTrue(controller.activate())
                    active(controller, fixture, "retained-status")
                    // The broker owns its source copy and native foreground
                    // service; closing this Binder client cannot own its lease.
                    retained = true
                    report.put("leftActive", true).put("retainedType", leaveActive)
                }
                report.put("passed", true); stage("complete")
            } catch (error: Throwable) { report.put("failure", error.toString()); save(); throw error }
            finally { if (!retained) runCatching { controller.deactivate() }; red.delete(); blue.delete(); video.delete() }
        }
    }

    @Test fun finiteVideoHoldsItsActualFinalFrame() {
        assumeTrue("Opt in to finite playback with finiteOnly=true", InstrumentationRegistry.getArguments().getString("finiteOnly") == "true")
        val cameraId = prepareGuest()
        val fixture = videoFixture(finite = true)
        VirtualCameraController(context).use { controller ->
            try {
                stage("finite-readiness")
                val support = controller.support(); report.put("support", support); save()
                assertTrue("Guarded VMOS setup must complete first: $support", support.getBoolean("supported"))
                assertEquals("vmos_camera_proxy", support.getString("backend"))
                controller.deactivate()
                controller.update(OutputSettings(width = width, height = height, cameraId = cameraId, loop = false, scaleMode = "fill"))
                controller.select(Uri.fromFile(fixture).toString(), "video")
                stage("finite-activation")
                assertTrue(controller.activate())
                val generation = active(controller, fixture, "finite-status").getString("generation")
                stage("finite-independent-observation")
                observe("finite-video", capture(cameraId, 11000), video = true, finite = true)
                assertEquals(generation, active(controller, fixture, "finite-after-observation").getString("generation"))
                controller.deactivate(); assertEquals("stopped", controller.status().getString("phase"))
                report.put("passed", true); stage("complete")
            } catch (error: Throwable) { report.put("failure", error.toString()); save(); throw error }
            finally { runCatching { controller.deactivate() }; fixture.delete() }
        }
    }
}
