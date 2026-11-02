package com.virtualcamerapro.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.android.camera.util.JpegUtilNative
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Records real public Camera2 plane layout; no NV21 or tightly packed assumption. */
@RunWith(AndroidJUnit4::class)
class VmosYuvColorTest {
    @Suppress("MissingPermission", "DEPRECATION")
    @Test fun actualYuvPlanesAndChroma() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals(28, Build.VERSION.SDK_INT); assertEquals("vphw90", Build.HARDWARE)
        assertTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        assertNotEquals(0, Process.myUid()); assertNotEquals(1000, Process.myUid())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val cameraId = args.getString("cameraId", "1")
        val width = args.getString("width", "1920").toInt()
        val height = args.getString("height", "1088").toInt()
        require(cameraId.matches(Regex("[0-9]{1,2}")) && width in 2..4096 && height in 2..4096 && width % 2 == 0 && height % 2 == 0)
        val directory = File(context.getExternalFilesDir(null), "yuv-color/camera-$cameraId-${width}x$height").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("cameraId", cameraId).put("consumerUid", Process.myUid())
            .put("requestedWidth", width).put("requestedHeight", height)
        val manager = context.getSystemService(CameraManager::class.java)
        val formats = requireNotNull(manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val sizes = requireNotNull(formats.getOutputSizes(ImageFormat.YUV_420_888))
        report.put("advertisedYuvSizes", JSONArray(sizes.map { "${it.width}x${it.height}" }))
        assertTrue("Requested exact YUV size is not advertised: ${width}x$height", sizes.any { it.width == width && it.height == height })
        val thread = HandlerThread("ActualYuvColorProbe").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val failure = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        val closing = AtomicBoolean()
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var frames = 0
        var previousTimestamp = 0L
        val timestamps = JSONArray()
        fun fail(error: Throwable) { failure.compareAndSet(null, error); done.countDown() }
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireNextImage()?.use { image ->
                if (closing.get() || frames >= 3) return@use
                check(image.planes.size == 3 && image.width == width && image.height == height)
                check(image.timestamp > previousTimestamp) { "Camera2 YUV timestamps did not increase" }
                previousTimestamp = image.timestamp; timestamps.put(image.timestamp); frames++
                if (frames == 3) { recordImage(image, directory, report); done.countDown() }
            } } catch (error: Throwable) { fail(error) }
        }, handler)
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closing.get()) { device.close(); return }; camera = device
                    try { device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closing.get()) { configured.close(); return }; session = configured
                            try { configured.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(reader.surface) }.build(), null, handler) }
                            catch (error: Throwable) { fail(error) }
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) { fail(IllegalStateException("Actual YUV-only session rejected")) }
                    }, handler) } catch (error: Throwable) { fail(error) }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail(IllegalStateException("Camera2 disconnected")) }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail(IllegalStateException("Camera2 error $error")) }
            }, handler)
            assertTrue("No three actual YUV frames within 30 seconds", done.await(30, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Actual YUV capture failed", it) }
            assertEquals(3, frames)
            report.put("passed", true)
        } catch (error: Throwable) { report.put("failure", error.toString()); throw error }
        finally {
            closing.set(true)
            val released = CountDownLatch(1)
            handler.post {
                try {
                    runCatching { session?.stopRepeating() }; runCatching { session?.close() }; runCatching { camera?.close() }
                    reader.close()
                } finally { released.countDown() }
            }
            released.await(3, TimeUnit.SECONDS)
            report.put("frames", frames).put("timestampsNs", timestamps)
            File(directory, "metrics.json").writeText(report.toString(2))
            thread.quitSafely()
        }
    }

    private data class Plane(val bytes: ByteBuffer, val position: Int, val limit: Int, val row: Int, val pixel: Int) {
        fun value(x: Int, y: Int): Int {
            val at = position + y * row + x * pixel
            check(at >= position && at < limit) { "Reported plane stride/crop references a byte outside the actual buffer" }
            return bytes.get(at).toInt() and 255
        }
    }
    private fun recordImage(image: Image, directory: File, report: JSONObject) {
        val crop = image.cropRect
        report.put("width", image.width).put("height", image.height).put("timestampNs", image.timestamp)
            .put("format", image.format).put("crop", JSONObject().put("left", crop.left).put("top", crop.top).put("right", crop.right).put("bottom", crop.bottom))
        val planes = image.planes.mapIndexed { index, raw ->
            val original = raw.buffer
            val remaining = original.duplicate()
            val bytes = ByteArray(remaining.remaining()); remaining.get(bytes)
            File(directory, "plane-$index-remaining.bin").writeBytes(bytes)
            val all = original.duplicate(); all.position(0)
            val fullBytes = ByteArray(all.remaining()); all.get(fullBytes)
            File(directory, "plane-$index-zero-to-limit.bin").writeBytes(fullBytes)
            Plane(original.duplicate(), original.position(), original.limit(), raw.rowStride, raw.pixelStride)
        }
        val info = JSONArray()
        var meanY = 0L; var meanU = 0L; var meanV = 0L
        for ((index, plane) in planes.withIndex()) {
            val raw = image.planes[index].buffer
            info.put(JSONObject().put("index", index).put("component", listOf("Y", "U", "V")[index])
                .put("position", raw.position()).put("limit", raw.limit()).put("capacity", raw.capacity()).put("remaining", raw.remaining())
                .put("rowStride", plane.row).put("pixelStride", plane.pixel)
                .put("firstBytes", JSONArray((0 until minOf(raw.remaining(), 64)).map { plane.bytes.get(plane.position + it).toInt() and 255 })))
        }
        report.put("planes", info)
        val colors = IntArray(crop.width() * crop.height())
        val patches = JSONArray()
        val sampleColumns = setOf(crop.width() / 4, crop.width() / 2, crop.width() * 3 / 4)
        val sampleRows = setOf(crop.height() / 4, crop.height() / 2, crop.height() * 3 / 4)
        for (y in 0 until crop.height()) for (x in 0 until crop.width()) {
            val absoluteX = crop.left + x; val absoluteY = crop.top + y
            val luma = planes[0].value(absoluteX, absoluteY)
            val u = planes[1].value(absoluteX / 2, absoluteY / 2)
            val v = planes[2].value(absoluteX / 2, absoluteY / 2)
            meanY += luma; meanU += u; meanV += v
            // Diagnostic rendering uses each plane's actual independent strides.
            val c = luma - 16; val d = u - 128; val e = v - 128
            val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            colors[y * crop.width() + x] = Color.rgb(red, green, blue)
            if (x in sampleColumns && y in sampleRows)
                patches.put(JSONObject().put("x", absoluteX).put("y", absoluteY).put("Y", luma).put("U", u).put("V", v).put("r", red).put("g", green).put("b", blue))
        }
        val count = colors.size.toDouble()
        report.put("meanY", meanY / count).put("meanU", meanU / count).put("meanV", meanV / count).put("samples", patches)
            .put("diagnosticConversion", "BT.601 limited-range RGB using actual Y/U/V rowStride, pixelStride, buffer position and crop")
        val bitmap = Bitmap.createBitmap(colors, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        try { File(directory, "actual-planes-bt601-limited.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        stockJniComparison(image, directory, report)
    }

    /** Uses the unchanged stock JNI entry point before the captured Image closes. */
    private fun stockJniComparison(image: Image, directory: File, report: JSONObject) {
        val library = File("/system/lib64/libjni_jpegutil.so")
        val hash = library.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65536)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        report.put("stockJniLibrary", library.absolutePath).put("stockJniLibrarySha256", hash)
        check(hash == "6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5") { "The actual stock JPEG JNI library differs from the audited binary" }
        // The ordinary app namespace correctly refuses private /system libs.
        // Load a byte-identical diagnostic copy from our own private directory.
        val privateLibrary = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "libstock_jpegutil_probe.so")
        library.inputStream().use { input -> privateLibrary.outputStream().use { input.copyTo(it) } }
        System.load(privateLibrary.absolutePath)
        val live = image.planes.map { it.buffer }
        val copied = live.map { source ->
            val all = source.duplicate(); all.position(0)
            ByteBuffer.allocateDirect(source.capacity()).apply {
                put(all); limit(source.limit()); position(source.position())
            }
        }
        val results = JSONArray()
        report.put("stockJniComparisons", results)
        for ((mode, buffers) in listOf("live" to live, "copied" to copied)) for (rotation in listOf(0, 1, 3)) {
            val output = ByteBuffer.allocateDirect(image.width * image.height * 3)
            val started = SystemClock.elapsedRealtime()
            val count = JpegUtilNative.compressJpegFromYUV420pNative(image.width, image.height,
                buffers[0], image.planes[0].pixelStride, image.planes[0].rowStride,
                buffers[1], image.planes[1].pixelStride, image.planes[1].rowStride,
                buffers[2], image.planes[2].pixelStride, image.planes[2].rowStride,
                output, output.capacity(), 95,
                image.cropRect.left, image.cropRect.top, image.cropRect.right, image.cropRect.bottom, rotation)
            val result = JSONObject().put("mode", mode).put("rotationQuarterTurns", rotation).put("returnedBytes", count)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            results.put(result)
            check(count > 0 && count <= output.capacity()) { "The exact stock JNI encoder returned invalid size $count" }
            output.position(0); output.limit(count)
            val jpeg = ByteArray(count); output.get(jpeg)
            val name = "stock-jni-$mode-rot$rotation.jpg"
            File(directory, name).writeBytes(jpeg)
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Stock JNI output is not an actual JPEG" }
            try {
                result.put("file", name).put("width", decoded.width).put("height", decoded.height)
                val samples = JSONArray()
                var red = 0L; var green = 0L; var blue = 0L
                val pixels = IntArray(decoded.width * decoded.height)
                decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                for (pixel in pixels) { red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel) }
                result.put("meanR", red.toDouble() / pixels.size).put("meanG", green.toDouble() / pixels.size).put("meanB", blue.toDouble() / pixels.size)
                for (y in listOf(decoded.height / 4, decoded.height / 2, decoded.height * 3 / 4)) for (x in listOf(decoded.width / 4, decoded.width / 2, decoded.width * 3 / 4)) {
                    val pixel = decoded.getPixel(x, y)
                    samples.put(JSONObject().put("x", x).put("y", y).put("r", Color.red(pixel)).put("g", Color.green(pixel)).put("b", Color.blue(pixel)))
                }
                result.put("colorSamples", samples)
            } finally { decoded.recycle() }
        }
    }
}
