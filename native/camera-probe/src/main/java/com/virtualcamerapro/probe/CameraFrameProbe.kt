package com.virtualcamerapro.probe

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

data class CameraFrameProof(
    val cameraId: String,
    val previewFrames: Int,
    val firstTimestamp: Long,
    val lastTimestamp: Long,
    val jpeg: ByteArray,
    val centerLuma: List<Int>,
    val previewWidth: Int,
    val previewHeight: Int,
    val jpegWidth: Int,
    val jpegHeight: Int,
    val centerTimestampsNs: List<Long> = emptyList(),
    val streamStrategy: String = "combined"
) {
    fun metrics() = JSONObject().put("cameraId", cameraId).put("previewFrames", previewFrames)
        .put("firstTimestampNs", firstTimestamp).put("lastTimestampNs", lastTimestamp)
        .put("jpegBytes", jpeg.size)
        .put("previewWidth", previewWidth).put("previewHeight", previewHeight)
        .put("jpegWidth", jpegWidth).put("jpegHeight", jpegHeight)
        .put("streamStrategy", streamStrategy)
}

/** Public Camera2 consumer. Opening a camera or receiving a callback alone is not proof. */
class CameraFrameProbe(private val context: Context) {
    @Suppress("MissingPermission", "DEPRECATION")
    fun capture(cameraId: String, sampleDurationMs: Long = 0, preferredWidth: Int = 1280, preferredHeight: Int = 720,
        streamStrategy: String = "combined", timeoutMs: Long = sampleDurationMs + 12000): CameraFrameProof {
        require(streamStrategy in setOf("combined", "sequential")) { "Unknown Camera2 verification stream strategy" }
        require(sampleDurationMs >= 0 && timeoutMs > 0 && preferredWidth > 0 && preferredHeight > 0) { "Invalid camera verification dimensions or timeout" }
        val waitBudgetMs = minOf(timeoutMs, sampleDurationMs + 12000)
        val manager = context.getSystemService(CameraManager::class.java)
        val map = requireNotNull(manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)) { "No output stream map for $cameraId" }
        fun sizes(format: Int) = requireNotNull(map.getOutputSizes(format)) { "Camera $cameraId has no format $format" }
            .filter { it.width > 0 && it.height > 0 && it.width.toLong()*it.height <= 8_847_360 }
        fun nearest(choices: List<android.util.Size>) = choices.minWithOrNull(compareBy<android.util.Size> {
            kotlin.math.abs(kotlin.math.ln((it.width.toDouble()/it.height)/(preferredWidth.toDouble()/preferredHeight)))
        }.thenBy { abs(it.width.toLong()*it.height-preferredWidth.toLong()*preferredHeight) })
            ?: error("Camera $cameraId reports no usable output sizes within the frame-memory limit")
        val yuvSizes = sizes(ImageFormat.YUV_420_888)
        val jpegSizes = sizes(ImageFormat.JPEG)
        val common = yuvSizes.filter { it in jpegSizes }
        require(streamStrategy != "sequential" || common.isNotEmpty()) {
            "Camera $cameraId has no common YUV/JPEG size for sequential verification"
        }
        val yuvSize = nearest(common.ifEmpty { yuvSizes })
        val jpegSize = if (common.isNotEmpty()) yuvSize else nearest(jpegSizes)
        val yuv = ImageReader.newInstance(yuvSize.width, yuvSize.height, ImageFormat.YUV_420_888, 3)
        val jpeg = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2)
        val thread = HandlerThread("VirtualCameraPipelineProbe").apply { start() }
        val handler = Handler(thread.looper)
        val failure = AtomicReference<Throwable?>()
        val device = AtomicReference<CameraDevice?>()
        val session = AtomicReference<CameraCaptureSession?>()
        val closing = AtomicBoolean(false)
        val previewClosing = AtomicBoolean(false)
        val done = CountDownLatch(1)
        var previewFrames = 0
        var firstTimestamp = 0L
        var lastTimestamp = 0L
        var startedMs = 0L
        var jpegBytes: ByteArray? = null
        var jpegWidth = 0
        var jpegHeight = 0
        val luma = mutableListOf<Int>()
        val centerTimestamps = mutableListOf<Long>()
        val previewRequestTag = Any()
        fun fail(e: Throwable) { if (!closing.get()) { failure.compareAndSet(null, e); done.countDown() } }
        fun completeIfReady() {
            if (previewFrames < 3 || SystemClock.elapsedRealtime() - startedMs < sampleDurationMs) return
            if (jpegBytes != null) done.countDown()
            else if (streamStrategy == "sequential" && previewClosing.compareAndSet(false, true)) {
                // The JPEG-only session is created by onClosed, after Camera2
                // has released the previous YUV-only session and its outputs.
                val previous = requireNotNull(session.get()) { "The YUV verification session disappeared" }
                previous.stopRepeating()
                previous.close()
            }
        }
        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureFailed(s: CameraCaptureSession, request: CaptureRequest, result: CaptureFailure) {
                // Closing the deliberately completed YUV session may flush an
                // outstanding preview request. Its verified frames remain valid.
                if (streamStrategy == "sequential" && previewClosing.get() && request.tag === previewRequestTag) return
                fail(IllegalStateException("Camera $cameraId capture failed: reason=${result.reason}"))
            }
        }
        yuv.setOnImageAvailableListener({ reader ->
            try { reader.acquireNextImage()?.use { image ->
                if (closing.get()) return@use
                check(image.planes.size == 3 && image.planes.all { it.buffer.remaining() > 0 }) { "Empty YUV planes from $cameraId" }
                check(image.timestamp > lastTimestamp) { "Non-increasing camera timestamps for $cameraId" }
                if (previewFrames == 0) { firstTimestamp = image.timestamp; startedMs = SystemClock.elapsedRealtime() }
                lastTimestamp = image.timestamp; previewFrames++
                val plane = image.planes[0]
                val offset = plane.buffer.position().toLong() + (image.height / 2).toLong()*plane.rowStride + (image.width / 2).toLong()*plane.pixelStride
                check(offset >= plane.buffer.position() && offset < plane.buffer.limit()) { "YUV plane strides exceed the actual buffer for $cameraId" }
                luma.add(plane.buffer.get(offset.toInt()).toInt() and 255)
                centerTimestamps.add(image.timestamp)
                completeIfReady()
            } } catch (e: Exception) { fail(e) }
        }, handler)
        jpeg.setOnImageAvailableListener({ reader ->
            try { reader.acquireNextImage()?.use { image ->
                if (closing.get()) return@use
                val bytes = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera $cameraId returned invalid JPEG data" }
                jpegWidth = bounds.outWidth; jpegHeight = bounds.outHeight
                jpegBytes = bytes; completeIfReady()
            } } catch (e: Exception) { fail(e) }
        }, handler)
        fun configureSession(camera: CameraDevice, jpegOnly: Boolean) {
            if (closing.get() || failure.get() != null) return
            val outputs = when {
                streamStrategy == "combined" -> listOf(yuv.surface, jpeg.surface)
                jpegOnly -> listOf(jpeg.surface)
                else -> listOf(yuv.surface)
            }
            try { camera.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (closing.get() || failure.get() != null) { s.close(); return }
                    session.set(s)
                    if (closing.get()) { session.getAndSet(null)?.close(); return }
                    try {
                        if (!jpegOnly) s.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            .apply { addTarget(yuv.surface); setTag(previewRequestTag) }.build(), captureCallback, handler)
                        if (streamStrategy == "combined" || jpegOnly) s.capture(
                            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(jpeg.surface); set(CaptureRequest.JPEG_ORIENTATION, 0)
                            }.build(), captureCallback, handler)
                    } catch (e: Exception) { fail(e) }
                }
                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    fail(IllegalStateException("Camera $cameraId rejected $streamStrategy ${if (jpegOnly) "JPEG" else "YUV"} verification streams"))
                }
                override fun onClosed(s: CameraCaptureSession) {
                    if (session.compareAndSet(s, null) && streamStrategy == "sequential" && !jpegOnly &&
                        previewClosing.get() && !closing.get() && failure.get() == null) configureSession(camera, true)
                }
            }, handler) } catch (e: Exception) { fail(e) }
        }
        try {
            Log.i("VirtualCameraProbe", "event=open cameraId=$cameraId yuv=$yuvSize jpeg=$jpegSize strategy=$streamStrategy timeoutMs=$waitBudgetMs")
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closing.get()) { camera.close(); return }
                    device.set(camera)
                    if (closing.get()) { device.getAndSet(null)?.close(); return }
                    configureSession(camera, false)
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail(IllegalStateException("Camera $cameraId disconnected during verification")) }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); fail(IllegalStateException("Camera $cameraId open error $error")) }
            }, handler)
            check(done.await(waitBudgetMs, TimeUnit.MILLISECONDS)) { "Camera $cameraId did not deliver actual YUV and JPEG frames with $streamStrategy streams within ${waitBudgetMs}ms" }
            val snapshot = FutureTask {
                failure.get()?.let { throw IllegalStateException(it.message, it) }
                CameraFrameProof(cameraId, previewFrames, firstTimestamp, lastTimestamp, requireNotNull(jpegBytes), luma.toList(), yuvSize.width, yuvSize.height, jpegWidth, jpegHeight, centerTimestamps.toList(), streamStrategy)
            }
            check(handler.post(snapshot)) { "Camera verification callback thread stopped" }
            val result = snapshot.get(2, TimeUnit.SECONDS)
            Log.i("VirtualCameraProbe", "event=verified cameraId=$cameraId previewFrames=${result.previewFrames} jpegBytes=${result.jpeg.size} strategy=$streamStrategy")
            return result
        } finally {
            closing.set(true)
            runCatching { session.get()?.stopRepeating() }; runCatching { session.get()?.abortCaptures() }
            session.getAndSet(null)?.close(); device.getAndSet(null)?.close()
            // Drain callbacks before releasing their ImageReaders.
            val drained = CountDownLatch(1)
            handler.post { drained.countDown() }; runCatching { drained.await(2, TimeUnit.SECONDS) }
            yuv.close(); jpeg.close(); thread.quitSafely()
        }
    }
}
