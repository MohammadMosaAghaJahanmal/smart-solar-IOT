package com.virtualcamerapro.camera

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraFrameProbe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Explicit opt-in: a real consumer holds the camera until a production fallback attempt fails. */
@RunWith(AndroidJUnit4::class)
class CompatibilityFallbackDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun realCameraContentionFallsBackAndCachedWinnerIsFreshlyVerified() {
        assumeTrue("Enable actual camera contention with compatibilityFallback=true",
            InstrumentationRegistry.getArguments().getString("compatibilityFallback") == "true")
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue("Exercise the ordinary installed application's UID", Process.myUid() >= 10000)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        instrumentation.waitForIdleSync()
        val report = JSONObject().put("passed", false).put("controllerUid", Process.myUid())
        val reportFile = File(context.getExternalFilesDir(null), "compatibility-fallback-device.json")
        fun save(stage: String) { report.put("stage", stage); reportFile.writeText(report.toString(2)) }
        val cache = File(context.filesDir, "guest-working-outputs.json")
        val preservedCache = File(context.filesDir, ".compatibility-test-${UUID.randomUUID()}.json")
        val activationWorker = Executors.newSingleThreadExecutor()
        var fixture: File? = null
        var held: HeldCamera? = null
        var cacheWasPreserved = false
        VirtualCameraController(context).use { controller ->
            BoundBroker(context).use { bound ->
                val originalSettings = controller.state.settings
                try {
                    controller.deactivate()
                    val support = controller.support()
                    assertTrue("The real integrated bridge must already be prepared: $support", support.optBoolean("supported"))
                    assertEquals("vmos_camera_proxy", support.getString("backend"))
                    val inventory = support.getJSONArray("cameras")
                    val cameras = (0 until inventory.length()).map { inventory.getJSONObject(it) }
                    val selected = cameras.firstOrNull { it.getString("facing") == "back" }
                        ?: cameras.first { it.getString("facing") == "front" }
                    val choices = selected.getJSONArray("resolutions")
                    val sizes = (0 until choices.length()).map { choices.getJSONObject(it) }
                    val size = sizes.firstOrNull { it.getInt("width") == 640 && it.getInt("height") == 480 }
                        ?: sizes.filter { it.getInt("width").toLong() * it.getInt("height") <= 1280L * 720 }
                            .maxByOrNull { it.getInt("width").toLong() * it.getInt("height") }
                        ?: sizes.minBy { it.getInt("width").toLong() * it.getInt("height") }
                    val requested = OutputSettings(cameraId = selected.getString("id"), width = size.getInt("width"),
                        height = size.getInt("height"), replacementMode = "single", loop = false, rotation = 180,
                        scaleMode = "fill", zoom = 1.25, panX = 0.2, panY = -0.1)
                    report.put("requested", settingsJson(requested))
                    // Preserve prior learned settings. An alternate cached lens would bypass this
                    // real held-camera condition and could falsely appear to exercise a retry.
                    if (cache.exists()) {
                        assertTrue("Preserve existing successful configurations", cache.renameTo(preservedCache))
                        cacheWasPreserved = true
                    }
                    assertFalse(cache.exists())
                    controller.update(requested)
                    fixture = quadrants(requested.width, requested.height)
                    val sourceHash = hash(requireNotNull(fixture))
                    controller.select(Uri.fromFile(fixture).toString(), "image")
                    held = HeldCamera(context, requested.cameraId, requested.width, requested.height)
                    requireNotNull(held).awaitFrames()
                    report.put("heldCameraFramesBeforeActivation", requireNotNull(held).frames.get())
                    save("real-camera-held")
                    val started = SystemClock.elapsedRealtime()
                    val activated = activationWorker.submit<Boolean> { controller.activate() }
                    var failedAttempt: JSONObject? = null
                    var blockedSnapshot = JSONObject()
                    val failureDeadline = SystemClock.elapsedRealtime() + 45_000
                    while (SystemClock.elapsedRealtime() < failureDeadline) {
                        val status = JSONObject(bound.service.status)
                        val attempts = status.optJSONObject("fallback")?.optJSONArray("attempts")
                        if (attempts != null && attempts.length() > 0 && attempts.getJSONObject(0).optString("status") == "failed") {
                            failedAttempt = JSONObject(attempts.getJSONObject(0).toString())
                            blockedSnapshot = status
                            break
                        }
                        assertNotEquals("A held camera must not produce false Active", "active", status.optString("phase"))
                        requireNotNull(held).assertNotPreempted()
                        if (activated.isDone) {
                            // Propagate a real operation failure instead of manufacturing a retry.
                            activated.get(1, TimeUnit.SECONDS)
                            fail("Activation finished without observing an actual failed fallback attempt")
                        }
                        Thread.sleep(15)
                    }
                    assertNotNull("A real open-camera failure must be recorded before releasing the consumer", failedAttempt)
                    assertNotEquals("active", blockedSnapshot.optString("phase"))
                    assertFalse(blockedSnapshot.optJSONObject("fallback")?.optBoolean("verified") ?: false)
                    assertFalse("No old proof can survive a failed current attempt", blockedSnapshot.has("proof"))
                    requireNotNull(held).assertNotPreempted()
                    report.put("failedAttemptWhileHeld", failedAttempt).put("blockedSnapshot", blockedSnapshot)
                    save("failed-attempt-observed-before-release")
                    requireNotNull(held).close(); held = null
                    assertTrue("Production retry must succeed after the real camera is released", activated.get(90, TimeUnit.SECONDS))
                    val totalMs = SystemClock.elapsedRealtime() - started
                    assertTrue("The real operation must respect its bounded activation budget", totalMs < 90_000)
                    val first = verifyActive(bound.service, sourceHash)
                    val fallback = first.getJSONObject("fallback")
                    val attempts = fallback.getJSONArray("attempts")
                    assertTrue("Recovery requires a later real attempt", attempts.length() >= 2)
                    assertTrue(attempts.length() <= 8)
                    assertEquals("failed", attempts.getJSONObject(0).getString("status"))
                    assertEquals("verified", attempts.getJSONObject(attempts.length() - 1).getString("status"))
                    assertTrue(fallback.getBoolean("used"))
                    assertTrue(fallback.getBoolean("saved"))
                    assertTrue("Successful output must be persisted in the real private store", cache.isFile && cache.length() > 0)
                    report.put("firstActivation", first).put("activationElapsedMs", totalMs)
                        .put("persistedCache", JSONObject(cache.readText()))
                    assertTransforms(requested, controller.state.settings)
                    val actual = first.getJSONObject("actualSettings")
                    val image = CameraFrameProbe(context).capture(actual.getString("cameraId"), 150,
                        actual.getInt("width"), actual.getInt("height"), fallback.getString("streamStrategy"))
                    assertTrue("An independent real consumer must receive increasing YUV frames", image.previewFrames >= 3 && image.lastTimestamp > image.firstTimestamp)
                    assertEquals(actual.getInt("width"), image.previewWidth)
                    assertEquals(actual.getInt("height"), image.previewHeight)
                    verifyColors(image.jpeg)
                    report.put("independentConsumer", image.metrics())
                    val generation = first.getJSONObject("proof").getString("generation")
                    save("fallback-verified-with-independent-consumer")

                    controller.deactivate()
                    // Restore exactly the original user request; the cache must select the winning
                    // actual route/strategy itself, and must still produce completely new proof.
                    controller.update(requested)
                    assertTrue(controller.activate())
                    val second = verifyActive(bound.service, sourceHash)
                    val cachedFallback = second.getJSONObject("fallback")
                    val reused = cachedFallback.getJSONArray("attempts")
                    assertEquals("The working cache should avoid repeating known failed strategies", 1, reused.length())
                    assertEquals("verified-cache", reused.getJSONObject(0).getString("reason"))
                    assertEquals("verified", reused.getJSONObject(0).getString("status"))
                    assertEquals(fallback.getString("streamStrategy"), reused.getJSONObject(0).getString("streamStrategy"))
                    assertEquals(actual.getString("cameraId"), second.getJSONObject("actualSettings").getString("cameraId"))
                    assertEquals(actual.getInt("width"), second.getJSONObject("actualSettings").getInt("width"))
                    assertEquals(actual.getInt("height"), second.getJSONObject("actualSettings").getInt("height"))
                    assertNotEquals("Cached settings never authorize reusing old delivery proof", generation, second.getJSONObject("proof").getString("generation"))
                    assertTransforms(requested, controller.state.settings)
                    report.put("cachedActivation", second).put("passed", true)
                    save("complete")
                } catch (error: Throwable) {
                    report.put("failure", "${error.javaClass.simpleName}: ${error.message}")
                    save("failed")
                    throw error
                } finally {
                    runCatching { held?.close() }
                    runCatching { bound.service.deactivate() }
                    activationWorker.shutdownNow()
                    activationWorker.awaitTermination(8, TimeUnit.SECONDS)
                    runCatching { controller.update(originalSettings) }
                    fixture?.delete()
                    if (cacheWasPreserved) {
                        if (cache.exists()) report.put("testCacheRemoved", cache.delete())
                        report.put("originalCacheRestored", preservedCache.renameTo(cache))
                        if (preservedCache.exists()) report.put("preservedCacheBackup", preservedCache.absolutePath)
                    }
                    reportFile.writeText(report.toString(2))
                }
            }
        }
    }

    private fun settingsJson(value: OutputSettings) = JSONObject().put("cameraId", value.cameraId)
        .put("width", value.width).put("height", value.height).put("replacementMode", value.replacementMode)
        .put("loop", value.loop).put("rotation", value.rotation).put("scaleMode", value.scaleMode)
        .put("zoom", value.zoom).put("panX", value.panX).put("panY", value.panY)

    private fun assertTransforms(requested: OutputSettings, actual: OutputSettings) {
        assertEquals(requested.copy(cameraId = actual.cameraId, width = actual.width, height = actual.height), actual)
    }

    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun quadrants(width: Int, height: Int): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for ((index, color) in listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE).withIndex()) {
            paint.color = color
            val left = if (index % 2 == 0) 0f else width / 2f
            val top = if (index < 2) 0f else height / 2f
            canvas.drawRect(left, top, left + width / 2f, top + height / 2f, paint)
        }
        return File(context.cacheDir, "fallback-quadrants-${UUID.randomUUID()}.png").also { file ->
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }

    private fun verifyColors(jpeg: ByteArray) {
        val image = requireNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        try {
            val expected = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
            val actual = listOf(1 to 1, 3 to 1, 1 to 3, 3 to 3).map { (x, y) -> image.getPixel(image.width * x / 4, image.height * y / 4) }
            val matched = actual.map { pixel -> expected.indexOfFirst { color ->
                listOf(16, 8, 0).all { shift -> abs(((pixel ushr shift) and 255) - ((color ushr shift) and 255)) < 40 }
            } }
            assertTrue("Actual camera JPEG must preserve the source quadrant colors: $matched", matched.all { it >= 0 })
            assertEquals("Actual source position, crop and colors must retain all four quadrants", 4, matched.toSet().size)
        } finally { image.recycle() }
    }

    private fun verifyActive(broker: IVirtualCameraBroker, sourceHash: String): JSONObject {
        val status = JSONObject(broker.status)
        assertEquals("active", status.getString("phase"))
        val fallback = status.getJSONObject("fallback")
        assertTrue(fallback.getBoolean("verified"))
        val proof = status.getJSONObject("proof")
        val actual = status.getJSONObject("actualSettings")
        assertEquals("single", actual.getString("replacementMode"))
        assertEquals("single", proof.getString("replacementMode"))
        assertTrue(proof.getBoolean("sourceVerified") && proof.getBoolean("rendererConfigured"))
        assertEquals(sourceHash, proof.getString("sourceSha256"))
        assertTrue(proof.getInt("previewFrames") >= 3 && proof.getInt("jpegBytes") > 0)
        assertTrue(proof.getLong("lastTimestampNs") > proof.getLong("firstTimestampNs"))
        assertTrue(proof.getLong("producerFrames") > 0 && proof.getLong("proxyFrames") >= 3)
        assertTrue(proof.getDouble("bestMeanAbsoluteError") in 0.0..25.0)
        assertEquals(actual.getString("cameraId"), proof.getString("cameraId"))
        assertEquals(actual.getInt("width"), proof.getInt("previewWidth"))
        assertEquals(actual.getInt("height"), proof.getInt("previewHeight"))
        assertEquals(actual.getInt("width"), proof.getInt("jpegWidth"))
        assertEquals(actual.getInt("height"), proof.getInt("jpegHeight"))
        val native = JSONObject(File(context.filesDir, "camera-runtime/status.json").readText())
        assertEquals(proof.getString("generation"), native.getString("sourceId"))
        assertEquals(sourceHash, native.getString("sourceSha256"))
        assertTrue(native.getLong("sourceFrames") >= 3)
        assertTrue(native.getLong("lastFrameBootNs") > SystemClock.elapsedRealtimeNanos() - 3_000_000_000L)
        assertTrue(native.getLong("expiresBootNs") > SystemClock.elapsedRealtimeNanos())
        return status.put("nativeDelivery", native)
    }

    private class BoundBroker(private val context: Context) : AutoCloseable {
        private val endpoint = AtomicReference<IVirtualCameraBroker?>()
        private val connected = CountDownLatch(1)
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                endpoint.set(IVirtualCameraBroker.Stub.asInterface(binder)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { endpoint.set(null) }
        }
        val service: IVirtualCameraBroker
        init {
            assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName,
                "com.virtualcamerapro.broker.BrokerService")), connection, Context.BIND_AUTO_CREATE))
            assertTrue(connected.await(8, TimeUnit.SECONDS)); service = requireNotNull(endpoint.get())
        }
        override fun close() { context.unbindService(connection) }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private class HeldCamera(context: Context, id: String, width: Int, height: Int) : AutoCloseable {
        val frames = AtomicInteger()
        private val closing = AtomicBoolean()
        private val ready = CountDownLatch(1)
        private val failure = AtomicReference<Throwable?>()
        private val thread = HandlerThread("RealFallbackCameraBlocker").apply { start() }
        private val handler = Handler(thread.looper)
        private val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 3)
        private var device: CameraDevice? = null
        private var session: CameraCaptureSession? = null
        private fun failed(error: Throwable) { if (!closing.get()) { failure.compareAndSet(null, error); ready.countDown() } }
        init {
            reader.setOnImageAvailableListener({ available ->
                try { available.acquireLatestImage()?.use { image ->
                    check(image.planes.size == 3 && image.planes[0].buffer.remaining() > 0)
                    if (frames.incrementAndGet() >= 3) ready.countDown()
                } } catch (error: Throwable) { failed(error) }
            }, handler)
            context.getSystemService(CameraManager::class.java).openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closing.get()) { camera.close(); return }
                    device = camera
                    try { camera.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(configured: CameraCaptureSession) {
                            if (closing.get()) { configured.close(); return }
                            session = configured
                            try { configured.setRepeatingRequest(camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                                .apply { addTarget(reader.surface) }.build(), null, handler) }
                            catch (error: Throwable) { failed(error) }
                        }
                        override fun onConfigureFailed(configured: CameraCaptureSession) {
                            configured.close(); failed(IllegalStateException("The real contention consumer could not configure YUV"))
                        }
                    }, handler) } catch (error: Throwable) { failed(error) }
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close(); failed(IllegalStateException("CameraService preempted the held consumer; this device did not exercise camera-in-use fallback"))
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); failed(IllegalStateException("Real contention consumer camera error $error"))
                }
            }, handler)
        }
        fun awaitFrames() { assertTrue("Held real camera must receive actual YUV buffers", ready.await(12, TimeUnit.SECONDS)); assertNotPreempted(); assertTrue(frames.get() >= 3) }
        fun assertNotPreempted() { failure.get()?.let { throw it } }
        override fun close() {
            if (!closing.compareAndSet(false, true)) return
            val closed = CountDownLatch(1)
            handler.post { try {
                runCatching { session?.stopRepeating() }; session?.close(); device?.close(); reader.close()
            } finally { closed.countDown() } }
            assertTrue("Release the real held camera before retry", closed.await(4, TimeUnit.SECONDS))
            thread.quitSafely(); thread.join(2000)
        }
    }
}
