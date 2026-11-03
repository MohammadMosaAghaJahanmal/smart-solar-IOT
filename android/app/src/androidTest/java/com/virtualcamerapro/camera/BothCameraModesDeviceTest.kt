package com.virtualcamerapro.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.probe.CameraFrameProof
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Opt-in real broker/Camera2 acceptance; replaces the selected media and stops it afterwards. */
@RunWith(AndroidJUnit4::class)
class BothCameraModesDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val observations = JSONArray()
    private val report = JSONObject().put("passed",false).put("controllerUid",Process.myUid())
        .put("observations",observations)
    private var reportName = "both-image-modes.json"
    private lateinit var ids: List<String>
    private lateinit var back: String
    private lateinit var front: String
    private var width = 0
    private var height = 0

    private fun save() {
        File(context.getExternalFilesDir(null),"both-camera-modes").apply { mkdirs() }
            .resolve(reportName).writeText(report.toString(2))
    }
    private fun stage(value: String) { report.put("stage",value); save() }
    private fun prepare(controller: VirtualCameraController) {
        assumeTrue("Enable real camera replacement explicitly with bothCameraModes=true",
            InstrumentationRegistry.getArguments().getString("bothCameraModes") == "true")
        assertTrue("Run as the installed application's ordinary UID",Process.myUid() >= 10000)
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.CAMERA))
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        instrumentation.waitForIdleSync()
        val support = controller.support()
        assertTrue("Prepare and verify the guest bridge first: $support",support.getBoolean("supported"))
        assertEquals("vmos_camera_proxy",support.getString("backend"))
        val modes = support.getJSONArray("replacementModes")
        assertTrue((0 until modes.length()).any { modes.getString(it) == "both" })
        val cameras = support.getJSONArray("cameras")
        val cameraMetadata = (0 until cameras.length()).map { cameras.getJSONObject(it) }
        fun facing(name: String) = cameraMetadata.filter { it.getString("facing") == name }
            .map { it.getString("id") }
        val backIds = facing("back"); val frontIds = facing("front")
        assertTrue("Both mode requires mapped back cameras",backIds.isNotEmpty())
        assertTrue("Both mode requires mapped front cameras",frontIds.isNotEmpty())
        back = backIds.first(); front = frontIds.first()
        val routes = support.getJSONArray("bothCameraIds")
        ids = (0 until routes.length()).map { routes.getString(it) }
        assertEquals("Both mode must route every mapped front and back camera",(backIds+frontIds).toSet(),ids.toSet())
        assertEquals("Both camera routes must not contain duplicate IDs",ids.size,ids.toSet().size)
        val choices = support.getJSONArray("bothResolutions")
        val sizes = (0 until choices.length()).map { choices.getJSONObject(it) }
        assertTrue(sizes.isNotEmpty())
        // Select an actually advertised common size within a modest test bandwidth budget.
        val size = sizes.filter { it.getInt("width").toLong()*it.getInt("height") <= 1280L*720 }
            .maxByOrNull { it.getInt("width").toLong()*it.getInt("height") }
            ?: sizes.minBy { it.getInt("width").toLong()*it.getInt("height") }
        width = size.getInt("width"); height = size.getInt("height")
        report.put("cameraIds",JSONArray(ids)).put("backCameraId",back).put("frontCameraId",front)
            .put("backCameraIds",JSONArray(backIds)).put("frontCameraIds",JSONArray(frontIds))
            .put("width",width).put("height",height)
        controller.deactivate()
        save()
    }
    private fun settings(id: String = back,mode: String = "both") = OutputSettings(
        width=width,height=height,cameraId=id,scaleMode="fill",loop=true,replacementMode=mode)
    private fun cameraSwitchOrder() = listOf(back,front)+ids.filter { it != back && it != front }+back
    private fun quadrants(): File {
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        for ((index,color) in listOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE).withIndex()) {
            paint.color = color
            val left = if (index%2 == 0) 0f else width/2f
            val top = if (index < 2) 0f else height/2f
            canvas.drawRect(left,top,left+width/2f,top+height/2f,paint)
        }
        return File(context.cacheDir,"both-camera-quadrants.png").also { file ->
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
        }
    }
    private fun video(): File = File(context.cacheDir,"both-camera-loop.mp4").apply {
        // The real encoded two-second red/blue fixture is shared with existing playback acceptance.
        instrumentation.context.assets.open("red-blue-2s.mp4").use { input -> outputStream().use { input.copyTo(it) } }
    }
    private fun compactProof(controller: VirtualCameraController,mode: String,expectedIds: Set<String>): JSONObject {
        val state = controller.status()
        assertEquals("active",state.getString("phase"))
        assertEquals(mode,state.getString("replacementMode"))
        val proof = state.getJSONObject("proof")
        assertTrue(proof.getBoolean("sourceVerified") && proof.getBoolean("rendererConfigured"))
        assertEquals(mode,proof.getString("replacementMode"))
        val actual = proof.getJSONArray("cameraIds")
        assertEquals(expectedIds,(0 until actual.length()).map { actual.getString(it) }.toSet())
        val cameras = proof.getJSONArray("cameras")
        assertEquals(expectedIds.size,cameras.length())
        assertEquals(expectedIds,(0 until cameras.length()).map { cameras.getJSONObject(it).getString("cameraId") }.toSet())
        val summary = JSONArray()
        for (i in 0 until cameras.length()) {
            val item = cameras.getJSONObject(i)
            assertTrue(item.getBoolean("sourceVerified"))
            assertTrue(item.getInt("previewFrames") >= 3 && item.getInt("jpegBytes") > 0)
            assertTrue(item.getLong("lastTimestampNs") > item.getLong("firstTimestampNs"))
            assertTrue(item.getDouble("bestMeanAbsoluteError") <= 25.0)
            assertTrue(item.getLong("proxyFrames") >= 3)
            summary.put(JSONObject().put("cameraId",item.getString("cameraId"))
                .put("cameraName",item.getString("cameraName")).put("previewFrames",item.getInt("previewFrames"))
                .put("proxyFrames",item.getLong("proxyFrames")).put("jpegBytes",item.getInt("jpegBytes"))
                .put("meanAbsoluteError",item.getDouble("bestMeanAbsoluteError")))
        }
        return JSONObject().put("generation",proof.getString("generation")).put("proxyPid",proof.getInt("proxyPid"))
            .put("replacementMode",mode).put("cameras",summary)
    }
    private fun capture(id: String,label: String,duration: Long = 180): CameraFrameProof {
        val frame = CameraFrameProbe(context).capture(id,duration,width,height)
        assertTrue("Actual camera $id must deliver increasing frames",frame.previewFrames >= 3 && frame.lastTimestamp > frame.firstTimestamp)
        assertEquals(width,frame.previewWidth); assertEquals(height,frame.previewHeight)
        assertEquals(width,frame.jpegWidth); assertEquals(height,frame.jpegHeight)
        assertTrue(frame.jpeg.isNotEmpty())
        assertTrue(frame.centerTimestampsNs.zipWithNext().all { (a,b) -> b > a })
        observations.put(frame.metrics().put("label",label)); save()
        return frame
    }
    private fun closeColor(a: Int,b: Int) = listOf(16,8,0).all { shift -> abs(((a ushr shift) and 255)-((b ushr shift) and 255)) < 35 }
    private fun colors(frame: CameraFrameProof): List<Int> {
        val image = requireNotNull(BitmapFactory.decodeByteArray(frame.jpeg,0,frame.jpeg.size))
        return try {
            listOf(1 to 1,3 to 1,1 to 3,3 to 3).map { (x,y) -> image.getPixel(image.width*x/4,image.height*y/4) }
        } finally { image.recycle() }
    }
    private fun verifyQuadrants(frame: CameraFrameProof) {
        val actual = colors(frame)
        val expected = listOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE)
        assertEquals("All four actual JPEG quadrants must survive reset",4,actual.map { pixel ->
            expected.indexOfFirst { closeColor(pixel,it) }.also { assertTrue("Unexpected actual quadrant color ${Integer.toHexString(pixel)}",it >= 0) }
        }.toSet().size)
    }
    private fun verifySolid(frame: CameraFrameProof,color: Int) {
        assertTrue("Every actual JPEG quadrant must reflect source pan/zoom",colors(frame).all { closeColor(it,color) })
    }
    private fun nativeStatus() = JSONObject(File(context.filesDir,"camera-runtime/status.json").readText())
    private fun assertStopped(controller: VirtualCameraController) {
        controller.deactivate()
        assertEquals("stopped",controller.status().getString("phase"))
        val deadline = SystemClock.elapsedRealtime()+2000
        while (nativeStatus().optBoolean("configured") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        assertFalse("Stop must remove every replacement route",nativeStatus().getBoolean("configured"))
        assertFalse(File(context.filesDir,"camera-runtime/source.cfg").exists())
        for (id in ids) capture(id,"stopped-passthrough")
    }

    @Test fun bothImageSwitchesTransformsResetAndSingleModesReachActualCameraFrames() {
        assumeTrue("Enable real camera replacement explicitly with bothCameraModes=true",
            InstrumentationRegistry.getArguments().getString("bothCameraModes") == "true")
        reportName = "both-image-modes.json"
        VirtualCameraController(context).use { controller ->
            var fixture: File? = null
            try {
                prepare(controller); fixture = quadrants()
                stage("both-image-activation")
                controller.update(settings()); controller.select(Uri.fromFile(fixture).toString(),"image")
                assertTrue(controller.activate())
                val before = compactProof(controller,"both",ids.toSet())
                report.put("bothActivation",before)
                val generation = before.getString("generation")
                for (id in cameraSwitchOrder()) {
                    verifyQuadrants(capture(id,"both-image-switch"))
                    assertEquals(generation,compactProof(controller,"both",ids.toSet()).getString("generation"))
                }
                for ((color,pan) in listOf(Color.RED to 1.0,Color.WHITE to -1.0)) {
                    stage("both-live-transform-$pan")
                    controller.applySourceTransform(2.0,pan,pan)
                    for (id in ids) verifySolid(capture(id,"both-zoom-pan"),color)
                    val current = compactProof(controller,"both",ids.toSet())
                    assertEquals(generation,current.getString("generation"))
                    assertEquals(before.getInt("proxyPid"),current.getInt("proxyPid"))
                }
                controller.applySourceTransform(1.0,0.0,0.0)
                for (id in ids) verifyQuadrants(capture(id,"both-transform-reset"))
                assertEquals(generation,compactProof(controller,"both",ids.toSet()).getString("generation"))
                val names = before.getJSONArray("cameras")
                val nameById = (0 until names.length()).associate { names.getJSONObject(it).let { item -> item.getString("cameraId") to item.getString("cameraName") } }
                for (selected in listOf(back,front)) {
                    stage("single-$selected")
                    controller.deactivate(); controller.update(settings(selected,"single")); assertTrue(controller.activate())
                    val active = compactProof(controller,"single",setOf(selected))
                    verifyQuadrants(capture(selected,"single-selected"))
                    val others = ids.filter { it != selected }
                    for (other in others) capture(other,"single-unselected-passthrough")
                    Thread.sleep(220)
                    capture(selected,"single-selected-after-other-camera")
                    val native = nativeStatus()
                    assertEquals(active.getString("generation"),native.getString("sourceId"))
                    val routes = native.getJSONArray("cameraNames")
                    assertEquals(1,routes.length()); assertEquals(nameById.getValue(selected),routes.getString(0))
                    for (other in others) assertEquals("The unselected camera $other must never consume selected-source frames",0L,
                        native.getJSONObject("cameraFrames").optLong(nameById.getValue(other),0))
                }
                stage("all-routes-stop"); assertStopped(controller)
                report.put("passed",true); stage("complete")
            } catch (error: Throwable) { report.put("failure",error.toString()); save(); throw error }
            finally { runCatching { controller.deactivate() }; fixture?.delete() }
        }
    }

    @Test fun liveVideoSourceUpdatesAndCameraSwitchesKeepBothRoutesAndOneGeneration() {
        assumeTrue("Enable real camera replacement explicitly with bothCameraModes=true",
            InstrumentationRegistry.getArguments().getString("bothCameraModes") == "true")
        reportName = "both-video-modes.json"
        VirtualCameraController(context).use { controller ->
            var image: File? = null; var movie: File? = null
            try {
                prepare(controller); image = quadrants(); movie = video()
                stage("both-image-before-video")
                controller.update(settings()); controller.select(Uri.fromFile(image).toString(),"image")
                assertTrue(controller.activate())
                val imageGeneration = compactProof(controller,"both",ids.toSet()).getString("generation")
                stage("both-live-image-to-video")
                controller.select(Uri.fromFile(movie).toString(),"video")
                val active = compactProof(controller,"both",ids.toSet())
                val generation = active.getString("generation")
                assertNotEquals(imageGeneration,generation); report.put("videoActivation",active)
                for (id in cameraSwitchOrder()) {
                    val frames = capture(id,"both-looping-video-switch",2600)
                    assertTrue(frames.lastTimestamp-frames.firstTimestamp >= 2_500_000_000L)
                    val stable = mutableListOf<String>()
                    var pending = ""; var streak = 0
                    for (luma in frames.centerLuma) {
                        val color = when (luma) { in 60..110 -> "red"; in 20..55 -> "blue"; else -> "" }
                        if (color.isEmpty()) { pending=""; streak=0; continue }
                        if (pending == color) streak++ else { pending=color; streak=1 }
                        if (streak >= 3 && stable.lastOrNull() != color) stable.add(color)
                    }
                    assertTrue("Actual video must change color and wrap its two-second loop: $stable",stable.size >= 3)
                    observations.put(JSONObject().put("label","video-loop-colors").put("cameraId",id)
                        .put("stableColors",JSONArray(stable)).put("frames",frames.previewFrames)); save()
                    val current = compactProof(controller,"both",ids.toSet())
                    assertEquals(generation,current.getString("generation")); assertEquals(active.getInt("proxyPid"),current.getInt("proxyPid"))
                }
                stage("both-live-video-to-image")
                controller.select(Uri.fromFile(image).toString(),"image")
                assertNotEquals(generation,compactProof(controller,"both",ids.toSet()).getString("generation"))
                for (id in ids) verifyQuadrants(capture(id,"both-after-video-image"))
                assertStopped(controller)
                report.put("passed",true); stage("complete")
            } catch (error: Throwable) { report.put("failure",error.toString()); save(); throw error }
            finally { runCatching { controller.deactivate() }; image?.delete(); movie?.delete() }
        }
    }
}
