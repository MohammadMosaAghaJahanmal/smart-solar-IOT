package com.virtualcamerapro.client

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.virtualcamerapro.engine.CameraOutputManager
import com.virtualcamerapro.engine.OutputSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FrameEngineTest {
    data class Frame(val red: Int,val blue: Int,val timestamp: Long)
    @Test fun liveZoomAndPanChangeActualPixelsWithoutReplacingTheSourceOrSurface() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = File(context.cacheDir, "zoom-pan-quadrants.png")
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888)
        for (y in 0 until 180) for (x in 0 until 320) bitmap.setPixel(x, y,
            if (y < 90) { if (x < 160) Color.RED else Color.GREEN } else { if (x < 160) Color.BLUE else Color.WHITE })
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val output = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val callback = HandlerThread("ZoomPanReadback").apply { start() }
        data class Pixels(val center: Int, val timestamp: Long)
        val frames = LinkedBlockingQueue<Pixels>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        output.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.use { image ->
            val plane = image.planes.single(); val at = 90 * plane.rowStride + 160 * plane.pixelStride
            frames.offer(Pixels(Color.rgb(plane.buffer.get(at).toInt() and 255, plane.buffer.get(at + 1).toInt() and 255, plane.buffer.get(at + 2).toInt() and 255), image.timestamp))
        } }, Handler(callback.looper))
        val engine = CameraOutputManager { failure.set(it) }
        var previousTimestamp = 0L
        fun next(expected: Int, settings: OutputSettings) {
            frames.clear()
            val revision = engine.update(settings)
            val actual = requireNotNull(frames.poll(8, TimeUnit.SECONDS)) { "No actual transformed EGL frame" }
            assertTrue("The requested image quadrant must become the actual camera center",
                kotlin.math.abs(Color.red(actual.center) - Color.red(expected)) < 20 &&
                    kotlin.math.abs(Color.green(actual.center) - Color.green(expected)) < 20 &&
                    kotlin.math.abs(Color.blue(actual.center) - Color.blue(expected)) < 20)
            assertTrue(actual.timestamp > previousTimestamp); previousTimestamp = actual.timestamp
            assertEquals("The transform must not replace the selected source", 1L, engine.sourceRevision)
            assertEquals("The same camera output Surface remains connected", 1, engine.streams)
            val deliveredDeadline = SystemClock.elapsedRealtime() + 1000
            while (engine.deliveredSettingsRevision < revision && SystemClock.elapsedRealtime() < deliveredDeadline) Thread.sleep(1)
            assertTrue(engine.deliveredSettingsRevision >= revision)
            assertNull(failure.get())
        }
        try {
            engine.start(source, "image", OutputSettings()); engine.add(11, output.surface, 320, 180)
            requireNotNull(frames.poll(8, TimeUnit.SECONDS))
            next(Color.RED, OutputSettings(zoom = 2.0, panX = 1.0, panY = 1.0))
            next(Color.GREEN, OutputSettings(zoom = 2.0, panX = -1.0, panY = 1.0))
            next(Color.BLUE, OutputSettings(zoom = 2.0, panX = 1.0, panY = -1.0))
            next(Color.WHITE, OutputSettings(zoom = 2.0, panX = -1.0, panY = -1.0))
        } finally { engine.close(); output.close(); callback.quitSafely(); source.delete() }
    }
    @Test fun liveVideoPanKeepsTheDecoderAndOutputConnected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val source = File(instrumentation.targetContext.cacheDir, "zoom-pan-quadrants.mp4")
        instrumentation.context.assets.open("quadrants-2s.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val output = ImageReader.newInstance(320, 180, PixelFormat.RGBA_8888, 3)
        val callback = HandlerThread("VideoZoomPanReadback").apply { start() }
        val frames = LinkedBlockingQueue<Pair<Int, Long>>()
        output.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.use { image ->
            val plane = image.planes.single(); val at = 90 * plane.rowStride + 160 * plane.pixelStride
            frames.offer(Color.rgb(plane.buffer.get(at).toInt() and 255, plane.buffer.get(at + 1).toInt() and 255, plane.buffer.get(at + 2).toInt() and 255) to image.timestamp)
        } }, Handler(callback.looper))
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val engine = CameraOutputManager { failure.set(it) }
        var previous = 0L
        try {
            engine.start(source, "video", OutputSettings()); engine.add(12, output.surface, 320, 180)
            requireNotNull(frames.poll(8, TimeUnit.SECONDS))
            for ((expected, value) in listOf(Color.RED to OutputSettings(zoom = 2.0, panX = 1.0, panY = 1.0),
                Color.BLUE to OutputSettings(zoom = 2.0, panX = 1.0, panY = -1.0),
                Color.GREEN to OutputSettings(zoom = 2.0, panX = -1.0, panY = 1.0))) {
                frames.clear(); engine.update(value)
                val frame = requireNotNull(frames.poll(8, TimeUnit.SECONDS)) { "No actual transformed video frame" }
                assertTrue(kotlin.math.abs(Color.red(frame.first) - Color.red(expected)) < 25 &&
                    kotlin.math.abs(Color.green(frame.first) - Color.green(expected)) < 25 &&
                    kotlin.math.abs(Color.blue(frame.first) - Color.blue(expected)) < 25)
                assertTrue(frame.second > previous); previous = frame.second
                assertEquals(1L, engine.sourceRevision); assertEquals(1, engine.streams)
                assertNull(failure.get())
                Thread.sleep(80)
            }
        } finally { engine.close(); output.close(); callback.quitSafely(); source.delete() }
    }
    @Test fun portraitMediaRespectsFitFillAndClockwiseRotation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=File(context.cacheDir,"portrait-pattern.png")
        val bitmap=Bitmap.createBitmap(90,160,Bitmap.Config.ARGB_8888)
        for(y in 0 until 160) for(x in 0 until 90) bitmap.setPixel(x,y,if(y<80) Color.RED else Color.BLUE)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        val output=ImageReader.newInstance(320,180,PixelFormat.RGBA_8888,3)
        val callback=HandlerThread("TransformReadback").apply { start() }
        val pixels=LinkedBlockingQueue<List<Int>>()
        output.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { image ->
            val plane=image.planes[0]
            fun pixel(x: Int,y: Int): Int {
                val index=y*plane.rowStride+x*plane.pixelStride
                return Color.rgb(plane.buffer.get(index).toInt() and 255,plane.buffer.get(index+1).toInt() and 255,plane.buffer.get(index+2).toInt() and 255)
            }
            pixels.offer(listOf(pixel(10,45),pixel(160,45),pixel(160,135),pixel(40,90),pixel(280,90)))
        } },Handler(callback.looper))
        val engine=CameraOutputManager { throw AssertionError(it) }
        fun next(): List<Int> = requireNotNull(pixels.poll(8,TimeUnit.SECONDS)) { "No transformed EGL frame" }
        try {
            engine.start(source,"image",OutputSettings(scaleMode="fit"));engine.add(5,output.surface,320,180)
            val fit=next();assertEquals("Fit must letterbox portrait media",Color.BLACK,fit[0])
            assertTrue("Image top stays red",Color.red(fit[1])>230 && Color.blue(fit[1])<20)
            assertTrue("Image bottom stays blue",Color.blue(fit[2])>230 && Color.red(fit[2])<20)
            engine.update(OutputSettings(scaleMode="fill"));engine.request(5)
            val fill=next();assertTrue("Fill removes side bars",Color.red(fill[0])>230)
            engine.update(OutputSettings(rotation=90,scaleMode="fit"));engine.request(5)
            val rotated=next()
            assertTrue("Clockwise rotation moves blue to the left",Color.blue(rotated[3])>230 && Color.red(rotated[3])<20)
            assertTrue("Clockwise rotation moves red to the right",Color.red(rotated[4])>230 && Color.blue(rotated[4])<20)
        } finally { engine.close();output.close();callback.quitSafely();source.delete() }
    }
    @Test fun imageFramesSwitchSourcesAndKeepMonotonicTimestamps() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val red = File(context.cacheDir,"engine-red.png"); val blue = File(context.cacheDir,"engine-blue.png")
        fun fixture(file: File,color: Int) { val b=Bitmap.createBitmap(160,90,Bitmap.Config.ARGB_8888);b.eraseColor(color);file.outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) };b.recycle() }
        fixture(red,Color.RED);fixture(blue,Color.BLUE)
        val output=ImageReader.newInstance(320,180,PixelFormat.RGBA_8888,3)
        val callback=HandlerThread("EngineTestReadback").apply { start() }
        val frames=LinkedBlockingQueue<Frame>()
        output.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { image ->
            val plane=image.planes[0];val index=(image.height/2)*plane.rowStride+(image.width/2)*plane.pixelStride
            frames.offer(Frame(plane.buffer.get(index).toInt() and 255,plane.buffer.get(index+2).toInt() and 255,image.timestamp))
        } },Handler(callback.looper))
        var failure: Throwable? = null
        val engine=CameraOutputManager { failure=it }
        try {
            engine.start(red,"image",OutputSettings());engine.awaitReady();engine.add(7,output.surface,320,180)
            val first=frames.poll(8,TimeUnit.SECONDS);assertNotNull("EGL must submit image pixels",first)
            assertTrue("Expected red pixels, engine error: $failure",first!!.red > 230 && first.blue < 20)
            engine.start(blue,"image",OutputSettings());engine.request(7)
            val second=frames.poll(8,TimeUnit.SECONDS);assertNotNull("Source switch must produce a frame",second)
            assertTrue("Expected blue pixels",second!!.blue > 230 && second.red < 20)
            assertTrue("Presentation timestamps must increase",second.timestamp > first.timestamp)
            assertNull(failure)
        } finally { engine.close();output.close();callback.quitSafely();red.delete();blue.delete() }
    }
    @Test fun mediaCodecVideoLoopsIntoRealEglBuffers() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=File(instrumentation.targetContext.cacheDir,"loop-fixture.mp4")
        instrumentation.context.assets.open("red-blue-2s.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        val output=ImageReader.newInstance(320,180,PixelFormat.RGBA_8888,3)
        val callback=HandlerThread("VideoTestReadback").apply { start() }
        val frames=LinkedBlockingQueue<Frame>()
        output.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.use { image ->
            val plane=image.planes[0];val index=(image.height/2)*plane.rowStride+(image.width/2)*plane.pixelStride
            frames.offer(Frame(plane.buffer.get(index).toInt() and 255,plane.buffer.get(index+2).toInt() and 255,image.timestamp))
        } },Handler(callback.looper))
        var failure: Throwable? = null
        val engine=CameraOutputManager { failure=it }
        try {
            engine.start(file,"video",OutputSettings(loop=true));engine.awaitReady();engine.add(1,output.surface,320,180)
            val colors=mutableListOf<Boolean>();var lastTimestamp=0L
            val end=SystemClock.elapsedRealtime()+6500
            while(SystemClock.elapsedRealtime()<end) {
                engine.request(1)
                val frame=frames.poll(250,TimeUnit.MILLISECONDS) ?: continue
                assertTrue("Decoded frame must be red or blue",frame.red>180 || frame.blue>180)
                assertTrue("Video timestamps must increase",frame.timestamp>lastTimestamp);lastTimestamp=frame.timestamp
                colors.add(frame.red>frame.blue)
                Thread.sleep(15)
            }
            assertNull(failure)
            assertTrue("Need sustained native frame output",colors.size>=60)
            assertTrue("Decoded video must loop for multiple periods",colors.zipWithNext().count { it.first != it.second }>=4)
        } finally { engine.close();output.close();callback.quitSafely();file.delete() }
    }
}
