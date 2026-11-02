package com.virtualcamerapro.client

import android.Manifest
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Independent Camera2 consumer; transport acknowledgements are never frame proof. */
@RunWith(AndroidJUnit4::class)
class OffworldCameraTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private data class Sample(
        val elapsedMs: Long, val timestampNs: Long,
        val y: Int, val u: Int, val v: Int,
        val red: Int, val green: Int, val blue: Int
    ) {
        val color: String get() = when {
            red >= 170 && green <= 100 && blue <= 100 -> "red"
            blue >= 170 && green <= 100 && red <= 100 -> "blue"
            else -> "other"
        }
        fun json() = JSONObject().put("elapsedMs", elapsedMs).put("timestampNs", timestampNs)
            .put("y", y).put("u", u).put("v", v)
            .put("red", red).put("green", green).put("blue", blue).put("color", color)
    }

    private fun center(image: Image, elapsedMs: Long): Sample {
        require(image.planes.size == 3) { "Expected three YUV planes" }
        val crop = image.cropRect
        val x = crop.left + crop.width() / 2
        val y = crop.top + crop.height() / 2
        fun component(plane: Image.Plane, px: Int, py: Int): Int {
            val buffer = plane.buffer
            val index = buffer.position() + py * plane.rowStride + px * plane.pixelStride
            require(index < buffer.limit()) { "YUV center is outside plane bounds" }
            return buffer.get(index).toInt() and 255
        }
        val luma = component(image.planes[0], x, y)
        val u = component(image.planes[1], x / 2, y / 2)
        val v = component(image.planes[2], x / 2, y / 2)
        val c = (luma - 16).coerceAtLeast(0)
        val d = u - 128
        val e = v - 128
        fun channel(value: Int) = ((value + 128) shr 8).coerceIn(0, 255)
        return Sample(elapsedMs, image.timestamp, luma, u, v,
            channel(298 * c + 409 * e), channel(298 * c - 100 * d - 208 * e), channel(298 * c + 516 * d))
    }

    @Suppress("MissingPermission", "DEPRECATION")
    @Test fun runtimeVideoReachesIndependentCamera2AcrossLoops() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in only on an isolated emulator with runtime video injection",
            args.getString("offworld") == "true" || args.getString("emulatorRuntime") == "true")
        val transport = args.getString("transport") ?: "offworld"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cameraId = requireNotNull(args.getString("cameraId")) { "Pass the actual injected cameraId" }
        val manager = context.getSystemService(CameraManager::class.java)
        require(cameraId in manager.cameraIdList) { "Camera $cameraId is absent" }
        val map = requireNotNull(manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val size = requireNotNull(map.getOutputSizes(ImageFormat.YUV_420_888))
            .minByOrNull { abs(it.width.toLong() * it.height - 1280L * 720) }
            ?: error("Camera has no YUV output")
        val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 4)
        val worker = HandlerThread("IndependentOffworldConsumer").apply { start() }
        val handler = Handler(worker.looper)
        val device = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        val failure = AtomicReference<Throwable?>()
        val closing = AtomicBoolean(false)
        val finished = CountDownLatch(1)
        val samples = mutableListOf<Sample>() // Owned exclusively by the camera callback thread.
        val openedMs = SystemClock.elapsedRealtime()
        var firstSourceMs = -1L
        var firstSourceTimestamp = 0L
        var lastTimestamp = 0L
        fun fail(error: Throwable) { failure.compareAndSet(null, error); finished.countDown() }
        reader.setOnImageAvailableListener({ output ->
            try {
                output.acquireNextImage()?.use { image ->
                    val elapsed = SystemClock.elapsedRealtime() - openedMs
                    check(image.timestamp > lastTimestamp) { "Camera timestamps did not increase" }
                    lastTimestamp = image.timestamp
                    val sample = center(image, elapsed)
                    samples.add(sample)
                    if (firstSourceMs < 0 && sample.color != "other") {
                        firstSourceMs = elapsed
                        firstSourceTimestamp = sample.timestampNs
                    }
                    if (firstSourceMs >= 0 && elapsed - firstSourceMs >= 6000) finished.countDown()
                }
            } catch (error: Exception) { fail(error) }
        }, handler)
        try {
            // The host runner must prepare the selected runtime transport before this
            // consumer opens. Offworld queues Load; environment injection may apply live.
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closing.get()) { camera.close(); return }
                    device.set(camera)
                    try {
                        camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (closing.get()) { configured.close(); return }
                                session.set(configured)
                                try {
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        .apply { addTarget(reader.surface) }.build()
                                    configured.setRepeatingRequest(request, null, handler)
                                } catch (error: Exception) { fail(error) }
                            }
                            override fun onConfigureFailed(configured: CameraCaptureSession) {
                                fail(IllegalStateException("Camera rejected independent YUV stream"))
                            }
                        }, handler)
                    } catch (error: Exception) { fail(error) }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (!closing.get()) fail(IllegalStateException("Camera disconnected"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (!closing.get()) fail(IllegalStateException("Camera open error $error"))
                }
            }, handler)
            val completed = finished.await(18000, TimeUnit.MILLISECONDS)
            val snapshot = FutureTask {
                val observed = samples.toList()
                val source = observed.filter { firstSourceMs >= 0 && it.elapsedMs >= firstSourceMs }
                // Three consecutive frames confirm a new color; this rejects single noisy samples.
                var pending = "other"
                var streak = 0
                val colors = mutableListOf<String>()
                for (sample in source) {
                    if (sample.color == "other") { pending = "other"; streak = 0; continue }
                    if (sample.color == pending) streak++ else { pending = sample.color; streak = 1 }
                    if (streak >= 3 && colors.lastOrNull() != pending) colors.add(pending)
                }
                val transitions = (colors.size - 1).coerceAtLeast(0)
                val sensorSpan = source.lastOrNull()?.let { it.timestampNs - firstSourceTimestamp } ?: 0L
                val proof = JSONObject().put("cameraId", cameraId).put("transport", transport)
                    .put("width", size.width).put("height", size.height)
                    .put("completedObservation", completed).put("firstSourceElapsedMs", firstSourceMs)
                    .put("sourceFrameCount", source.size).put("sourceSensorSpanNs", sensorSpan)
                    .put("stableColors", JSONArray(colors)).put("colorTransitions", transitions)
                    .put("failure", failure.get()?.toString() ?: JSONObject.NULL)
                    .put("samples", JSONArray(observed.map { it.json() }))
                File(requireNotNull(context.getExternalFilesDir(null)), "offworld-camera-proof.json").writeText(proof.toString(2))
                Triple(source.size, sensorSpan, transitions)
            }
            check(handler.post(snapshot)) { "Camera callback thread stopped" }
            val (frameCount, sensorSpan, transitions) = snapshot.get(2, TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError("Independent camera failed", it) }
            assertTrue("No full 6-second source observation; inspect offworld-camera-proof.json", completed)
            assertTrue("Expected actual red/blue source buffers", firstSourceMs >= 0 && frameCount >= 20)
            assertTrue("Sensor timestamps must span the observation", sensorSpan >= 5_500_000_000L)
            assertTrue("Expected >=4 color transitions across loops, got $transitions; inspect offworld-camera-proof.json", transitions >= 4)
        } finally {
            closing.set(true)
            runCatching { session.get()?.stopRepeating() }
            runCatching { session.get()?.abortCaptures() }
            session.getAndSet(null)?.close()
            device.getAndSet(null)?.close()
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }
            drained.await(2, TimeUnit.SECONDS)
            reader.close()
            worker.quitSafely()
        }
    }
}
