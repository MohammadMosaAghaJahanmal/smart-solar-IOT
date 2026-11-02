package com.virtualcamerapro.engine

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Decode straight to an external GPU texture; no pixel data crosses Binder or JS. */
class VideoFrameSource(file: File, handler: Handler) : AutoCloseable {
    private val extractor = MediaExtractor()
    private lateinit var decoder: MediaCodec
    private lateinit var surfaceTexture: SurfaceTexture
    private lateinit var surface: Surface
    private val available = AtomicBoolean(false)
    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var pending = -1
    private var epochNs = 0L
    private var firstPtsUs = 0L
    var hasFrame = false; private set
    var loop = true
    var texture: Int = 0; private set
    var width: Int = 0; private set
    var height: Int = 0; private set
    var rotation: Int = 0; private set
    val transform = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
    init {
      try {
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: error("No supported video track")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        width = format.getInteger(MediaFormat.KEY_WIDTH); height = format.getInteger(MediaFormat.KEY_HEIGHT)
        rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) format.getInteger(MediaFormat.KEY_ROTATION) else 0
        require(width in 1..8192 && height in 1..8192) { "Video dimensions are too large" }
        format.setInteger(MediaFormat.KEY_ROTATION, 0)
        val names = IntArray(1); GLES20.glGenTextures(1, names, 0); texture = names[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        FrameRenderer.textureParameters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setDefaultBufferSize(width, height)
        surfaceTexture.setOnFrameAvailableListener({ available.set(true) }, handler)
        surface = Surface(surfaceTexture)
        decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        decoder.configure(format, surface, null, 0); decoder.start()
      } catch (e: Exception) { close(); throw e }
    }
    fun advance(nowNs: Long) {
        if (available.getAndSet(false)) { surfaceTexture.updateTexImage(); surfaceTexture.getTransformMatrix(transform); hasFrame = true }
        if (outputEnded) {
            if (!loop) return
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            decoder.flush(); inputEnded = false; outputEnded = false; epochNs = 0L; pending = -1
        }
        repeat(4) {
            if (!inputEnded) {
                val index = decoder.dequeueInputBuffer(0)
                if (index >= 0) {
                    val size = extractor.readSampleData(requireNotNull(decoder.getInputBuffer(index)), 0)
                    if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                    else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                }
            }
        }
        if (pending < 0) pending = decoder.dequeueOutputBuffer(info, 0)
        if (pending >= 0) {
            if (epochNs == 0L) { epochNs = nowNs; firstPtsUs = info.presentationTimeUs }
            val due = epochNs + (info.presentationTimeUs - firstPtsUs) * 1000
            if (nowNs >= due) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                decoder.releaseOutputBuffer(pending, info.size > 0)
                pending = -1
                if (eos) outputEnded = true
            }
        }
    }
    override fun close() {
        if (this::decoder.isInitialized) { runCatching { decoder.stop() }; runCatching { decoder.release() } }
        runCatching { extractor.release() }
        if (this::surface.isInitialized) surface.release()
        if (this::surfaceTexture.isInitialized) surfaceTexture.release()
        if (texture != 0) { GLES20.glDeleteTextures(1,intArrayOf(texture),0); texture = 0 }
    }
}
