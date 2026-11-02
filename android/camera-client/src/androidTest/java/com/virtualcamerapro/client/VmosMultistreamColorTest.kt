package com.virtualcamerapro.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.camera.util.JpegUtilNative
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Diagnoses the stream combination used by stock Camera2 ZSL, independently of
 * its Java image wrappers: PRIVATE preview first, then a full-resolution YUV
 * still frame. An optional repeating-both mode keeps the earlier ZSL probe.
 * No HAL patches or broker calls occur.
 */
@RunWith(AndroidJUnit4::class)
class VmosMultistreamColorTest {
    @Suppress("MissingPermission", "DEPRECATION")
    @Test fun zslPrivatePreviewAndFullResolutionYuvKeepActualChroma() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals(28, Build.VERSION.SDK_INT)
        assertEquals("vphw90", Build.HARDWARE)
        assertNotEquals(0, Process.myUid())
        assertNotEquals(1000, Process.myUid())
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val cameraId = args.getString("cameraId", "1")
        val width = args.getString("width", "1920").toInt()
        val height = args.getString("height", "1088").toInt()
        val previewWidth = args.getString("previewWidth", "1920").toInt()
        val previewHeight = args.getString("previewHeight", "1080").toInt()
        val mode = args.getString("mode", "still")
        require(mode in listOf("still", "repeatBoth"))
        require(cameraId.matches(Regex("[0-9]{1,2}")))
        require(listOf(width, height, previewWidth, previewHeight).all { it in 2..4096 && it % 2 == 0 })
        val template = when (args.getString("template", if (mode == "still") "still" else "zsl")) {
            "still" -> CameraDevice.TEMPLATE_STILL_CAPTURE
            "zsl" -> CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG
            "preview" -> CameraDevice.TEMPLATE_PREVIEW
            else -> error("template must be still, zsl, or preview")
        }
        val directory = File(context.getExternalFilesDir(null),
            "multistream-color/camera-$cameraId-yuv-${width}x$height-preview-${previewWidth}x$previewHeight-mode-$mode-template-$template").apply { check(mkdirs() || isDirectory) }
        val report = JSONObject().put("passed", false).put("consumerUid", Process.myUid()).put("cameraId", cameraId)
            .put("requestedYuv", "${width}x$height").put("requestedPreview", "${previewWidth}x$previewHeight")
            .put("template", template).put("mode", mode).put("readerMaxImages", 15)
            .put("privatePreviewConsumer", "EGL SurfaceTexture drained on an independent GL thread")
        val manager = context.getSystemService(CameraManager::class.java)
        val configuration = requireNotNull(manager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        val yuvSizes = requireNotNull(configuration.getOutputSizes(ImageFormat.YUV_420_888))
        val previewSizes = requireNotNull(configuration.getOutputSizes(SurfaceTexture::class.java))
        report.put("advertisedYuvSizes", JSONArray(yuvSizes.map { "${it.width}x${it.height}" }))
            .put("advertisedPrivateSizes", JSONArray(previewSizes.map { "${it.width}x${it.height}" }))
        assertTrue("Exact YUV size must be advertised", yuvSizes.any { it.width == width && it.height == height })
        assertTrue("Exact PRIVATE preview size must be advertised", previewSizes.any { it.width == previewWidth && it.height == previewHeight })
        val failure = AtomicReference<Throwable?>()
        val completed = CountDownLatch(1)
        val closing = AtomicBoolean()
        val previewFrames = AtomicInteger()
        val completedRequests = AtomicInteger()
        val captureSubmitted = AtomicBoolean()
        val thread = HandlerThread("MultistreamYuvProbe").apply { start() }
        val handler = Handler(thread.looper)
        val preview = PrivatePreview(previewWidth, previewHeight, previewFrames) { error ->
            failure.compareAndSet(null, error); completed.countDown()
        }
        val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 15)
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        var frames = 0
        var previousTimestamp = 0L
        val timestamps = JSONArray()
        fun fail(error: Throwable) { failure.compareAndSet(null, error); completed.countDown() }
        reader.setOnImageAvailableListener({ source ->
            try { source.acquireNextImage()?.use { image ->
                if (closing.get() || completed.count == 0L) return@use
                check(image.width == width && image.height == height && image.planes.size == 3)
                check(image.timestamp > previousTimestamp) { "YUV timestamps stopped increasing" }
                previousTimestamp = image.timestamp; frames++; timestamps.put(image.timestamp)
                // Wait for preview consumption, rather than trusting session configuration.
                if ((mode == "still" || frames >= 6) && previewFrames.get() >= 3) {
                    report.put("previewFramesBeforeSample", previewFrames.get()).put("requestsBeforeSample", completedRequests.get())
                    recordImage(image, directory, report)
                    completed.countDown()
                }
            } } catch (error: Throwable) { fail(error) }
        }, handler)
        try {
            preview.start()
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closing.get()) { device.close(); return }; camera = device
                    // Match stock stream IDs: full-resolution YUV first, preview second.
                    try { device.createCaptureSession(listOf(reader.surface, preview.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closing.get()) { configured.close(); return }; session = configured
                            try {
                                val request = device.createCaptureRequest(if (mode == "still") CameraDevice.TEMPLATE_PREVIEW else template).apply {
                                    addTarget(preview.surface)
                                    if (mode == "repeatBoth") addTarget(reader.surface)
                                }.build()
                                configured.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                        completedRequests.incrementAndGet()
                                        if (mode == "still" && previewFrames.get() >= 3 && captureSubmitted.compareAndSet(false, true)) {
                                            try {
                                                report.put("previewFramesAtShutter", previewFrames.get())
                                                    .put("requestsAtShutter", completedRequests.get())
                                                    .put("previewSensorTimestampAtShutter", result.get(TotalCaptureResult.SENSOR_TIMESTAMP))
                                                val still = device.createCaptureRequest(template).apply {
                                                    addTarget(reader.surface); addTarget(preview.surface)
                                                    set(CaptureRequest.JPEG_QUALITY, 80.toByte())
                                                    set(CaptureRequest.JPEG_ORIENTATION, 270)
                                                }.build()
                                                s.capture(still, object : CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                                                        report.put("stillSensorTimestamp", result.get(TotalCaptureResult.SENSOR_TIMESTAMP))
                                                            .put("stillFrameNumber", result.frameNumber)
                                                    }
                                                }, handler)
                                            } catch (error: Throwable) { fail(error) }
                                        }
                                    }
                                }, handler)
                            } catch (error: Throwable) { fail(error) }
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) { fail(IllegalStateException("Actual PRIVATE + YUV stream combination rejected")) }
                    }, handler) } catch (error: Throwable) { fail(error) }
                }
                override fun onDisconnected(device: CameraDevice) { device.close(); fail(IllegalStateException("Camera disconnected")) }
                override fun onError(device: CameraDevice, error: Int) { device.close(); fail(IllegalStateException("Camera error $error")) }
            }, handler)
            assertTrue("No concurrent PRIVATE preview and YUV sample within 45 seconds", completed.await(45, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Actual multistream color failed", it) }
            assertTrue("An actual full-resolution YUV still frame is required", frames >= if (mode == "still") 1 else 6)
            assertTrue("PRIVATE buffers must actually be consumed", previewFrames.get() >= 3)
            if (mode == "still") assertTrue("The YUV request must follow actual preview consumption", captureSubmitted.get())
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
            runCatching { preview.close() }.onFailure { report.put("previewCloseFailure", it.toString()) }
            report.put("yuvFrames", frames).put("previewFrames", previewFrames.get()).put("completedRequests", completedRequests.get())
                .put("yuvTimestampsNs", timestamps)
            File(directory, "metrics.json").writeText(report.toString(2))
            thread.quitSafely()
        }
    }

    private data class Plane(val bytes: ByteBuffer, val position: Int, val limit: Int, val row: Int, val pixel: Int) {
        fun value(x: Int, y: Int): Int {
            val at = position.toLong() + y.toLong() * row + x.toLong() * pixel
            check(at >= position && at < limit) { "Actual plane stride references an unavailable byte" }
            return bytes.get(at.toInt()).toInt() and 255
        }
    }

    private fun recordImage(image: Image, directory: File, report: JSONObject) {
        val crop = image.cropRect
        report.put("width", image.width).put("height", image.height).put("timestampNs", image.timestamp).put("format", image.format)
            .put("crop", JSONObject().put("left", crop.left).put("top", crop.top).put("right", crop.right).put("bottom", crop.bottom))
        val layout = JSONArray()
        val planes = image.planes.mapIndexed { index, source ->
            val buffer = source.buffer
            val all = buffer.duplicate().apply { position(0) }
            File(directory, "plane-$index-zero-to-limit.bin").outputStream().use { out ->
                val block = ByteArray(65536)
                while (all.hasRemaining()) { val count = minOf(all.remaining(), block.size); all.get(block, 0, count); out.write(block, 0, count) }
            }
            layout.put(JSONObject().put("component", listOf("Y", "U", "V")[index]).put("position", buffer.position())
                .put("limit", buffer.limit()).put("capacity", buffer.capacity()).put("rowStride", source.rowStride).put("pixelStride", source.pixelStride))
            Plane(buffer.duplicate(), buffer.position(), buffer.limit(), source.rowStride, source.pixelStride)
        }
        report.put("planes", layout)
        val rgb = IntArray(crop.width() * crop.height())
        val sums = LongArray(3)
        var jointlyLowChroma = 0L
        val samples = JSONArray()
        val sampleColumns = setOf(crop.width() / 4, crop.width() / 2, crop.width() * 3 / 4)
        val sampleRows = setOf(crop.height() / 4, crop.height() / 2, crop.height() * 3 / 4)
        for (y in 0 until crop.height()) for (x in 0 until crop.width()) {
            val absoluteX = crop.left + x; val absoluteY = crop.top + y
            val luma = planes[0].value(absoluteX, absoluteY)
            val u = planes[1].value(absoluteX / 2, absoluteY / 2)
            val v = planes[2].value(absoluteX / 2, absoluteY / 2)
            sums[0] += luma.toLong(); sums[1] += u.toLong(); sums[2] += v.toLong()
            if (u < 16 && v < 16) jointlyLowChroma++
            val c = luma - 16; val d = u - 128; val e = v - 128
            rgb[y * crop.width() + x] = Color.rgb(((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255),
                ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255), ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255))
            if (x in sampleColumns && y in sampleRows) {
                samples.put(JSONObject().put("x", absoluteX).put("y", absoluteY).put("Y", luma).put("U", u).put("V", v))
            }
        }
        report.put("meanY", sums[0].toDouble() / rgb.size).put("meanU", sums[1].toDouble() / rgb.size).put("meanV", sums[2].toDouble() / rgb.size)
            .put("jointlyNearZeroChromaFraction", jointlyLowChroma.toDouble() / rgb.size).put("colorSamples", samples)
            .put("diagnosticConversion", "BT.601 limited range, each plane's actual position, crop and independent row/pixel strides")
        val bitmap = Bitmap.createBitmap(rgb, crop.width(), crop.height(), Bitmap.Config.ARGB_8888)
        try { File(directory, "actual-planes-bt601-limited.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        // Save and run all encoder variants even if the source chroma is broken.
        encodeStockJni(image, directory, report, rgb)
        assertTrue("Both actual chroma components became near-zero across almost the entire image", jointlyLowChroma.toDouble() / rgb.size < 0.90)
    }

    private fun encodeStockJni(image: Image, directory: File, report: JSONObject, expected: IntArray) {
        val pinned = "6050a81c4eae572989b284bc55531a853efe83512b5413b7bffe00fb308c2bd5"
        fun sha256(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
            while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        val original = listOf(File("/system/lib64/.vcam-original-libjni_jpegutil.so"), File("/system/lib64/libjni_jpegutil.so"))
            .firstOrNull { it.isFile && sha256(it) == pinned } ?: error("No exact audited stock JPEG JNI original is available")
        report.put("stockJniOriginal", original.absolutePath).put("stockJniSha256", pinned)
        val copy = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "libstock_jpegutil_multistream_probe.so")
        original.inputStream().use { input -> copy.outputStream().use { input.copyTo(it) } }
        check(sha256(copy) == pinned)
        System.load(copy.absolutePath)
        val live = image.planes.map { it.buffer }
        val copied = live.map { input -> ByteBuffer.allocateDirect(input.capacity()).apply {
            put(input.duplicate().apply { position(0) }); limit(input.limit()); position(input.position())
        } }
        val results = JSONArray(); report.put("stockJniComparisons", results)
        var allMatch = true
        for ((mode, buffers) in listOf("live" to live, "copied" to copied)) for (rotation in listOf(0, 1, 3)) {
            val output = ByteBuffer.allocateDirect(image.width * image.height * 3)
            val started = SystemClock.elapsedRealtime()
            val count = JpegUtilNative.compressJpegFromYUV420pNative(image.width, image.height,
                buffers[0], image.planes[0].pixelStride, image.planes[0].rowStride,
                buffers[1], image.planes[1].pixelStride, image.planes[1].rowStride,
                buffers[2], image.planes[2].pixelStride, image.planes[2].rowStride,
                output, output.capacity(), 95, image.cropRect.left, image.cropRect.top, image.cropRect.right, image.cropRect.bottom, rotation)
            val result = JSONObject().put("mode", mode).put("rotationQuarterTurns", rotation).put("returnedBytes", count)
                .put("elapsedMs", SystemClock.elapsedRealtime() - started)
            results.put(result)
            check(count > 0 && count <= output.capacity()) { "Stock JNI returned an invalid byte count $count" }
            output.position(0); output.limit(count)
            val jpeg = ByteArray(count); output.get(jpeg)
            val filename = "stock-jni-$mode-rot$rotation.jpg"; File(directory, filename).writeBytes(jpeg)
            val decoded = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, count))
            try {
                val sourceWidth = image.cropRect.width(); val sourceHeight = image.cropRect.height()
                assertEquals(if (rotation % 2 == 0) sourceWidth else sourceHeight, decoded.width)
                assertEquals(if (rotation % 2 == 0) sourceHeight else sourceWidth, decoded.height)
                val alternatives = if (rotation == 0) listOf(0) else listOf(1, 3)
                val errors = alternatives.map { turn ->
                    var error = 0L; var samples = 0
                    // Sample away from image edges and on every 8th pixel; no fit/scale tolerance.
                    for (y in 8 until sourceHeight - 8 step 8) for (x in 8 until sourceWidth - 8 step 8) {
                        val actual = when (turn) {
                            1 -> decoded.getPixel(sourceHeight - 1 - y, x)
                            3 -> decoded.getPixel(y, sourceWidth - 1 - x)
                            else -> decoded.getPixel(x, y)
                        }
                        val wanted = expected[y * sourceWidth + x]
                        error += abs(Color.red(actual) - Color.red(wanted)) + abs(Color.green(actual) - Color.green(wanted)) + abs(Color.blue(actual) - Color.blue(wanted))
                        samples++
                    }
                    turn to error.toDouble() / (samples * 3)
                }
                val best = errors.minBy { it.second }
                val pixels = IntArray(decoded.width * decoded.height)
                decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
                var red = 0L; var green = 0L; var blue = 0L
                for (pixel in pixels) { red += Color.red(pixel); green += Color.green(pixel); blue += Color.blue(pixel) }
                result.put("file", filename).put("width", decoded.width).put("height", decoded.height)
                    .put("meanR", red.toDouble() / pixels.size).put("meanG", green.toDouble() / pixels.size).put("meanB", blue.toDouble() / pixels.size)
                    .put("bestClockwiseQuarterTurns", best.first).put("actualYuvToJpegChannelMae", best.second)
                    .put("colorParityThresholdMae", 18.0)
                if (best.second > 18.0) allMatch = false
            } finally { decoded.recycle() }
        }
        assertTrue("The unchanged stock JPEG encoder changed actual live/copied YUV colors (see all six saved JPEGs)", allMatch)
    }

    /** An actual SurfaceTexture consumer; unlike an undrained Surface it cannot stall after a few buffers. */
    private class PrivatePreview(private val width: Int, private val height: Int, private val frames: AtomicInteger, private val fail: (Throwable) -> Unit) {
        private val thread = HandlerThread("MultistreamPrivateGl").apply { start() }
        private val handler = Handler(thread.looper)
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        private var textureName = 0
        private var texture: SurfaceTexture? = null
        lateinit var surface: Surface; private set
        fun start() {
            val ready = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            handler.post {
                try {
                    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    check(display != EGL14.EGL_NO_DISPLAY)
                    check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
                    val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                    check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
                    val config = requireNotNull(configs[0])
                    context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                    check(context != EGL14.EGL_NO_CONTEXT)
                    pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                    check(pbuffer != EGL14.EGL_NO_SURFACE)
                    check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
                    val names = IntArray(1); GLES20.glGenTextures(1, names, 0); textureName = names[0]
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                    texture = SurfaceTexture(textureName).apply {
                        setDefaultBufferSize(width, height)
                        setOnFrameAvailableListener({ available ->
                            try { available.updateTexImage(); frames.incrementAndGet() } catch (exception: Throwable) { fail(exception) }
                        }, handler)
                    }
                    surface = Surface(requireNotNull(texture))
                } catch (exception: Throwable) { error.set(exception) }
                finally { ready.countDown() }
            }
            check(ready.await(10, TimeUnit.SECONDS)) { "Could not initialize the actual PRIVATE preview consumer" }
            error.get()?.let { throw it }
        }
        fun close() {
            val released = CountDownLatch(1)
            handler.post {
                try {
                    texture?.setOnFrameAvailableListener(null)
                    if (::surface.isInitialized) surface.release()
                    texture?.release()
                    if (textureName != 0) GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                        EGL14.eglTerminate(display); EGL14.eglReleaseThread()
                    }
                } finally { released.countDown() }
            }
            check(released.await(3, TimeUnit.SECONDS)) { "PRIVATE preview consumer did not close" }
            thread.quitSafely()
        }
    }
}
