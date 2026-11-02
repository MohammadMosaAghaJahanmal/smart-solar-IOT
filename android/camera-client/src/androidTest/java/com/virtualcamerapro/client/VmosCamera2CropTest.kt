package com.virtualcamerapro.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Ordinary Camera2 app controls only public sensor crop metadata in one session. */
@RunWith(AndroidJUnit4::class)
class VmosCamera2CropTest {
    private data class PlaneLayout(val rowStride: Int, val pixelStride: Int, val position: Int, val limit: Int)
    private data class Frame(val timestamp: Long, val pixels: IntArray, val crop: Rect,
        val sourceX: IntArray, val sourceY: IntArray, val planes: List<PlaneLayout>,
        val imageHeldUs: Long, val rgbConversionUs: Long)
    private data class Window(val left: Double, val top: Double, val width: Double, val height: Double)

    @Suppress("MissingPermission", "DEPRECATION")
    @Test fun standardCropChangesAndRestoresActualPixelsWithoutReopeningCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("cameraId", "1")
        val width = args.getString("width", "1280").toInt()
        val height = args.getString("height", "720").toInt()
        require(id.matches(Regex("[0-9]{1,2}")) && width in 320..1920 && height in 240..1088 && width % 2 == 0 && height % 2 == 0)
        val sampleScale = minOf(320.0 / width, 240.0 / height)
        val sampleWidth = maxOf(1, (width * sampleScale).toInt())
        val sampleHeight = maxOf(1, (height * sampleScale).toInt())
        val directory = File(context.getExternalFilesDir(null), "camera2-crop/camera-$id-${width}x$height").apply { check(mkdirs() || isDirectory) }
        val report = JSONObject().put("passed", false).put("cameraId", id).put("consumerUid", Process.myUid())
            .put("width", width).put("height", height).put("control", "public CaptureRequest.SCALER_CROP_REGION only")
            .put("sampleGrid", JSONObject().put("width", sampleWidth).put("height", sampleHeight)
                .put("comparisonPointsPerFrame", 117).put("maximumWidth", 320).put("maximumHeight", 240)
                .put("sampling", "Nearest actual Y/U/V plane samples across Image.cropRect; RGB conversion occurs after Image.close"))
            .put("diagnosticImages", "Sampled ${sampleWidth}x${sampleHeight} PNGs, not full-resolution captures; calibration.png is the full-resolution source fixture")
            .put("sourceRequirement", "Select a static distinctive image, preferably the generated calibration.png, before the full test")
        val observations = JSONArray(); report.put("observations", observations)
        fun save() = File(directory, "crop-result.json").writeText(report.toString(2))
        // This explicit prepare mode lets the operator select a repeatable image
        // without granting this independent consumer any broker permission.
        val calibration = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val calibrationPixels = IntArray(width * height) { at ->
            val x = at % width; val y = at / width
            Color.rgb(20 + x * 210 / (width - 1), 20 + y * 210 / (height - 1),
                if ((x * 8 / width + y * 6 / height) % 2 == 0) 35 else 220)
        }
        calibration.setPixels(calibrationPixels, 0, width, 0, 0, width, height)
        try { File(directory, "calibration.png").outputStream().use { check(calibration.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { calibration.recycle() }
        if (args.getString("prepareOnly") == "true") {
            report.put("fixturePrepared", true).put("cameraTestExecuted", false); save(); return
        }
        assertEquals(28, Build.VERSION.SDK_INT); assertEquals("vphw90", Build.HARDWARE)
        assertNotEquals(0, Process.myUid()); assertNotEquals(1000, Process.myUid())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val manager = context.getSystemService(CameraManager::class.java)
        val characteristics = manager.getCameraCharacteristics(id)
        val active = Rect(requireNotNull(characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)))
        val maxZoom = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
        val cropType = characteristics.get(CameraCharacteristics.SCALER_CROPPING_TYPE)
        report.put("activeArray", active.flattenToString()).put("maxDigitalZoom", maxZoom).put("croppingType", cropType)
        assertTrue("At least 2x public crop must be advertised", maxZoom >= 2f)
        val available = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)).getOutputSizes(ImageFormat.YUV_420_888)
        assertTrue("Requested exact YUV output must be advertised", available.any { it.width == width && it.height == height })
        // A synchronous Camera2 Binder update must never block delivery of the
        // ImageReader buffers or camera callbacks it may need to complete.
        val cameraThread = HandlerThread("IndependentCropEvents").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)
        val controlThread = HandlerThread("IndependentCropControl").apply { start() }
        val controlHandler = Handler(controlThread.looper)
        val imageThread = HandlerThread("IndependentCropImages").apply { start() }
        val imageHandler = Handler(imageThread.looper)
        report.put("threading", "Separate camera callbacks, synchronous request controls, and ImageReader conversion")
        val updates = JSONArray(); report.put("requestUpdates", updates)
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        val frames = LinkedBlockingQueue<Frame>(4)
        val failure = AtomicReference<Throwable?>()
        val ready = CountDownLatch(1)
        val closing = AtomicBoolean()
        val opens = AtomicInteger(); val configurations = AtomicInteger()
        var camera: CameraDevice? = null; var session: CameraCaptureSession? = null
        fun fail(error: Throwable) { failure.compareAndSet(null, error); ready.countDown() }
        reader.setOnImageAvailableListener({ source ->
            try {
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                val acquired = SystemClock.elapsedRealtimeNanos()
                var timestamp = 0L
                var crop = Rect()
                var sourceX = IntArray(0); var sourceY = IntArray(0)
                var layouts = emptyList<PlaneLayout>()
                val sampledYuv = IntArray(sampleWidth * sampleHeight)
                // Hold the camera buffer only long enough to copy a bounded grid.
                // In particular, PNG encoding and RGB conversion never hold Image.
                image.use {
                    if (closing.get()) return@setOnImageAvailableListener
                    check(image.width == width && image.height == height && image.planes.size == 3)
                    timestamp = image.timestamp
                    crop = Rect(image.cropRect)
                    check(crop.width() > 0 && crop.height() > 0 && crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height)
                    sourceX = IntArray(sampleWidth) { crop.left + it * crop.width() / sampleWidth }
                    sourceY = IntArray(sampleHeight) { crop.top + it * crop.height() / sampleHeight }
                    val buffers = image.planes.map { it.buffer.duplicate() }
                    layouts = image.planes.mapIndexed { index, plane ->
                        check(plane.rowStride > 0 && plane.pixelStride > 0)
                        PlaneLayout(plane.rowStride, plane.pixelStride, buffers[index].position(), buffers[index].limit())
                    }
                    fun value(index: Int, x: Int, y: Int): Int {
                        val plane = layouts[index]; val buffer = buffers[index]
                        val offset = plane.position.toLong() + y.toLong() * plane.rowStride + x.toLong() * plane.pixelStride
                        check(offset >= plane.position && offset < plane.limit)
                        return buffer.get(offset.toInt()).toInt() and 255
                    }
                    for (gy in 0 until sampleHeight) for (gx in 0 until sampleWidth) {
                        val x = sourceX[gx]; val y = sourceY[gy]
                        sampledYuv[gy * sampleWidth + gx] = (value(0, x, y) shl 16) or
                            (value(1, x / 2, y / 2) shl 8) or value(2, x / 2, y / 2)
                    }
                }
                val released = SystemClock.elapsedRealtimeNanos()
                val pixels = IntArray(sampledYuv.size) { index ->
                    val yuv = sampledYuv[index]
                    val c = (yuv ushr 16) - 16; val d = ((yuv ushr 8) and 255) - 128; val e = (yuv and 255) - 128
                    Color.rgb(((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255),
                        ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255), ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255))
                }
                val frame = Frame(timestamp, pixels, crop, sourceX, sourceY, layouts,
                    (released - acquired) / 1000, (SystemClock.elapsedRealtimeNanos() - released) / 1000)
                if (!frames.offer(frame)) { frames.poll(); frames.offer(frame) }
            } catch (error: Throwable) { fail(error) }
        }, imageHandler)
        fun update(rect: Rect) {
            val updated = CountDownLatch(1)
            val stage = AtomicReference("queued")
            val started = SystemClock.elapsedRealtime()
            val requested = Rect(rect)
            check(controlHandler.post {
                try {
                    check(!closing.get()) { "Crop test is closing" }
                    stage.set("building_request")
                    val request = requireNotNull(camera).createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface); set(CaptureRequest.SCALER_CROP_REGION, requested)
                    }.build()
                    stage.set("setRepeatingRequest_entered")
                    requireNotNull(session).setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                            fail(IllegalStateException("Standard crop capture failed: reason=${failure.reason} frame=${failure.frameNumber}"))
                        }
                    }, cameraHandler)
                    stage.set("submitted")
                } catch (error: Throwable) { stage.set("failed"); fail(error) }
                finally { updated.countDown() }
            }) { "Camera crop control thread rejected the update" }
            val submitted = updated.await(5, TimeUnit.SECONDS)
            val observation = JSONObject().put("requestedCrop", requested.flattenToString()).put("stage", stage.get())
                .put("submitted", submitted).put("elapsedMs", SystemClock.elapsedRealtime() - started)
            if (!submitted) {
                observation.put("controlThreadStack", JSONArray(controlThread.stackTrace.map { it.toString() }))
                    .put("cameraThreadStack", JSONArray(cameraThread.stackTrace.map { it.toString() }))
                    .put("imageThreadStack", JSONArray(imageThread.stackTrace.map { it.toString() }))
            }
            updates.put(observation); save()
            check(submitted) { "Camera crop update was not submitted (stage=${stage.get()}; camera/image callbacks run independently)" }
            failure.get()?.let { throw it }
        }
        var lastTimestamp = 0L
        var expectedImageCrop: Rect? = null
        var observedFrames = 0
        var maxImageHeldUs = 0L; var maxRgbConversionUs = 0L
        fun next(): Frame {
            failure.get()?.let { throw it }
            val frame = requireNotNull(frames.poll(6, TimeUnit.SECONDS)) { "No actual YUV image after public crop request" }
            check(frame.timestamp > lastTimestamp) { "Actual sensor timestamps did not advance across crop updates" }
            expectedImageCrop?.let { check(it == frame.crop) { "Image.cropRect changed: expected $it, received ${frame.crop}" } }
            observedFrames++
            maxImageHeldUs = maxOf(maxImageHeldUs, frame.imageHeldUs)
            maxRgbConversionUs = maxOf(maxRgbConversionUs, frame.rgbConversionUs)
            lastTimestamp = frame.timestamp; return frame
        }
        fun png(name: String, frame: Frame) {
            val bitmap = Bitmap.createBitmap(frame.pixels, sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
            try { File(directory, "$name-sampled-${sampleWidth}x${sampleHeight}.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closing.get()) { device.close(); return }; camera = device; opens.incrementAndGet()
                    try { device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closing.get()) { configured.close(); return }; session = configured; configurations.incrementAndGet(); ready.countDown()
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) { fail(IllegalStateException("Crop YUV session rejected")) }
                    }, cameraHandler) } catch (error: Throwable) { fail(error) }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail(IllegalStateException("Held camera disconnected")) }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail(IllegalStateException("Held camera error $error")) }
            }, cameraHandler)
            assertTrue("Camera did not configure", ready.await(10, TimeUnit.SECONDS)); failure.get()?.let { throw it }
            update(active)
            repeat(3) { next() }
            val baseline = next(); png("baseline-full", baseline)
            expectedImageCrop = Rect(baseline.crop)
            report.put("baselineTimestampNs", baseline.timestamp).put("actualImageCrop", baseline.crop.flattenToString())
                .put("actualPlaneLayout", JSONArray(baseline.planes.map { JSONObject().put("rowStride", it.rowStride)
                    .put("pixelStride", it.pixelStride).put("position", it.position).put("limit", it.limit) }))
            val halfWidth = active.width() / 2; val halfHeight = active.height() / 2
            val steps = listOf(
                "center-2x" to Rect(active.left + active.width() / 4, active.top + active.height() / 4,
                    active.left + active.width() / 4 + halfWidth, active.top + active.height() / 4 + halfHeight),
                "top-left-2x" to Rect(active.left, active.top, active.left + halfWidth, active.top + halfHeight),
                "bottom-right-2x" to Rect(active.right - halfWidth, active.bottom - halfHeight, active.right, active.bottom),
                // Deliberately different rectangle aspect to expose independent-axis stretching.
                "tall-center" to Rect(active.left + active.width() / 4, active.top, active.left + active.width() / 4 + halfWidth, active.bottom),
                "restored-full" to Rect(active))
            val baseWindow = window(active, width, height)
            for ((name, crop) in steps) {
                if (name != "center-2x" && name != "restored-full")
                    assertEquals("Off-center crop requires the standard FREEFORM capability", CameraCharacteristics.SCALER_CROPPING_TYPE_FREEFORM, cropType)
                val target = window(crop, width, height)
                val expected = expectedSamples(baseline, sampleWidth, sampleHeight, width, height, baseWindow, target)
                val unzoomed = expectedSamples(baseline, sampleWidth, sampleHeight, width, height, baseWindow, baseWindow)
                val distinguishability = distance(expected, unzoomed)
                if (name != "restored-full") assertTrue("Select the generated calibration.png: this source cannot distinguish $name from an unchanged camera ($distinguishability)", distinguishability > 10.0)
                frames.clear(); val started = SystemClock.elapsedRealtime(); update(crop)
                val timeout = SystemClock.elapsedRealtime() + 10000
                var matched = 0; var observed: Frame? = null; var best = Double.POSITIVE_INFINITY; var error = best
                while (matched < 3 && SystemClock.elapsedRealtime() < timeout) {
                    val frame = next(); error = distance(sample(frame.pixels, sampleWidth, sampleHeight), expected); best = minOf(best, error)
                    if (error <= 14.0) { matched++; observed = frame } else matched = 0
                }
                observations.put(JSONObject().put("name", name).put("requestedCrop", crop.flattenToString()).put("stableMatchingFrames", matched)
                    .put("lastChannelMae", error).put("bestChannelMae", best).put("uncroppedExpectedDifferenceMae", distinguishability)
                    .put("actualTimestampNs", observed?.timestamp).put("updateToObservedMs", SystemClock.elapsedRealtime() - started)
                    .put("matchedFrameImageHeldUs", observed?.imageHeldUs).put("matchedFrameRgbConversionUs", observed?.rgbConversionUs))
                save()
                assertTrue("Three actual frames must match the requested $name crop (best channel MAE $best)", matched >= 3)
                png(name, requireNotNull(observed))
            }
            assertEquals("CameraDevice must remain open", 1, opens.get())
            assertEquals("CameraCaptureSession must remain configured", 1, configurations.get())
            report.put("passed", true).put("cameraTestExecuted", true)
        } catch (error: Throwable) { report.put("failure", error.toString()); throw error }
        finally {
            closing.set(true)
            val closed = CountDownLatch(1)
            controlHandler.post { try { runCatching { session?.stopRepeating() }; runCatching { session?.close() }; camera?.close() } finally { closed.countDown() } }
            report.put("cameraControlClosed", closed.await(3, TimeUnit.SECONDS))
            val imagesClosed = CountDownLatch(1)
            imageHandler.post { try { reader.close() } finally { imagesClosed.countDown() } }
            report.put("imageReaderClosed", imagesClosed.await(2, TimeUnit.SECONDS))
            controlThread.quitSafely(); cameraThread.quitSafely(); imageThread.quitSafely()
            report.put("cameraOpens", opens.get()).put("sessionConfigurations", configurations.get()).put("lastActualTimestampNs", lastTimestamp)
                .put("observedFrames", observedFrames).put("maximumObservedImageHeldUs", maxImageHeldUs)
                .put("maximumObservedRgbConversionUs", maxRgbConversionUs); save()
        }
    }

    private fun window(rect: Rect, width: Int, height: Int): Window {
        var w = rect.width(); var h = rect.height(); var left = rect.left; var top = rect.top
        if (w.toLong() * height > h.toLong() * width) { val actual = maxOf(1, h * width / height); left += (w - actual) / 2; w = actual }
        else if (w.toLong() * height < h.toLong() * width) { val actual = maxOf(1, w * height / width); top += (h - actual) / 2; h = actual }
        return Window(left.toDouble(), top.toDouble(), w.toDouble(), h.toDouble())
    }
    private fun points(width: Int, height: Int): List<Pair<Int, Int>> = (1..9).flatMap { row ->
        (1..13).map { column -> (column * width / 14) to (row * height / 10) }
    }
    private fun sample(pixels: IntArray, width: Int, height: Int): IntArray = points(width, height).map { (x,y) -> pixels[y * width + x] }.toIntArray()
    private fun expectedSamples(frame: Frame, gridWidth: Int, gridHeight: Int, outputWidth: Int, outputHeight: Int,
        base: Window, target: Window): IntArray = points(gridWidth, gridHeight).map { (x,y) ->
        // Use actual stream aspect and actual sampled output-pixel coordinates,
        // including Image.cropRect. The rounded grid aspect never defines a crop.
        val sourceX = (target.left + frame.sourceX[x].toDouble() / outputWidth * target.width - base.left) / base.width * outputWidth
        val sourceY = (target.top + frame.sourceY[y].toDouble() / outputHeight * target.height - base.top) / base.height * outputHeight
        val atX = nearest(frame.sourceX, sourceX); val atY = nearest(frame.sourceY, sourceY)
        frame.pixels[atY * gridWidth + atX]
    }.toIntArray()
    private fun nearest(coordinates: IntArray, wanted: Double): Int {
        var low = 0; var high = coordinates.lastIndex
        while (low < high) {
            val middle = (low + high) / 2
            if (coordinates[middle] < wanted) low = middle + 1 else high = middle
        }
        return if (low > 0 && abs(coordinates[low - 1] - wanted) <= abs(coordinates[low] - wanted)) low - 1 else low
    }
    private fun distance(a: IntArray, b: IntArray): Double {
        check(a.size == b.size)
        var sum = 0L
        for (i in a.indices) sum += abs(Color.red(a[i]) - Color.red(b[i])) + abs(Color.green(a[i]) - Color.green(b[i])) + abs(Color.blue(a[i]) - Color.blue(b[i]))
        return sum.toDouble() / (a.size * 3)
    }
}
