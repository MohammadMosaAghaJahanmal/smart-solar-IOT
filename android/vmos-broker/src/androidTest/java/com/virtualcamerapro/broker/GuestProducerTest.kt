package com.virtualcamerapro.broker

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/** Executes the real EGL and MediaCodec pipeline without changing the guest HAL. */
@RunWith(AndroidJUnit4::class)
class GuestProducerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun properties(directory: File): Map<String, String> = File(directory, "source.cfg").readLines().also {
        check(it.first() == "VCAM2")
    }.drop(1).associate { val at = it.indexOf('='); it.substring(0, at) to it.substring(at + 1) }

    private fun center(properties: Map<String, String>): Triple<Int, Int, Int>? = runCatching {
        DataInputStream(File(properties.getValue("path")).inputStream()).use { input ->
            val header = ByteArray(8); input.readFully(header)
            check(String(header, Charsets.US_ASCII) == "VCAMFR2\n")
            check(input.readLong() == properties.getValue("sequence").toLong())
            val width = properties.getValue("width").toInt(); val height = properties.getValue("height").toInt()
            val offset = ((height / 2L) * width + width / 2) * 4
            var skipped = 0L
            while (skipped < offset) { val n = input.skip(offset - skipped); check(n > 0); skipped += n }
            Triple(input.readUnsignedByte(), input.readUnsignedByte(), input.readUnsignedByte())
        }
    }.getOrNull()

    @Test fun imageAndBoundedLiveVideoUseRealDecodedPixels() {
        GuestRuntime(context).requireGuest()
        val directory = File(context.cacheDir, "producer-check-${System.nanoTime()}").apply { check(mkdir()) }
        val failure = AtomicReference<Throwable?>()
        val evidence = JSONObject()
        try {
            val image = File(directory, "red.png")
            Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(Color.RED)
                image.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            }
            GuestFrameProducer(directory, GuestRuntime.sha256(image), "camera_0", OutputSettings(), { failure.set(it) }).use { producer ->
                producer.start(image, "image")
                assertFalse("A prepared source must not publish itself before the broker commits", File(directory, "source.cfg").exists())
                producer.publish()
                val config = properties(directory)
                assertEquals(producer.generation, config["id"])
                assertEquals("0", config["sequence"])
                assertTrue(config.getValue("expires_ns").toLong() > SystemClock.elapsedRealtimeNanos())
                val color = center(config) ?: error("No actual published image pixels")
                assertTrue("Expected actual red RGBA pixels: $color", color.first > 220 && color.second < 25 && color.third < 25)
                evidence.put("imageFrames", producer.frames).put("imageRed", true)
                producer.suspendPublication()
                File(directory, "source.cfg").delete()
            }
            val video = File(directory, "red-blue.mp4")
            instrumentation.context.assets.open("red-blue-2s.mp4").use { input -> video.outputStream().use { input.copyTo(it) } }
            for (loop in listOf(true, false)) {
                val settings = OutputSettings(loop = loop)
                GuestFrameProducer(directory, GuestRuntime.sha256(video), "camera_0", settings, { failure.set(it) }).use { producer ->
                    producer.start(video, "video"); producer.publish()
                    val deadline = SystemClock.elapsedRealtime() + 5600
                    var transitions = 0; var previous = ""; var red = 0; var blue = 0; var finalRed = 0; var finalBlue = 0
                    var maxFiles = 0; var maxSequence = 0L
                    while (SystemClock.elapsedRealtime() < deadline) {
                        failure.get()?.let { throw AssertionError("Native producer failed", it) }
                        producer.renew()
                        val config = properties(directory)
                        assertEquals(producer.generation, config["id"])
                        maxSequence = maxOf(maxSequence, config.getValue("sequence").toLong())
                        center(config)?.let { pixel ->
                            val color = if (pixel.first > 180 && pixel.third < 60) "red" else if (pixel.third > 180 && pixel.first < 60) "blue" else ""
                            if (color.isNotEmpty()) {
                                if (previous.isNotEmpty() && color != previous) transitions++
                                previous = color
                                if (color == "red") red++ else blue++
                                if (SystemClock.elapsedRealtime() > deadline - 1000) { if (color == "red") finalRed++ else finalBlue++ }
                            }
                        }
                        maxFiles = maxOf(maxFiles, directory.listFiles()?.count { it.name.startsWith(producer.generation) && it.name.endsWith(".raw") } ?: 0)
                        Thread.sleep(50)
                    }
                    assertTrue("The producer must deliver fresh video frames", maxSequence > 15)
                    assertTrue("Both actual video colors must be decoded", red > 0 && blue > 0)
                    assertTrue("Live frame storage must remain bounded", maxFiles <= 2)
                    if (loop) assertTrue("Looping video must keep transitioning: $transitions", transitions >= 4)
                    else { assertEquals("Finite playback must hold its final blue frame", 0, finalRed); assertTrue(finalBlue > 3) }
                    evidence.put(if (loop) "loop" else "finite", JSONObject().put("frames", producer.frames).put("sequence", maxSequence).put("transitions", transitions).put("maximumRawFiles", maxFiles).put("tailRed", finalRed).put("tailBlue", finalBlue))
                    producer.suspendPublication(); File(directory, "source.cfg").delete()
                }
            }
            evidence.put("passed", true)
        } finally {
            File(context.filesDir, "guest-producer-proof.json").writeText(evidence.toString(2))
            directory.listFiles()?.forEach { it.delete() }; directory.delete()
        }
    }
}
