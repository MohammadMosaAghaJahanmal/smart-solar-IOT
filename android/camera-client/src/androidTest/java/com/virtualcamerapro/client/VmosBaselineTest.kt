package com.virtualcamerapro.client

import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.probe.CameraFrameProbe
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Separates original-guest YUV and JPEG failures before installing a camera bridge. */
@RunWith(AndroidJUnit4::class)
class VmosBaselineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun requireGuest() {
        check(Build.VERSION.SDK_INT == 28 && Build.HARDWARE == "vphw90")
    }
    private fun single(format: Int): JSONObject {
        requireGuest()
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = InstrumentationRegistry.getArguments().getString("cameraId") ?: "0"
        val map = manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val size = map.getOutputSizes(format).minBy { abs(it.width * it.height - 640 * 480) }
        val thread = HandlerThread("VmosBaseline").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val device = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        val reader = ImageReader.newInstance(size.width, size.height, format, 3)
        var count = 0
        var first = 0L
        var last = 0L
        var bytes = 0
        reader.setOnImageAvailableListener({ r ->
            try { r.acquireNextImage()?.use { image ->
                assertTrue("Actual image timestamp must increase", image.timestamp > last)
                if (first == 0L) first = image.timestamp
                last = image.timestamp
                assertTrue(image.planes.all { it.buffer.remaining() > 0 })
                if (format == ImageFormat.JPEG) {
                    val data = ByteArray(image.planes[0].buffer.remaining())
                    image.planes[0].buffer.get(data)
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    assertNotNull("Actual JPEG must decode", bitmap)
                    assertEquals(size.width, bitmap.width); assertEquals(size.height, bitmap.height)
                    bitmap.recycle(); bytes = data.size
                } else { assertEquals(3, image.planes.size); bytes = image.planes.sumOf { it.buffer.remaining() } }
                count++
                if (format == ImageFormat.JPEG || count >= 3) done.countDown()
            } } catch (error: Throwable) { failure.compareAndSet(null, error); done.countDown() }
        }, handler)
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device.set(camera)
                    try { camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            session.set(s)
                            try {
                                val request = camera.createCaptureRequest(if (format == ImageFormat.JPEG) CameraDevice.TEMPLATE_STILL_CAPTURE else CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build()
                                val callback = object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
                                        failure.set(IllegalStateException("Capture failed ${f.reason}")); done.countDown()
                                    }
                                }
                                if (format == ImageFormat.JPEG) s.capture(request, callback, handler) else s.setRepeatingRequest(request, callback, handler)
                            } catch (e: Throwable) { failure.set(e); done.countDown() }
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) { failure.set(IllegalStateException("Single stream configuration failed")); done.countDown() }
                    }, handler) } catch (e: Throwable) { failure.set(e); done.countDown() }
                }
                override fun onDisconnected(c: CameraDevice) { failure.set(IllegalStateException("Original camera disconnected")); done.countDown() }
                override fun onError(c: CameraDevice, code: Int) { failure.set(IllegalStateException("Original camera open error $code")); done.countDown() }
            }, handler)
            assertTrue("Original single-stream camera timed out", done.await(12, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Original single-stream camera failed", it) }
            assertTrue(count >= if (format == ImageFormat.JPEG) 1 else 3)
            if (format != ImageFormat.JPEG) assertTrue(last > first)
            return JSONObject().put("format", format).put("width", size.width).put("height", size.height)
                .put("frames", count).put("firstTimestampNs", first).put("lastTimestampNs", last).put("bytes", bytes)
        } finally {
            runCatching { session.get()?.stopRepeating() }
            session.get()?.close(); device.get()?.close()
            val drained = CountDownLatch(1); handler.post { drained.countDown() }; drained.await(1, TimeUnit.SECONDS)
            reader.close(); thread.quitSafely()
        }
    }
    @Test fun originalYuvOnly640() { File(context.filesDir, "baseline-yuv.json").writeText(single(ImageFormat.YUV_420_888).toString(2)) }
    @Test fun originalJpegOnly640() { File(context.filesDir, "baseline-jpeg.json").writeText(single(ImageFormat.JPEG).toString(2)) }
    @Test fun originalCombined640() {
        requireGuest()
        val proof = CameraFrameProbe(context).capture("0", 300, 640, 480)
        assertTrue(proof.previewFrames >= 3 && proof.lastTimestamp > proof.firstTimestamp && proof.jpeg.isNotEmpty())
        File(context.filesDir, "baseline-combined.json").writeText(proof.metrics().toString(2))
    }
    @Test fun nativePrivateDirectoryOwnership() {
        requireGuest()
        val file = context.filesDir
        val info = Os.stat(file.absolutePath)
        val process = ProcessBuilder("/data/local/tmp/vcam-owner-probe", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(4, TimeUnit.SECONDS))
        File(file, "native-ownership.json").writeText(JSONObject().put("appUid", android.os.Process.myUid())
            .put("javaStatUid", info.st_uid).put("javaStatMode", info.st_mode).put("nativeOutput", output).toString(2))
        assertEquals(0, process.exitValue())
    }
}
