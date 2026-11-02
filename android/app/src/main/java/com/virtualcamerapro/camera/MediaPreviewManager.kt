package com.virtualcamerapro.camera

import android.graphics.SurfaceTexture
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.virtualcamerapro.engine.CameraOutputManager
import com.virtualcamerapro.engine.OutputSettings
import java.io.File
import java.util.concurrent.Executors
import android.os.Handler
import android.os.Looper
import com.facebook.react.bridge.LifecycleEventListener

class PreviewView(private val reactContext: ThemedReactContext) : TextureView(reactContext), TextureView.SurfaceTextureListener, LifecycleEventListener {
    var uri = ""
    var type = "image"
    var settings = OutputSettings()
    @Volatile private var engine: CameraOutputManager? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var ticker: Runnable? = null
    private var paused = false
    private var released = false
    private var preparedSource = ""
    private var attachedTexture: SurfaceTexture? = null
    private var attachedWidth = 0
    private var attachedHeight = 0
    init { surfaceTextureListener = this; reactContext.addLifecycleEventListener(this) }
    fun refresh() {
        if (paused || released) return
        val texture = surfaceTexture ?: return
        if (uri.isEmpty()) return
        val path = Uri.parse(uri).path ?: return
        val mediaType = type; val w = width; val h = height; val output = settings
        if (w <= 0 || h <= 0) return
        val sourceKey = "$mediaType:$path"
        worker.execute {
            try {
                if (paused || released) return@execute
                if (engine == null) engine = CameraOutputManager { android.util.Log.e("MediaPreview","Preview error",it) }
                if (preparedSource != sourceKey) {
                    engine!!.start(File(path),mediaType,output)
                    preparedSource = sourceKey
                } else engine!!.update(output)
                if (attachedTexture !== texture || attachedWidth != w || attachedHeight != h) {
                    engine!!.add(0,Surface(texture),w,h)
                    attachedTexture = texture; attachedWidth = w; attachedHeight = h
                }
            } catch (e: Exception) { android.util.Log.e("MediaPreview","Cannot preview selected media",e) }
        }
        if (ticker == null) {
            ticker = object : Runnable { override fun run() { engine?.request(0); main.postDelayed(this,33) } }
            main.post(ticker!!)
        }
    }
    fun dispose() { ticker?.let { main.removeCallbacks(it) }; ticker = null; if(!worker.isShutdown) worker.execute { engine?.close(); engine = null; preparedSource = ""; attachedTexture = null; attachedWidth = 0; attachedHeight = 0 } }
    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) = refresh()
    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = refresh()
    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean { dispose(); if(!worker.isShutdown) worker.execute { surface.release() } else surface.release(); return false }
    override fun onHostResume() { paused = false; refresh() }
    override fun onHostPause() { paused = true; dispose() }
    override fun onHostDestroy() { if (!released) release() }
    fun release() { if(released) return; released = true; reactContext.removeLifecycleEventListener(this); dispose(); worker.shutdown() }
}
class MediaPreviewManager : SimpleViewManager<PreviewView>() {
    override fun getName() = "MediaPreview"
    override fun createViewInstance(context: ThemedReactContext) = PreviewView(context)
    @ReactProp(name="uri") fun uri(view: PreviewView, value: String?) { view.uri = value ?: "" }
    @ReactProp(name="mediaType") fun type(view: PreviewView, value: String?) { view.type = value ?: "image" }
    @ReactProp(name="rotation", defaultInt=0) fun rotation(view: PreviewView, value: Int) { view.settings = view.settings.copy(rotation=value) }
    @ReactProp(name="scaleMode") fun scaleMode(view: PreviewView, value: String?) { view.settings = view.settings.copy(scaleMode=value ?: "fit") }
    @ReactProp(name="loop", defaultBoolean=true) fun loop(view: PreviewView, value: Boolean) { view.settings = view.settings.copy(loop=value) }
    @ReactProp(name="zoom", defaultDouble=1.0) fun zoom(view: PreviewView, value: Double) { view.settings = view.settings.copy(zoom=value) }
    @ReactProp(name="panX", defaultDouble=0.0) fun panX(view: PreviewView, value: Double) { view.settings = view.settings.copy(panX=value) }
    @ReactProp(name="panY", defaultDouble=0.0) fun panY(view: PreviewView, value: Double) { view.settings = view.settings.copy(panY=value) }
    override fun onAfterUpdateTransaction(view: PreviewView) { super.onAfterUpdateTransaction(view); view.refresh() }
    override fun onDropViewInstance(view: PreviewView) { view.release(); super.onDropViewInstance(view) }
}
