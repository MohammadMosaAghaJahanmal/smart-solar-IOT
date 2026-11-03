package com.virtualcamerapro.broker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
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
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual stock-camera stream combination: consumed PRIVATE preview followed by a larger YUV
 * still request. Used with the setup calibration source, whose U/V are never both near zero.
 * Captures are sampled directly using their reported layout; no stock JNI library is loaded.
 */
internal class GuestStillColorProbe(private val context: Context) {
    data class Proof(val metrics: JSONObject, val jpeg: ByteArray)
    private data class Sample(val metrics: JSONObject, val pixels: IntArray)

    @Suppress("MissingPermission", "DEPRECATION")
    fun capture(cameraId: String, previewWidth: Int, previewHeight: Int, stillWidth: Int, stillHeight: Int): Proof {
        require(listOf(previewWidth, previewHeight, stillWidth, stillHeight).all { it > 0 })
        val manager = context.getSystemService(CameraManager::class.java)
        require(cameraId in manager.cameraIdList) { "The requested still-color camera is unavailable" }
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val map = requireNotNull(characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
        require(map.getOutputSizes(SurfaceTexture::class.java)?.any { it.width == previewWidth && it.height == previewHeight } == true) {
            "The requested PRIVATE preview dimensions are not advertised by this camera"
        }
        require(map.getOutputSizes(ImageFormat.YUV_420_888)?.any { it.width == stillWidth && it.height == stillHeight } == true) {
            "The requested YUV still dimensions are not advertised by this camera"
        }
        val started = SystemClock.elapsedRealtime()
        val deadline = started + CAPTURE_TIMEOUT_MS
        val failure = AtomicReference<Throwable?>()
        val result = AtomicReference<Proof?>()
        val stillResult = AtomicReference<TotalCaptureResult?>()
        val completed = CountDownLatch(1)
        val closing = AtomicBoolean()
        val previewFrames = AtomicInteger()
        val captureSubmitted = AtomicBoolean()
        val framesAtShutter = AtomicInteger()
        val previewRequests = AtomicInteger()
        val camera = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        val callbackThread = HandlerThread("GuestStillColorCamera").apply { start() }
        val handler = Handler(callbackThread.looper)
        fun fail(error: Throwable) {
            if (!closing.get()) { failure.compareAndSet(null, error); completed.countDown() }
        }
        fun completeIfReady() {
            if (result.get() != null && stillResult.get() != null) completed.countDown()
        }
        val preview = PrivatePreview(previewWidth, previewHeight, previewFrames, ::fail)
        var reader: ImageReader? = null
        var primaryError: Throwable? = null
        try {
            preview.start(deadline)
            val yuv = ImageReader.newInstance(stillWidth, stillHeight, ImageFormat.YUV_420_888, 3)
            reader = yuv
            yuv.setOnImageAvailableListener({ source ->
                try {
                    val sample = source.acquireNextImage()?.use { image ->
                        if (closing.get() || result.get() != null) return@use null
                        check(captureSubmitted.get() && framesAtShutter.get() >= REQUIRED_PREVIEW_FRAMES) {
                            "The YUV still arrived before actual PRIVATE preview consumption"
                        }
                        check(image.width == stillWidth && image.height == stillHeight) { "Unexpected YUV still dimensions" }
                        readSample(image)
                    } ?: return@setOnImageAvailableListener
                    // The hardware Image is closed before bitmap/JPEG encoding or pixel comparison.
                    val bitmap = Bitmap.createBitmap(sample.pixels, SAMPLE_WIDTH, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
                    val bytes = try {
                        ByteArrayOutputStream().use { output ->
                            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) { "YUV calibration sample could not be encoded" }
                            output.toByteArray()
                        }
                    } finally { bitmap.recycle() }
                    check(bytes.isNotEmpty())
                    result.compareAndSet(null, Proof(sample.metrics, bytes))
                    completeIfReady()
                } catch (error: Throwable) { fail(error) }
            }, handler)
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (closing.get()) { device.close(); return }
                    camera.set(device)
                    if (closing.get()) { camera.getAndSet(null)?.close(); return }
                    try {
                        // Preserve stock stream ordering: full-size YUV first, PRIVATE preview second.
                        device.createCaptureSession(listOf(yuv.surface, preview.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(configured: CameraCaptureSession) {
                                if (closing.get()) { configured.close(); return }
                                session.set(configured)
                                if (closing.get()) { session.getAndSet(null)?.close(); return }
                                val callback = object : CameraCaptureSession.CaptureCallback() {
                                    override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, error: CaptureFailure) {
                                        fail(IllegalStateException("PRIVATE/YUV camera request failed: ${error.reason}"))
                                    }
                                    override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                                        fail(IllegalStateException("PRIVATE/YUV camera lost a requested buffer"))
                                    }
                                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, metadata: TotalCaptureResult) {
                                        if (closing.get()) return
                                        previewRequests.incrementAndGet()
                                        if (previewFrames.get() >= REQUIRED_PREVIEW_FRAMES && captureSubmitted.compareAndSet(false, true)) {
                                            framesAtShutter.set(previewFrames.get())
                                            try {
                                                val still = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                                    addTarget(yuv.surface); addTarget(preview.surface)
                                                }.build()
                                                s.capture(still, object : CameraCaptureSession.CaptureCallback() {
                                                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, metadata: TotalCaptureResult) {
                                                        stillResult.set(metadata); completeIfReady()
                                                    }
                                                    override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, error: CaptureFailure) {
                                                        fail(IllegalStateException("Actual YUV still capture failed: ${error.reason}"))
                                                    }
                                                    override fun onCaptureBufferLost(s: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
                                                        fail(IllegalStateException("Actual still capture lost a requested output buffer"))
                                                    }
                                                }, handler)
                                            } catch (error: Throwable) { fail(error) }
                                        }
                                    }
                                }
                                try {
                                    configured.setRepeatingRequest(device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                        .apply { addTarget(preview.surface) }.build(), callback, handler)
                                } catch (error: Throwable) { fail(error) }
                            }
                            override fun onConfigureFailed(configured: CameraCaptureSession) {
                                configured.close(); fail(IllegalStateException("The camera rejected concurrent PRIVATE preview and YUV still streams"))
                            }
                        }, handler)
                    } catch (error: Throwable) { fail(error) }
                }
                override fun onDisconnected(device: CameraDevice) {
                    device.close(); fail(IllegalStateException("The camera disconnected during still-color verification"))
                }
                override fun onError(device: CameraDevice, error: Int) {
                    device.close(); fail(IllegalStateException("The camera reported still-color error $error"))
                }
            }, handler)
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0 && completed.await(remaining, TimeUnit.MILLISECONDS)) {
                "Concurrent PRIVATE preview and YUV still capture did not complete within ${CAPTURE_TIMEOUT_MS / 1000} seconds"
            }
            failure.get()?.let { throw IllegalStateException("Actual guest still-color verification failed: ${it.message}", it) }
            val proof = requireNotNull(result.get()) { "No actual YUV still sample was received" }
            check(previewFrames.get() >= REQUIRED_PREVIEW_FRAMES && preview.lastTimestamp.get() > preview.firstTimestamp.get()) {
                "PRIVATE preview did not consume three advancing camera buffers"
            }
            check(proof.metrics.getDouble("zeroChromaFraction") < 0.90) {
                "Actual YUV still chroma became near-zero after PRIVATE preview resizing"
            }
            val metadata = requireNotNull(stillResult.get())
            proof.metrics.put("cameraId", cameraId).put("previewFrames", previewFrames.get())
                .put("previewFramesAtShutter", framesAtShutter.get()).put("completedPreviewRequests", previewRequests.get())
                .put("previewWidth", previewWidth).put("previewHeight", previewHeight)
                .put("firstPreviewTimestampNs", preview.firstTimestamp.get()).put("lastPreviewTimestampNs", preview.lastTimestamp.get())
                .put("stillSensorTimestampNs", metadata.get(TotalCaptureResult.SENSOR_TIMESTAMP))
                .put("stillFrameNumber", metadata.frameNumber).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                .put("requestTemplate", "TEMPLATE_STILL_CAPTURE")
                .put("previewConsumer", "SurfaceTexture updated on a dedicated EGL thread")
                .put("jpegEvidence", "JPEG encoded from measured YUV samples for calibration comparison; independent of HAL JPEG verification")
            return proof
        } catch (error: Throwable) { primaryError = error; throw error }
        finally {
            closing.set(true)
            var interrupted = Thread.interrupted() || primaryError is InterruptedException
            val released = CountDownLatch(1)
            val cleanupFailure = AtomicReference<Throwable?>()
            if (!handler.post {
                try {
                    runCatching { session.get()?.stopRepeating() }
                    runCatching { session.get()?.abortCaptures() }
                    session.getAndSet(null)?.close()
                    camera.getAndSet(null)?.close()
                    reader?.setOnImageAvailableListener(null, null)
                    reader?.close()
                } catch (error: Throwable) { cleanupFailure.compareAndSet(null, error) }
                finally { released.countDown() }
            }) released.countDown()
            try {
                try {
                    if (!released.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                        cleanupFailure.compareAndSet(null, IllegalStateException("Still-color camera callbacks did not drain during cleanup"))
                } catch (error: InterruptedException) { interrupted = true; cleanupFailure.compareAndSet(null, error) }
                try { preview.close() }
                catch (error: Throwable) {
                    if (error is InterruptedException) interrupted = true
                    cleanupFailure.compareAndSet(null, error)
                }
                callbackThread.quitSafely()
                if (Thread.currentThread() !== callbackThread) try { callbackThread.join(CLOSE_TIMEOUT_MS) }
                catch (error: InterruptedException) { interrupted = true; cleanupFailure.compareAndSet(null, error) }
                cleanupFailure.get()?.let { error -> if (primaryError != null) primaryError!!.addSuppressed(error) else throw error }
            } finally { if (interrupted) Thread.currentThread().interrupt() }
        }
    }

    private data class Plane(val buffer: ByteBuffer, val start: Int, val limit: Int, val row: Int, val step: Int) {
        fun value(x: Int, y: Int): Int {
            check(x >= 0 && y >= 0)
            val offset = start.toLong() + y.toLong() * row + x.toLong() * step
            check(offset >= start && offset < limit && offset <= Int.MAX_VALUE) { "A reported YUV stride addresses an unavailable plane byte" }
            return buffer.get(offset.toInt()).toInt() and 255
        }
    }

    private fun readSample(image: Image): Sample {
        check(image.format == ImageFormat.YUV_420_888 && image.planes.size == 3 && image.timestamp > 0)
        val crop = image.cropRect
        check(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height &&
            crop.width() > 0 && crop.height() > 0) { "The actual YUV crop is outside its image" }
        val layouts = JSONArray()
        val planes = image.planes.mapIndexed { index, plane ->
            val buffer = plane.buffer.duplicate()
            check(plane.rowStride > 0 && plane.pixelStride > 0 && buffer.remaining() > 0)
            layouts.put(JSONObject().put("component", listOf("Y", "U", "V")[index])
                .put("rowStride", plane.rowStride).put("pixelStride", plane.pixelStride)
                .put("position", buffer.position()).put("limit", buffer.limit()).put("capacity", buffer.capacity()))
            Plane(buffer, buffer.position(), buffer.limit(), plane.rowStride, plane.pixelStride).also {
                val divisor = if (index == 0) 1 else 2
                it.value(crop.left / divisor, crop.top / divisor)
                it.value((crop.right - 1) / divisor, (crop.bottom - 1) / divisor)
            }
        }
        val pixels = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
        val sampledRgb = JSONArray()
        val yuvSum = LongArray(3)
        val rgbSum = LongArray(3)
        var zeroChroma = 0
        for (y in 0 until SAMPLE_HEIGHT) for (x in 0 until SAMPLE_WIDTH) {
            val absoluteX = crop.left + ((2L * x + 1) * crop.width() / (2 * SAMPLE_WIDTH)).toInt()
            val absoluteY = crop.top + ((2L * y + 1) * crop.height() / (2 * SAMPLE_HEIGHT)).toInt()
            val yy = planes[0].value(absoluteX, absoluteY)
            val uu = planes[1].value(absoluteX / 2, absoluteY / 2)
            val vv = planes[2].value(absoluteX / 2, absoluteY / 2)
            if (uu < 16 && vv < 16) zeroChroma++
            val c = yy - 16; val d = uu - 128; val e = vv - 128
            val r = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
            pixels[y * SAMPLE_WIDTH + x] = Color.rgb(r, g, b)
            sampledRgb.put(JSONArray(listOf(r, g, b)))
            yuvSum[0] += yy.toLong(); yuvSum[1] += uu.toLong(); yuvSum[2] += vv.toLong()
            rgbSum[0] += r.toLong(); rgbSum[1] += g.toLong(); rgbSum[2] += b.toLong()
        }
        val count = pixels.size.toDouble()
        return Sample(JSONObject().put("width", image.width).put("height", image.height).put("timestampNs", image.timestamp)
            .put("crop", JSONObject().put("left", crop.left).put("top", crop.top).put("right", crop.right).put("bottom", crop.bottom))
            .put("planes", layouts).put("sampleWidth", SAMPLE_WIDTH).put("sampleHeight", SAMPLE_HEIGHT)
            .put("sampledRgb", sampledRgb).put("sampleCount", pixels.size)
            .put("meanY", yuvSum[0] / count).put("meanU", yuvSum[1] / count).put("meanV", yuvSum[2] / count)
            .put("meanR", rgbSum[0] / count).put("meanG", rgbSum[1] / count).put("meanB", rgbSum[2] / count)
            .put("zeroChromaFraction", zeroChroma / count)
            .put("statistics", "32 by 18 cell-center samples inside the actual crop")
            .put("colorConversion", "BT.601 limited range with each actual plane's independent row/pixel strides"), pixels)
    }

    private class PrivatePreview(private val width: Int, private val height: Int,
        private val frames: AtomicInteger, private val failure: (Throwable) -> Unit) {
        private val thread = HandlerThread("GuestStillColorGl").apply { start() }
        private val handler = Handler(thread.looper)
        private val closing = AtomicBoolean()
        val firstTimestamp = AtomicLong()
        val lastTimestamp = AtomicLong()
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var context: EGLContext = EGL14.EGL_NO_CONTEXT
        private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
        private var textureName = 0
        private var texture: SurfaceTexture? = null
        lateinit var surface: Surface; private set

        fun start(deadline: Long) {
            val ready = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            check(handler.post {
                try {
                    display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                    check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1))
                    val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
                    check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0) && count[0] > 0)
                    val config = requireNotNull(configs[0])
                    context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                        intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
                    check(context != EGL14.EGL_NO_CONTEXT)
                    pbuffer = EGL14.eglCreatePbufferSurface(display, config,
                        intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                    check(pbuffer != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context))
                    val names = IntArray(1); GLES20.glGenTextures(1, names, 0); textureName = names[0]
                    check(textureName != 0)
                    GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                    GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                    check(GLES20.glGetError() == GLES20.GL_NO_ERROR)
                    texture = SurfaceTexture(textureName).apply {
                        setDefaultBufferSize(width, height)
                        setOnFrameAvailableListener({ available ->
                            if (!closing.get()) try {
                                available.updateTexImage()
                                val timestamp = available.timestamp
                                if (timestamp > lastTimestamp.get()) {
                                    firstTimestamp.compareAndSet(0L, timestamp)
                                    lastTimestamp.set(timestamp); frames.incrementAndGet()
                                } else check(timestamp == lastTimestamp.get()) { "PRIVATE preview timestamps went backwards" }
                            } catch (error: Throwable) { failure(error) }
                        }, handler)
                    }
                    surface = Surface(requireNotNull(texture))
                } catch (exception: Throwable) { error.set(exception) }
                finally { ready.countDown() }
            })
            val remaining = minOf(4000L, deadline - SystemClock.elapsedRealtime())
            check(remaining > 0 && ready.await(remaining, TimeUnit.MILLISECONDS)) { "The real PRIVATE preview consumer could not initialize" }
            error.get()?.let { throw it }
        }

        fun close() {
            closing.set(true)
            val released = CountDownLatch(1)
            val error = AtomicReference<Throwable?>()
            if (!handler.post {
                try {
                    texture?.setOnFrameAvailableListener(null)
                    if (::surface.isInitialized) surface.release()
                    texture?.release()
                    if (display != EGL14.EGL_NO_DISPLAY) {
                        if (textureName != 0 && context != EGL14.EGL_NO_CONTEXT) GLES20.glDeleteTextures(1, intArrayOf(textureName), 0)
                        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
                        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                        EGL14.eglTerminate(display); EGL14.eglReleaseThread()
                    }
                } catch (exception: Throwable) { error.set(exception) }
                finally { released.countDown() }
            }) released.countDown()
            try { check(released.await(CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) { "The PRIVATE preview GL consumer did not release" } }
            finally { thread.quitSafely(); thread.join(CLOSE_TIMEOUT_MS) }
            error.get()?.let { throw it }
        }
    }

    companion object {
        private const val CAPTURE_TIMEOUT_MS = 15_000L
        private const val CLOSE_TIMEOUT_MS = 2000L
        private const val REQUIRED_PREVIEW_FRAMES = 3
        private const val SAMPLE_WIDTH = 32
        private const val SAMPLE_HEIGHT = 18
    }
}
