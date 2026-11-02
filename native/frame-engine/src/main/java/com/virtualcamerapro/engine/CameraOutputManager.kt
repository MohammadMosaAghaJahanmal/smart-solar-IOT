package com.virtualcamerapro.engine

import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.util.Log
import java.io.File
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Owns the EGL and codec lifecycle independently of React Native. */
class CameraOutputManager(private val onError: (Throwable) -> Unit) : AutoCloseable {
    private val thread = HandlerThread("VirtualCameraFrames").apply { start() }
    private val handler = Handler(thread.looper)
    private var renderer: FrameRenderer? = null
    @Volatile private var running = false
    private var nextFrame = 0L
    @Volatile var frames = 0L; private set
    @Volatile var streams = 0; private set
    @Volatile var lastTickMs = 0L; private set
    @Volatile var sourceRevision = 0L; private set
    @Volatile var deliveredRevision = 0L; private set
    @Volatile var settingsRevision = 0L; private set
    @Volatile var deliveredSettingsRevision = 0L; private set
    @Volatile var failure: Throwable? = null; private set
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                val previousFrames = frames
                renderer?.render(System.nanoTime()); frames = renderer?.frames ?: 0; streams = renderer?.streamCount ?: 0
                if (frames > previousFrames) { deliveredRevision = sourceRevision; deliveredSettingsRevision = settingsRevision }
                if (frames / 150 > previousFrames / 150) Log.i("VirtualCameraEngine", "event=frames_delivered total=$frames streams=$streams revision=$deliveredRevision")
                lastTickMs = SystemClock.elapsedRealtime()
                nextFrame = maxOf(nextFrame + 33, SystemClock.uptimeMillis())
                handler.postAtTime(this,nextFrame)
            } catch (e: Throwable) { failure = e; running = false; onError(e) }
        }
    }
    private fun <T> sync(action: () -> T): T {
        val task = FutureTask(action); check(handler.post(task)) { "Frame thread stopped" }; return task.get(15,TimeUnit.SECONDS)
    }
    fun start(file: File, type: String, settings: OutputSettings) = sync {
        if (renderer == null) renderer = FrameRenderer(handler)
        renderer!!.settings = settings; renderer!!.source(file,type)
        sourceRevision++; failure = null
        Log.i("VirtualCameraEngine", "event=source_prepared type=$type revision=$sourceRevision")
        if (!running) { running = true; nextFrame = SystemClock.uptimeMillis(); handler.post(tick) }
    }
    fun update(settings: OutputSettings): Long = sync {
        renderer?.settings = settings; settingsRevision++
        renderer?.requestAll()
        settingsRevision
    }
    fun request(id: Int) { handler.post { renderer?.request(id) } }
    fun awaitReady() {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!sync { renderer?.ready == true }) {
            check(running && SystemClock.elapsedRealtime() < deadline) { "Source did not produce a decoded frame" }
            Thread.sleep(40)
        }
        Log.i("VirtualCameraEngine", "event=decode_ready revision=$sourceRevision")
    }
    fun awaitDelivery(revision: Long = sourceRevision): Boolean {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (deliveredRevision < revision) {
            if (streams == 0) return false
            check(failure == null && running && SystemClock.elapsedRealtime() < deadline) { failure?.message ?: "No camera buffer delivered for source revision $revision" }
            Thread.sleep(40)
        }
        return true
    }
    fun add(id: Int, surface: Surface, width: Int, height: Int) = sync {
        requireNotNull(renderer).add(id,surface,width,height); streams = renderer!!.streamCount
        Log.i("VirtualCameraEngine", "event=stream_open id=$id width=$width height=$height")
    }
    fun remove(id: Int) = sync {
        renderer?.remove(id); streams = renderer?.streamCount ?: 0
        Log.i("VirtualCameraEngine", "event=stream_close id=$id")
    }
    override fun close() {
        try { sync { running = false; handler.removeCallbacks(tick); renderer?.close(); renderer = null } }
        finally { thread.quitSafely() }
    }
}
