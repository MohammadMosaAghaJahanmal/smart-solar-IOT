package com.virtualcamerapro.broker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.virtualcamerapro.engine.CameraOutputManager
import com.virtualcamerapro.engine.OutputSettings
import java.io.File
import java.io.FileOutputStream
import java.io.DataOutputStream
import android.system.Os
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Shared native decoder/EGL renderer; neither source pixels nor video frames enter JavaScript. */
internal class GuestFrameProducer(
    private val directory: File,
    val sourceHash: String,
    private val cameraName: String,
    initialSettings: OutputSettings,
    private val cameraNames: List<String> = listOf(cameraName),
    private val onError: (Throwable) -> Unit
) : AutoCloseable {
    init {
        require(cameraNames.isNotEmpty() && cameraNames.size <= 32 && cameraNames.distinct().size == cameraNames.size && cameraName in cameraNames)
        require((initialSettings.replacementMode == "both") == (cameraNames.size > 1))
        require(cameraNames.all { it.matches(Regex("[A-Za-z0-9_.-]{1,128}")) })
    }
    @Volatile var settings = initialSettings; private set
    val generation = UUID.randomUUID().toString()
    private val raw = listOf(File(directory, "$generation-0.raw"), File(directory, "$generation-1.raw"))
    private val thread = HandlerThread("VmosCameraRing").apply { start() }
    private val handler = Handler(thread.looper)
    private val failure = AtomicReference<Throwable?>()
    private val closed = AtomicBoolean()
    private val ready = CountDownLatch(1)
    private val samples = ArrayDeque<IntArray>()
    @Volatile private var active = true
    private var publication = false
    private var sequence = 0L
    private var currentRaw: File? = null
    private var publishedSettingsRevision = 0L
    private var settingsChangedNs = 0L
    private var mediaType = "image"
    private var reader: ImageReader? = null
    private var output: CameraOutputManager? = null
    private val count = AtomicLong()
    val frames get() = count.get()
    @Volatile var lastFrameNs = 0L; private set
    @Volatile var expiresNs = 0L; private set

    fun start(file: File, type: String) {
        require(type == "image" || type == "video") { "Invalid native media source type" }
        mediaType = type
        val imageReader = ImageReader.newInstance(settings.width, settings.height, PixelFormat.RGBA_8888, 3)
        reader = imageReader
        imageReader.setOnImageAvailableListener({ source ->
            try {
                source.acquireLatestImage()?.use { image ->
                    if (!active) return@use
                    val plane = image.planes.single()
                    check(plane.pixelStride == 4) { "Unexpected RGBA plane pixel stride" }
                    val bytes = ByteArray(settings.width * settings.height * 4)
                    val pixels = plane.buffer.duplicate()
                    for (row in 0 until settings.height) {
                        pixels.position(row * plane.rowStride)
                        pixels.get(bytes, row * settings.width * 4, settings.width * 4)
                    }
                    synchronized(this) {
                        if (!active) return@use
                        val revision = output?.deliveredSettingsRevision ?: 0L
                        if (image.timestamp >= settingsChangedNs && (mediaType == "video" || currentRaw == null || revision > publishedSettingsRevision)) {
                            sequence = count.get()
                            val destination = raw[(sequence % 2).toInt()]
                            writeFrameAtomic(destination, bytes, sequence)
                            currentRaw = destination
                            if (publication) publishConfig()
                            publishedSettingsRevision = revision
                        }
                    }
                    val sample = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
                    for (y in 0 until SAMPLE_HEIGHT) for (x in 0 until SAMPLE_WIDTH) {
                        val at = ((((2 * y + 1) * settings.height / (2 * SAMPLE_HEIGHT))) * settings.width + (2 * x + 1) * settings.width / (2 * SAMPLE_WIDTH)) * 4
                        sample[y * SAMPLE_WIDTH + x] = (0xff shl 24) or ((bytes[at].toInt() and 255) shl 16) or ((bytes[at + 1].toInt() and 255) shl 8) or (bytes[at + 2].toInt() and 255)
                    }
                    synchronized(samples) { samples.addLast(sample); while (samples.size > 120) samples.removeFirst() }
                    lastFrameNs = SystemClock.elapsedRealtimeNanos()
                    count.incrementAndGet()
                    ready.countDown()
                }
                if (active) output?.request(STREAM_ID)
            } catch (error: Throwable) { failure.compareAndSet(null, error); ready.countDown(); onError(error) }
        }, handler)
        val engine = CameraOutputManager { error -> failure.compareAndSet(null, error); ready.countDown(); onError(error) }
        output = engine
        try {
            engine.start(file, type, settings)
            engine.add(STREAM_ID, imageReader.surface, settings.width, settings.height)
            engine.request(STREAM_ID)
            check(ready.await(10, TimeUnit.SECONDS)) { "Native producer returned no RGBA frame" }
            failure.get()?.let { throw it }
            check(frames > 0 && currentRaw?.isFile == true) { "Native producer did not publish an actual RGBA frame" }
        } catch (error: Throwable) { close(); throw error }
    }

    @Synchronized fun suspendPublication() { publication = false }

    /** Keep the decoder, native source generation and camera connections alive. */
    fun updateSettings(next: OutputSettings): Pair<Long, Long> {
        require(next.width == settings.width && next.height == settings.height && next.cameraId == settings.cameraId &&
            next.replacementMode == settings.replacementMode)
        val engine = requireNotNull(output)
        synchronized(this) {
            check(active && publication) { "The guest source is inactive" }
            settingsChangedNs = System.nanoTime()
            settings = next
            synchronized(samples) { samples.clear() }
        }
        val revision = engine.update(next)
        engine.request(STREAM_ID)
        val deadline = SystemClock.elapsedRealtime() + 4000
        while (true) {
            failure.get()?.let { throw it }
            synchronized(this) {
                if (publishedSettingsRevision >= revision && currentRaw?.isFile == true) return revision to sequence
                check(active && publication && SystemClock.elapsedRealtime() < deadline) { "No transformed native frame was atomically published for the requested settings" }
            }
            Thread.sleep(8)
        }
    }

    @Synchronized fun publish() { publication = true; renew() }

    @Synchronized fun renew() {
        failure.get()?.let { throw it }
        check(active && publication && frames > 0 && SystemClock.elapsedRealtimeNanos() - lastFrameNs < 3_000_000_000L) { "Guest media producer stopped delivering frames" }
        expiresNs = SystemClock.elapsedRealtimeNanos() + 15_000_000_000L
        publishConfig()
    }

    /** Each file replacement gets a new inode; an already mapped frame stays valid. */
    private fun publishConfig() {
        val protocol = if (cameraNames.size > 1) "VCAM3" else "VCAM2"
        val routes = if (cameraNames.size > 1) "camera_names=${cameraNames.joinToString(",")}\n" else ""
        val config = "$protocol\nid=$generation\nsequence=$sequence\ncamera_name=$cameraName\n${routes}path=${requireNotNull(currentRaw).absolutePath}\nformat=RGBA\nwidth=${settings.width}\nheight=${settings.height}\nfps=30\nframes=1\nloop=1\nsha256=$sourceHash\nexpires_ns=$expiresNs\n"
        writeFrameAtomic(File(directory, "source.cfg"), config.toByteArray(Charsets.UTF_8))
    }

    private fun writeFrameAtomic(file: File, bytes: ByteArray, frameSequence: Long? = null) {
        val temporary = File(directory, ".${file.name}.next")
        try {
            DataOutputStream(FileOutputStream(temporary)).use {
                if (frameSequence != null) { it.writeBytes("VCAMFR2\n"); it.writeLong(frameSequence) }
                it.write(bytes)
            }
            Os.chmod(temporary.absolutePath, 0x180)
            check(temporary.renameTo(file)) { "Cannot atomically publish the live guest camera frame" }
        } finally { temporary.delete() }
    }

    /** Compare actual Camera2 JPEG pixels to frames decoded from the selected media. */
    fun jpegError(jpeg: ByteArray): Double {
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("Actual camera JPEG is invalid")
        try {
            val references = synchronized(samples) { samples.toList() }
            check(references.isNotEmpty())
            var best = Double.POSITIVE_INFINITY
            // VMOS HALs can apply camera facing and sensor orientation on JPEG.
            for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
                val matrix = android.graphics.Matrix().apply { postRotate(rotation.toFloat()); if (mirror) postScale(-1f, 1f) }
                val oriented = Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
                val small = Bitmap.createScaledBitmap(oriented, SAMPLE_WIDTH, SAMPLE_HEIGHT, true)
                try {
                    val captured = IntArray(SAMPLE_WIDTH * SAMPLE_HEIGHT)
                    small.getPixels(captured, 0, SAMPLE_WIDTH, 0, 0, SAMPLE_WIDTH, SAMPLE_HEIGHT)
                    for (reference in references) {
                        var sum = 0L
                        var contentSum = 0L
                        var contentPixels = 0
                        for (i in captured.indices) {
                            val content = ((reference[i] ushr 16) and 255) > 20 || ((reference[i] ushr 8) and 255) > 20 || (reference[i] and 255) > 20
                            if (content) contentPixels++
                            for (shift in listOf(0, 8, 16)) {
                                val difference = abs(((captured[i] ushr shift) and 255) - ((reference[i] ushr shift) and 255))
                                sum += difference
                                if (content) contentSum += difference
                            }
                        }
                        val fullError = sum.toDouble() / (captured.size * 3)
                        val contentError = if (contentPixels > 0) contentSum.toDouble() / (contentPixels * 3) else fullError
                        best = minOf(best, maxOf(fullError, contentError))
                    }
                } finally { if (small !== oriented) small.recycle(); if (oriented !== image) oriented.recycle() }
            }
            return best
        } finally { image.recycle() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(this) { active = false; publication = false }
        try { output?.close() }
        finally {
            output = null
            reader?.close(); reader = null
            thread.quitSafely()
            try { thread.join(2000) }
            finally { raw.forEach { it.delete() }; synchronized(samples) { samples.clear() } }
        }
    }
    companion object { private const val STREAM_ID = 831; private const val SAMPLE_WIDTH = 32; private const val SAMPLE_HEIGHT = 18 }
}
