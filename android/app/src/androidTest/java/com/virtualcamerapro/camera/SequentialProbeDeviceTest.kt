package com.virtualcamerapro.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraInventory
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly opted-in consumer test. Both strategies obtain JPEG bytes from Camera2. */
@RunWith(AndroidJUnit4::class)
class SequentialProbeDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun combinedAndSequentialCaptureActualYuvAndHalJpegForEveryUsableCamera() {
        assumeTrue("Enable actual Camera2 stream probing explicitly with sequentialProbe=true",
            InstrumentationRegistry.getArguments().getString("sequentialProbe") == "true")
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue("The real camera consumer must use the ordinary app UID", Process.myUid() >= 10000)
        assertEquals("Camera permission must already be granted", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        instrumentation.waitForIdleSync()

        val evidence = File(requireNotNull(context.getExternalFilesDir(null)), "sequential-probe").apply { mkdirs() }
        val observations = JSONArray()
        val report = JSONObject().put("passed", false).put("consumerUid", Process.myUid())
            .put("jpegEvidence", "Actual Camera2 JPEG ImageReader output; no application JPEG encoder")
            .put("observations", observations)
        fun save() = File(evidence, "acceptance.json").writeText(report.toString(2))
        save()
        try {
            val inventory = CameraInventory.query(context)
            report.put("inventory", inventory)
            assertTrue("The real provider must expose at least one camera", inventory.length() > 0)
            val ids = mutableSetOf<String>()
            for (index in 0 until inventory.length()) {
                val camera = inventory.getJSONObject(index)
                val cameraId = camera.getString("id")
                val resolutions = camera.getJSONArray("resolutions")
                if (resolutions.length() == 0) {
                    observations.put(JSONObject().put("cameraId", cameraId).put("skipped", true)
                        .put("reason", "No advertised common YUV/JPEG output size"))
                    save()
                    continue
                }
                ids.add(cameraId)
                val sizes = (0 until resolutions.length()).map { resolutions.getJSONObject(it) }
                val size = sizes.filter { it.getInt("width").toLong() * it.getInt("height") <= 1280L * 720 }
                    .maxByOrNull { it.getInt("width").toLong() * it.getInt("height") }
                    ?: sizes.minBy { it.getInt("width").toLong() * it.getInt("height") }
                val width = size.getInt("width"); val height = size.getInt("height")
                for (strategy in listOf("combined", "sequential")) {
                    report.put("stage", "$cameraId:$strategy"); save()
                    val started = SystemClock.elapsedRealtime()
                    val captured = CameraFrameProbe(context).capture(cameraId, 350, width, height, strategy, 12_350)
                    assertEquals(cameraId, captured.cameraId)
                    assertEquals(strategy, captured.streamStrategy)
                    assertEquals(strategy, captured.metrics().getString("streamStrategy"))
                    assertTrue("Actual preview frames must be delivered", captured.previewFrames >= 3)
                    assertTrue(captured.firstTimestamp > 0 && captured.lastTimestamp > captured.firstTimestamp)
                    assertEquals(captured.previewFrames, captured.centerTimestampsNs.size)
                    assertTrue(captured.centerTimestampsNs.zipWithNext().all { (first, next) -> next > first })
                    assertEquals(width, captured.previewWidth); assertEquals(height, captured.previewHeight)
                    assertEquals(width, captured.jpegWidth); assertEquals(height, captured.jpegHeight)
                    assertTrue("The HAL must return real JPEG bytes", captured.jpeg.isNotEmpty())
                    val decoded = requireNotNull(BitmapFactory.decodeByteArray(captured.jpeg, 0, captured.jpeg.size))
                    try { assertEquals(width, decoded.width); assertEquals(height, decoded.height) }
                    finally { decoded.recycle() }
                    val metrics = captured.metrics().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("facing", camera.getString("facing")).put("passed", true)
                    observations.put(metrics)
                    // Use only a test index in filenames; Camera2 IDs are opaque.
                    File(evidence, "camera-$index-$strategy.jpg").writeBytes(captured.jpeg)
                    save()
                }
            }
            assertTrue("At least one camera must complete both real stream strategies", ids.isNotEmpty())
            report.put("verifiedCameraIds", JSONArray(ids.toList())).put("passed", true).put("stage", "complete")
        } catch (error: Throwable) {
            report.put("failure", "${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally { save() }
    }
}
