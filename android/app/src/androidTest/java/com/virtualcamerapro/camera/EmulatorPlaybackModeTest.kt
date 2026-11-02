package com.virtualcamerapro.camera

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
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

/** Real controller and public Camera2 evidence for finite and looping playback. */
@RunWith(AndroidJUnit4::class)
class EmulatorPlaybackModeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val evidence = File(context.getExternalFilesDir(null), "emulator-playback-modes").apply { mkdirs() }
    private val report = JSONObject().put("passed", false).put("controllerUid", Process.myUid())

    private fun save() = File(evidence, "playback-modes.json").writeText(report.toString(2))
    private fun stage(name: String) { report.put("stage", name); save() }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

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

    private fun sourceHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun active(controller: VirtualCameraController, expectedHash: String, name: String): JSONObject {
        val status = controller.status()
        File(evidence, "$name-status.json").writeText(status.toString(2))
        assertEquals("active", status.getString("phase"))
        val proof = status.getJSONObject("proof")
        assertEquals("android_emulator_environment", proof.getString("backend"))
        assertTrue("The broker must verify actual camera source pixels", proof.getBoolean("sourceVerified"))
        assertTrue(proof.getBoolean("rendererConfigured"))
        assertEquals(expectedHash, proof.getString("sourceSha256"))
        assertTrue(proof.getString("hostGeneration").isNotBlank())
        assertTrue(proof.getInt("previewFrames") >= 3 && proof.getInt("jpegBytes") > 0)
        report.put("$name-status", status); save()
        return proof
    }

    private fun color(luma: Int): String = when (luma) {
        in 60..110 -> "red"
        in 20..55 -> "blue"
        else -> "other"
    }

    private fun observed(name: String, frames: CameraFrameProof): JSONObject {
        File(evidence, "$name.jpg").writeBytes(frames.jpeg)
        val metrics = frames.metrics().put("centerLuma", JSONArray(frames.centerLuma))
            .put("centerTimestampsNs", JSONArray(frames.centerTimestampsNs))
        File(evidence, "$name.json").writeText(metrics.toString(2))
        report.put(name, metrics); save()
        assertEquals("Every luma sample needs its actual sensor timestamp", frames.centerLuma.size, frames.centerTimestampsNs.size)
        assertEquals(frames.previewFrames, frames.centerTimestampsNs.size)
        assertTrue("Camera timestamps must strictly increase", frames.centerTimestampsNs.zipWithNext().all { (a, b) -> b > a })
        assertTrue("Expected a full video observation", frames.lastTimestamp - frames.firstTimestamp >= 6000000000L)
        assertEquals(1280, frames.previewWidth); assertEquals(720, frames.previewHeight)
        assertEquals(1280, frames.jpegWidth); assertEquals(720, frames.jpegHeight)
        return metrics
    }

    @Test fun finiteVideoHoldsFinalFrameAndLoopingVideoKeepsChanging() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in only on the configured Android emulator backend", args.getString("emulatorBackend") == "true")
        assertNotEquals("Test must run as the ordinary controller UID", 1000, Process.myUid())
        assertNotEquals("Controller Binder operations must run off the main thread", Looper.getMainLooper(), Looper.myLooper())
        assertEquals("This acceptance never targets a physical device", "1", shell("getprop ro.kernel.qemu").trim())
        val cameraId = args.getString("cameraId", "10")
        require(cameraId.matches(Regex("[A-Za-z0-9_]{1,32}"))) { "Invalid configured camera ID" }
        shell("pm grant com.virtualcamerapro android.permission.CAMERA")
        val fixture = File(context.cacheDir, "playback-mode-red-blue-2s.mp4").apply {
            instrumentation.context.assets.open("red-blue-2s.mp4").use { input -> outputStream().use { input.copyTo(it) } }
        }
        val expectedHash = sourceHash(fixture)
        VirtualCameraController(context).use { controller ->
            try {
                stage("readiness")
                val support = controller.support()
                report.put("support", support); save()
                assertTrue("Emulator backend is not ready: $support", support.getBoolean("supported"))
                assertEquals("android_emulator_environment", support.getString("backend"))
                controller.deactivate()
                foregroundController()
                val settings = OutputSettings(width = 1280, height = 720, loop = false, cameraId = cameraId)
                controller.update(settings)
                controller.select(Uri.fromFile(fixture).toString(), "video")
                stage("finite-activation")
                assertTrue(controller.activate())
                val finiteProof = active(controller, expectedHash, "finite")
                val finite = CameraFrameProbe(context).capture(cameraId, sampleDurationMs = 6500)
                val finiteMetrics = observed("finite", finite)
                val initialColors = finite.centerLuma.map(::color)
                val finalStartNs = finite.lastTimestamp - 2000000000L
                val finalColors = finite.centerTimestampsNs.zip(finite.centerLuma)
                    .filter { (timestamp, _) -> timestamp >= finalStartNs }.map { (_, luma) -> color(luma) }
                finiteMetrics.put("finalWindowStartNs", finalStartNs).put("finalWindowSamples", finalColors.size)
                    .put("finalBlueSamples", finalColors.count { it == "blue" })
                    .put("finalRedSamples", finalColors.count { it == "red" })
                File(evidence, "finite.json").writeText(finiteMetrics.toString(2)); save()
                assertTrue("Finite playback must initially deliver both fixture colors", "red" in initialColors && "blue" in initialColors)
                assertTrue("Need at least 30 actual blue buffers in the final two seconds", finalColors.size >= 30)
                assertTrue("Finite playback wrapped or lost its final blue frame: $finalColors", finalColors.all { it == "blue" })
                assertEquals("End-frame hold must preserve host generation", finiteProof.getString("hostGeneration"),
                    active(controller, expectedHash, "finite-after-observation").getString("hostGeneration"))
                controller.deactivate()
                assertEquals("stopped", controller.status().getString("phase"))

                foregroundController()
                controller.update(settings.copy(loop = true))
                stage("looping-activation")
                assertTrue(controller.activate())
                active(controller, expectedHash, "looping")
                val looping = CameraFrameProbe(context).capture(cameraId, sampleDurationMs = 6500)
                val loopingMetrics = observed("looping", looping)
                val stableColors = mutableListOf<String>()
                var pending = "other"
                var streak = 0
                for (luma in looping.centerLuma) {
                    val next = color(luma)
                    if (next == "other") { pending = "other"; streak = 0; continue }
                    if (next == pending) streak++ else { pending = next; streak = 1 }
                    if (streak >= 3 && stableColors.lastOrNull() != pending) stableColors.add(pending)
                }
                val transitions = (stableColors.size - 1).coerceAtLeast(0)
                loopingMetrics.put("stableColors", JSONArray(stableColors)).put("stableTransitions", transitions)
                File(evidence, "looping.json").writeText(loopingMetrics.toString(2)); save()
                assertTrue("Looping playback must keep changing across wraps: $stableColors", transitions >= 4)
                active(controller, expectedHash, "looping-after-observation")
                controller.deactivate()
                assertEquals("stopped", controller.status().getString("phase"))
                report.put("passed", true); stage("complete")
            } catch (error: Throwable) {
                report.put("failure", error.toString()); save(); throw error
            } finally { runCatching { controller.deactivate() }; fixture.delete() }
        }
    }
}
