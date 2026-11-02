package com.virtualcamerapro.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Keeps one real Camera2 connection open across live controller pan/zoom updates. */
@RunWith(AndroidJUnit4::class)
class VmosLiveTransformTest {
    @Suppress("MissingPermission", "DEPRECATION")
    @Test fun panAndZoomChangeActualOpenCameraWithoutReopeningIt() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals("true", args.getString("vmosBackend"))
        assertEquals(28, Build.VERSION.SDK_INT); assertEquals("vphw90", Build.HARDWARE)
        assertNotEquals(0, Process.myUid()); assertNotEquals(1000, Process.myUid())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val id = args.getString("cameraId", "0")
        val directory = File(context.getExternalFilesDir(null), "vmos-live-transform").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("cameraId", id).put("controllerUid", Process.myUid())
        val observations = JSONArray(); report.put("observations", observations)
        fun save() = File(directory, "live-transform.json").writeText(report.toString(2))
        val source = File(context.cacheDir, "live-transform-quadrants.png")
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        for (y in 0 until 180) for (x in 0 until 320) bitmap.setPixel(x, y, if (y < 90) { if (x < 160) Color.RED else Color.GREEN } else { if (x < 160) Color.BLUE else Color.WHITE })
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val worker = HandlerThread("HeldOpenTransformConsumer").apply { start() }
        val handler = Handler(worker.looper)
        val reader = ImageReader.newInstance(1280, 720, ImageFormat.YUV_420_888, 3)
        val ready = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closing = AtomicBoolean()
        val frames = LinkedBlockingQueue<Pair<Int, Long>>()
        var camera: CameraDevice? = null; var session: CameraCaptureSession? = null
        var opens = 0; var configurations = 0
        reader.setOnImageAvailableListener({ imageReader ->
            try { imageReader.acquireLatestImage()?.use { image ->
                fun component(index: Int, x: Int, y: Int): Int {
                    val plane = image.planes[index]
                    return plane.buffer.get(plane.buffer.position() + y * plane.rowStride + x * plane.pixelStride).toInt() and 255
                }
                val y = component(0, image.width / 2, image.height / 2) - 16
                val u = component(1, image.width / 4, image.height / 4) - 128
                val v = component(2, image.width / 4, image.height / 4) - 128
                val color = Color.rgb(((298 * y + 409 * v + 128) shr 8).coerceIn(0, 255),
                    ((298 * y - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255), ((298 * y + 516 * u + 128) shr 8).coerceIn(0, 255))
                frames.offer(color to image.timestamp)
                while (frames.size > 20) frames.poll()
            } } catch (error: Throwable) { failure.compareAndSet(null, error); ready.countDown() }
        }, handler)
        VirtualCameraController(context).use { controller ->
            try {
                val support = controller.support(); report.put("support", support); save()
                assertTrue("The configured guest bridge must be ready: $support", support.getBoolean("supported"))
                controller.deactivate(); controller.update(OutputSettings(cameraId = id, scaleMode = "fill"))
                controller.select(Uri.fromFile(source).toString(), "image"); assertTrue(controller.activate())
                val original = controller.status().getJSONObject("proof")
                val generation = original.getString("generation")
                val proxyPid = original.getInt("proxyPid")
                context.getSystemService(CameraManager::class.java).openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (closing.get()) { device.close(); return }; opens++; camera = device
                        try { device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (closing.get()) { configured.close(); return }; configurations++; session = configured
                                try { configured.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build(), null, handler); ready.countDown() }
                                catch (error: Throwable) { failure.set(error); ready.countDown() }
                            }
                            override fun onConfigureFailed(configured: CameraCaptureSession) { failure.set(IllegalStateException("Actual YUV consumer configuration failed")); ready.countDown() }
                        }, handler) } catch (error: Throwable) { failure.set(error); ready.countDown() }
                    }
                    override fun onDisconnected(device: CameraDevice) { device.close(); failure.set(IllegalStateException("Held-open camera disconnected")); ready.countDown() }
                    override fun onError(device: CameraDevice, error: Int) { device.close(); failure.set(IllegalStateException("Held-open camera error $error")); ready.countDown() }
                }, handler)
                assertTrue(ready.await(10, TimeUnit.SECONDS)); failure.get()?.let { throw it }
                requireNotNull(frames.poll(5, TimeUnit.SECONDS)) { "The held-open camera delivered no actual buffers" }
                var lastTimestamp = 0L; var lastSequence = -1L
                for ((expected, value) in listOf(Color.RED to OutputSettings(cameraId = id, scaleMode = "fill", zoom = 2.0, panX = 1.0, panY = 1.0),
                    Color.GREEN to OutputSettings(cameraId = id, scaleMode = "fill", zoom = 2.0, panX = -1.0, panY = 1.0),
                    Color.BLUE to OutputSettings(cameraId = id, scaleMode = "fill", zoom = 2.0, panX = 1.0, panY = -1.0),
                    Color.WHITE to OutputSettings(cameraId = id, scaleMode = "fill", zoom = 2.0, panX = -1.0, panY = -1.0))) {
                    frames.clear()
                    val started = SystemClock.elapsedRealtime(); controller.update(value)
                    val timeout = SystemClock.elapsedRealtime() + 4000
                    var matched = 0; var actual = 0; var observedTimestamp = 0L
                    while (matched < 3 && SystemClock.elapsedRealtime() < timeout) {
                        failure.get()?.let { throw it }
                        val frame = frames.poll(100, TimeUnit.MILLISECONDS) ?: continue
                        assertTrue("Sensor timestamps must increase across live transforms", frame.second > lastTimestamp); lastTimestamp = frame.second
                        if (abs(Color.red(frame.first) - Color.red(expected)) < 25 && abs(Color.green(frame.first) - Color.green(expected)) < 25 && abs(Color.blue(frame.first) - Color.blue(expected)) < 25) {
                            matched++; actual = frame.first; observedTimestamp = frame.second
                        } else matched = 0
                    }
                    assertTrue("Three actual open-camera buffers must reflect the live transform", matched >= 3)
                    val status = controller.status(); val proof = status.getJSONObject("proof")
                    assertEquals("active", status.getString("phase")); assertEquals(generation, proof.getString("generation")); assertEquals(proxyPid, proof.getInt("proxyPid"))
                    assertTrue(proof.getLong("liveTransformSequence") > lastSequence); lastSequence = proof.getLong("liveTransformSequence")
                    observations.put(JSONObject().put("zoom", value.zoom).put("panX", value.panX).put("panY", value.panY).put("actualRgb", Integer.toHexString(actual))
                        .put("sensorTimestampNs", observedTimestamp).put("updateToObservedMs", SystemClock.elapsedRealtime() - started).put("proof", proof)); save()
                }
                assertEquals("The same CameraDevice must remain open", 1, opens)
                assertEquals("The same CameraCaptureSession must remain configured", 1, configurations)
                report.put("passed", true).put("cameraOpens", opens).put("sessionConfigurations", configurations)
            } catch (error: Throwable) { report.put("failure", error.toString()); throw error }
            finally {
                closing.set(true)
                val closed = CountDownLatch(1)
                handler.post { try { runCatching { session?.stopRepeating() }; session?.close(); camera?.close(); reader.close() } finally { closed.countDown() } }
                closed.await(3, TimeUnit.SECONDS)
                runCatching { controller.deactivate() }; source.delete(); save(); worker.quitSafely()
            }
        }
    }
}
