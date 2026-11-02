package com.virtualcamerapro.engine

import android.opengl.*
import android.os.Handler
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** All calls are serialized on the engine thread, including Surface destruction. */
class FrameRenderer(private val handler: Handler) : AutoCloseable {
    private val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext
    private val pbuffer: EGLSurface
    private val outputs = mutableMapOf<Int, Target>()
    private val requested = mutableSetOf<Int>()
    private data class Target(val surface: Surface, val egl: EGLSurface, val width: Int, val height: Int)
    private var image: ImageFrameSource? = null
    private var video: VideoFrameSource? = null
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0)
    }
    private val imageProgram: Int
    private val videoProgram: Int
    var settings = OutputSettings()
    var frames = 0L; private set
    val streamCount get() = outputs.size
    val ready get() = image != null || video?.hasFrame == true
    init {
        check(display != EGL14.EGL_NO_DISPLAY && EGL14.eglInitialize(display, null, 0, null, 0)) { "EGL initialization failed" }
        val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE),0,configs,0,1,count,0) && count[0] > 0) { "No RGBA EGL config" }
        config = requireNotNull(configs[0])
        context = EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        pbuffer = EGL14.eglCreatePbufferSurface(display,config,intArrayOf(EGL14.EGL_WIDTH,1,EGL14.EGL_HEIGHT,1,EGL14.EGL_NONE),0)
        makeCurrent(pbuffer)
        imageProgram = program(false); videoProgram = program(true)
    }
    fun source(file: File, type: String) {
        makeCurrent(pbuffer)
        // Prepare replacement before discarding the source currently feeding clients.
        if (type == "image") {
            val next = ImageFrameSource(file); image?.close(); video?.close(); image = next; video = null
        } else {
            val next = VideoFrameSource(file,handler); image?.close(); video?.close(); video = next; image = null
        }
    }
    fun add(id: Int, surface: Surface, width: Int, height: Int) {
        remove(id)
        val egl = EGL14.eglCreateWindowSurface(display,config,surface,intArrayOf(EGL14.EGL_NONE),0)
        check(egl != EGL14.EGL_NO_SURFACE) { "Camera Surface is not EGL compatible" }
        outputs[id] = Target(surface,egl,width,height)
        requested.add(id)
    }
    fun request(id: Int) { if (outputs.containsKey(id)) requested.add(id) }
    fun requestAll() { requested.addAll(outputs.keys) }
    fun remove(id: Int) {
        makeCurrent(pbuffer)
        outputs.remove(id)?.let { EGL14.eglDestroySurface(display,it.egl); it.surface.release() }
        requested.remove(id)
    }
    fun render(nowNs: Long) {
        makeCurrent(pbuffer)
        video?.let { it.loop = settings.loop; it.advance(nowNs) }
        if (!ready) return
        val program = if (video != null) videoProgram else imageProgram
        val target = if (video != null) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
        val width = video?.width ?: image!!.width; val height = video?.height ?: image!!.height
        val rotation = (settings.rotation + (video?.rotation ?: 0)) % 360
        val texMatrix = video?.transform ?: FloatArray(16).also { Matrix.setIdentityM(it,0); it[5] = -1f; it[13] = 1f }
        for ((id, out) in outputs) {
            if (!requested.remove(id)) continue
            makeCurrent(out.egl)
            GLES20.glViewport(0,0,out.width,out.height); GLES20.glClearColor(0f,0f,0f,1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val swapped = rotation % 180 != 0
            val aspect = (if (swapped) height.toFloat()/width else width.toFloat()/height) / (out.width.toFloat()/out.height)
            var sx = 1f; var sy = 1f
            if ((aspect > 1f) == (settings.scaleMode == "fit")) sy = 1f/aspect else sx = aspect
            sx *= settings.zoom.toFloat(); sy *= settings.zoom.toFloat()
            val translateX = settings.panX.toFloat() * maxOf(0f, sx - 1f)
            val translateY = -settings.panY.toFloat() * maxOf(0f, sy - 1f)
            // Apply pan in output coordinates after the media orientation and
            // fit/fill/zoom scale. Positive pan follows a finger right/down.
            val mvp = FloatArray(16); Matrix.setIdentityM(mvp,0)
            Matrix.translateM(mvp,0,translateX,translateY,0f)
            Matrix.scaleM(mvp,0,sx,sy,1f); Matrix.rotateM(mvp,0,-rotation.toFloat(),0f,0f,1f)
            GLES20.glUseProgram(program); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(target,video?.texture ?: image!!.texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"uTexture"),0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uMvp"),1,false,mvp,0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program,"uTexMatrix"),1,false,texMatrix,0)
            val p = GLES20.glGetAttribLocation(program,"aPosition"); val t = GLES20.glGetAttribLocation(program,"aTexCoord")
            vertices.position(0); GLES20.glVertexAttribPointer(p,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(p)
            vertices.position(2); GLES20.glVertexAttribPointer(t,2,GLES20.GL_FLOAT,false,16,vertices); GLES20.glEnableVertexAttribArray(t)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "OpenGL frame rendering failed" }
            check(EGLExt.eglPresentationTimeANDROID(display,out.egl,nowNs)) { "EGL timestamp failed" }
            check(EGL14.eglSwapBuffers(display,out.egl)) { "Camera buffer queue disconnected" }
            frames++
        }
    }
    private fun makeCurrent(surface: EGLSurface) { check(EGL14.eglMakeCurrent(display,surface,surface,context)) { "EGL context lost" } }
    override fun close() {
        outputs.keys.toList().forEach(::remove); makeCurrent(pbuffer); image?.close(); video?.close()
        GLES20.glDeleteProgram(imageProgram); GLES20.glDeleteProgram(videoProgram)
        EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display,pbuffer); EGL14.eglDestroyContext(display,context); EGL14.eglReleaseThread(); EGL14.eglTerminate(display)
    }
    companion object {
        fun textureParameters(target: Int) {
            GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE); GLES20.glTexParameteri(target,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        }
        private fun shader(type: Int, code: String): Int {
            val id = GLES20.glCreateShader(type); GLES20.glShaderSource(id,code); GLES20.glCompileShader(id)
            val ok = IntArray(1); GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
            check(ok[0] != 0) { GLES20.glGetShaderInfoLog(id) }; return id
        }
        private fun program(external: Boolean): Int {
            val vertex = shader(GLES20.GL_VERTEX_SHADER,"attribute vec2 aPosition; attribute vec2 aTexCoord; uniform mat4 uMvp; uniform mat4 uTexMatrix; varying vec2 vTex; void main(){gl_Position=uMvp*vec4(aPosition,0.,1.);vTex=(uTexMatrix*vec4(aTexCoord,0.,1.)).xy;}")
            val fragment = shader(GLES20.GL_FRAGMENT_SHADER,(if(external) "#extension GL_OES_EGL_image_external : require\n" else "")+"precision mediump float; varying vec2 vTex; uniform ${if(external) "samplerExternalOES" else "sampler2D"} uTexture; void main(){gl_FragColor=texture2D(uTexture,vTex);}")
            val id = GLES20.glCreateProgram(); GLES20.glAttachShader(id,vertex); GLES20.glAttachShader(id,fragment); GLES20.glLinkProgram(id)
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment)
            val ok = IntArray(1); GLES20.glGetProgramiv(id,GLES20.GL_LINK_STATUS,ok,0); check(ok[0] != 0) { GLES20.glGetProgramInfoLog(id) }; return id
        }
    }
}
