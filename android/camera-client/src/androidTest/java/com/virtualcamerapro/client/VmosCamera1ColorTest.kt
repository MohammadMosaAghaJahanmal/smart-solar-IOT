package com.virtualcamerapro.client

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES11Ext
import android.opengl.GLES20
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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Diagnostic Camera1 consumer. Saves original bytes without recompressing a photo. */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class VmosCamera1ColorTest {
    @Test fun actualCamera1PreviewAndPicture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals(28, Build.VERSION.SDK_INT); assertEquals("vphw90", Build.HARDWARE)
        assertTrue("arm64-v8a" in Build.SUPPORTED_ABIS)
        assertNotEquals(0, Process.myUid()); assertNotEquals(1000, Process.myUid())
        assertEquals("Grant Camera permission to the independent guest client first", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission(Manifest.permission.CAMERA))
        val cameraId = args.getString("cameraId", "1").toInt()
        val requestedWidth = args.getString("width", "1920").toInt()
        val requestedHeight = args.getString("height", "1080").toInt()
        val requestedPreviewWidth = args.getString("previewWidth", "1280").toInt()
        val requestedPreviewHeight = args.getString("previewHeight", "720").toInt()
        val rotation = args.getString("rotation", "0").toInt()
        require(cameraId in 0 until Camera.getNumberOfCameras() && rotation in listOf(0, 90, 180, 270))
        val directory = File(context.getExternalFilesDir(null), "camera1-color/camera-$cameraId-${requestedWidth}x$requestedHeight-rot$rotation").apply { mkdirs() }
        val report = JSONObject().put("passed", false).put("cameraId", cameraId).put("consumerUid", Process.myUid())
            .put("requestedPictureWidth", requestedWidth).put("requestedPictureHeight", requestedHeight)
            .put("requestedPreviewWidth", requestedPreviewWidth).put("requestedPreviewHeight", requestedPreviewHeight).put("rotation", rotation)
        val thread = HandlerThread("ActualCamera1ColorProbe").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val closing = AtomicBoolean()
        var camera: Camera? = null
        var target: PreviewTarget? = null
        var callbacks = 0
        var shutter = false
        var configuredWidth = 0
        var configuredHeight = 0
        val callbackTimes = JSONArray()
        fun fail(error: Throwable) { failure.compareAndSet(null, error); done.countDown() }
        handler.post {
            try {
                val opened = Camera.open(cameraId); camera = opened
                opened.setErrorCallback { code, _ -> fail(IllegalStateException("Camera1 error $code")) }
                val parameters = opened.parameters
                File(directory, "parameters-before.txt").writeText(parameters.flatten())
                fun sizes(items: List<Camera.Size>?) = JSONArray().also { array -> items?.forEach { array.put("${it.width}x${it.height}") } }
                report.put("supportedPreviewSizes", sizes(parameters.supportedPreviewSizes))
                    .put("supportedPictureSizes", sizes(parameters.supportedPictureSizes))
                    .put("supportedPreviewFormats", JSONArray(parameters.supportedPreviewFormats))
                    .put("requestedPictureAdvertised", parameters.supportedPictureSizes.any { it.width == requestedWidth && it.height == requestedHeight })
                fun choose(items: List<Camera.Size>, width: Int, height: Int): Camera.Size =
                    items.firstOrNull { it.width == width && it.height == height }
                        ?: items.minBy { abs(it.width.toLong() * it.height - width.toLong() * height) }
                val preview = choose(parameters.supportedPreviewSizes, requestedPreviewWidth, requestedPreviewHeight)
                val picture = choose(parameters.supportedPictureSizes, requestedWidth, requestedHeight)
                parameters.setPreviewSize(preview.width, preview.height)
                parameters.previewFormat = ImageFormat.NV21
                parameters.setPictureSize(picture.width, picture.height)
                parameters.pictureFormat = ImageFormat.JPEG
                parameters.jpegQuality = 95
                parameters.setRotation(rotation)
                opened.parameters = parameters
                val configured = opened.parameters
                configuredWidth = configured.pictureSize.width; configuredHeight = configured.pictureSize.height
                val previewWidth = configured.previewSize.width; val previewHeight = configured.previewSize.height
                report.put("configuredPictureWidth", configuredWidth).put("configuredPictureHeight", configuredHeight)
                    .put("configuredPreviewWidth", previewWidth).put("configuredPreviewHeight", previewHeight)
                    .put("configuredPreviewFormat", configured.previewFormat).put("configuredPictureFormat", configured.pictureFormat)
                File(directory, "parameters-configured.txt").writeText(configured.flatten())
                check(configured.previewFormat == ImageFormat.NV21) { "Camera1 did not accept NV21 preview" }
                val output = PreviewTarget(previewWidth, previewHeight, directory)
                target = output
                output.texture.setOnFrameAvailableListener({
                    if (!closing.get()) try { output.drain() } catch (error: Throwable) { fail(error) }
                }, handler)
                opened.setPreviewTexture(output.texture)
                opened.setPreviewCallback { bytes, source ->
                    if (!closing.get()) try {
                        check(bytes != null && bytes.size >= previewWidth * previewHeight * 3 / 2) { "Camera1 returned a short NV21 preview buffer" }
                        callbacks++; callbackTimes.put(SystemClock.elapsedRealtimeNanos())
                        if (callbacks == 3) {
                            File(directory, "preview.nv21").writeBytes(bytes)
                            report.put("previewBytes", bytes.size).put("previewExpectedPackedBytes", previewWidth * previewHeight * 3 / 2)
                                .put("previewNv21Samples", nv21Samples(bytes, previewWidth, previewHeight))
                        }
                        if (callbacks >= 3 && !shutter) {
                            shutter = true
                            handler.postDelayed({
                                if (!closing.get()) try {
                                    source.setPreviewCallback(null)
                                    source.takePicture(null, null, Camera.PictureCallback { jpeg, _ ->
                                        try {
                                            check(jpeg != null && jpeg.isNotEmpty()) { "Camera1 returned no JPEG bytes" }
                                            File(directory, "capture.jpg").writeBytes(jpeg)
                                            val decoded = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)) { "Camera1 JPEG did not decode" }
                                            try {
                                                report.put("jpegBytes", jpeg.size).put("jpegWidth", decoded.width).put("jpegHeight", decoded.height)
                                                check((decoded.width == configuredWidth && decoded.height == configuredHeight) ||
                                                    (rotation % 180 != 0 && decoded.width == configuredHeight && decoded.height == configuredWidth)) { "The Camera1 JPEG dimensions do not match the actual configured picture size" }
                                                val colors = JSONArray()
                                                for (row in 0..4) for (column in 0..4) {
                                                    val x = (column * (decoded.width - 1)) / 4; val y = (row * (decoded.height - 1)) / 4
                                                    val color = decoded.getPixel(x, y)
                                                    colors.put(JSONObject().put("x", x).put("y", y).put("r", Color.red(color)).put("g", Color.green(color)).put("b", Color.blue(color)))
                                                }
                                                report.put("jpegColorSamples", colors)
                                                check(decoded.width > 0 && decoded.height > 0)
                                            } finally { decoded.recycle() }
                                            File(directory, "parameters-after.txt").writeText(source.parameters.flatten())
                                            done.countDown()
                                        } catch (error: Throwable) { fail(error) }
                                    })
                                } catch (error: Throwable) { fail(error) }
                            }, 150)
                        }
                    } catch (error: Throwable) { fail(error) }
                }
                opened.startPreview()
            } catch (error: Throwable) { fail(error) }
        }
        try {
            assertTrue("Camera1 did not deliver NV21 preview and JPEG in 45 seconds", done.await(45, TimeUnit.SECONDS))
            failure.get()?.let { throw AssertionError("Actual Camera1 pipeline failed", it) }
            assertTrue(callbacks >= 3)
            assertTrue(File(directory, "capture.jpg").length() > 0)
            assertTrue(File(directory, "preview.nv21").length() > 0)
            assertTrue("The actual SurfaceTexture must deliver GPU preview frames", (target?.timestamps?.length() ?: 0) > 0)
            assertTrue("The actual GPU preview must be saved for independent color analysis", File(directory, "preview.rgba").length() > 0)
            report.put("passed", true)
        } catch (error: Throwable) { report.put("failure", error.toString()); throw error }
        finally {
            closing.set(true)
            val released = CountDownLatch(1)
            handler.post {
                try {
                    report.put("previewCallbacks", callbacks).put("previewCallbackBootTimesNs", callbackTimes)
                        .put("previewSurfaceTimestampsNs", target?.timestamps ?: JSONArray())
                        .put("previewRgbaFormat", "RGBA8888, OpenGL bottom-left origin after actual SurfaceTexture transform")
                    runCatching { camera?.setPreviewCallback(null) }; runCatching { camera?.stopPreview() }; runCatching { camera?.release() }
                    runCatching { target?.close() }
                } finally { released.countDown() }
            }
            released.await(3, TimeUnit.SECONDS)
            File(directory, "metrics.json").writeText(report.toString(2))
            thread.quitSafely()
        }
    }

    private fun nv21Samples(bytes: ByteArray, width: Int, height: Int): JSONArray = JSONArray().also { samples ->
        for (row in listOf(0, height / 2, height - 1)) {
            var luma = 0L
            for (column in 0 until width) luma += bytes[row * width + column].toInt() and 255
            val uvRow = (row / 2).coerceAtMost(height / 2 - 1)
            var u = 0L; var v = 0L
            for (column in 0 until width step 2) {
                val at = width * height + uvRow * width + column
                v += bytes[at].toInt() and 255; u += bytes[at + 1].toInt() and 255
            }
            samples.put(JSONObject().put("row", row).put("meanY", luma.toDouble() / width)
                .put("meanU", u.toDouble() / (width / 2)).put("meanV", v.toDouble() / (width / 2)))
        }
    }

    private class PreviewTarget(private val width: Int, private val height: Int, private val directory: File) : AutoCloseable {
        private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: android.opengl.EGLContext
        private val surface: android.opengl.EGLSurface
        private val program: Int
        private val textureId: Int
        val texture: SurfaceTexture
        val timestamps = JSONArray()
        private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
        }
        init {
            check(EGL14.eglInitialize(display, null, 0, null, 0))
            val choices = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE), 0, choices, 0, 1, count, 0) && count[0] > 0)
            val config = requireNotNull(choices[0])
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context))
            val names = IntArray(1); GLES20.glGenTextures(1, names, 0); textureId = names[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            texture = SurfaceTexture(textureId).apply { setDefaultBufferSize(width, height) }
            fun shader(kind: Int, source: String): Int {
                val shader = GLES20.glCreateShader(kind); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader)
                val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
                check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }; return shader
            }
            val vertex = shader(GLES20.GL_VERTEX_SHADER, "attribute vec2 p;attribute vec2 t;uniform mat4 matrix;varying vec2 uv;void main(){gl_Position=vec4(p,0.,1.);uv=(matrix*vec4(t,0.,1.)).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER, "#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES image;varying vec2 uv;void main(){gl_FragColor=texture2D(image,uv);}")
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment); GLES20.glLinkProgram(program)
            val status = IntArray(1); GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(program) }
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
        }
        fun drain() {
            texture.updateTexImage(); timestamps.put(texture.timestamp)
            if (timestamps.length() != 3) return
            val transform = FloatArray(16); texture.getTransformMatrix(transform)
            GLES20.glViewport(0, 0, width, height); GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "matrix"), 1, false, transform, 0)
            val position = GLES20.glGetAttribLocation(program, "p"); val texcoord = GLES20.glGetAttribLocation(program, "t")
            vertices.position(0); GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(position)
            vertices.position(2); GLES20.glVertexAttribPointer(texcoord, 2, GLES20.GL_FLOAT, false, 16, vertices); GLES20.glEnableVertexAttribArray(texcoord)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            val bytes = ByteBuffer.allocateDirect(width * height * 4)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, bytes)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "Actual Camera1 GPU preview readback failed" }
            bytes.position(0)
            File(directory, "preview.rgba").outputStream().channel.use { channel -> while (bytes.hasRemaining()) channel.write(bytes) }
            File(directory, "preview-texture-transform.json").writeText(JSONArray(transform.toList()).toString())
        }
        override fun close() {
            texture.release(); GLES20.glDeleteProgram(program); GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
        }
    }
}
