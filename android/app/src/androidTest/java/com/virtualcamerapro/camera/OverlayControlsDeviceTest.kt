package com.virtualcamerapro.camera

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.overlay.FloatingCameraController
import com.virtualcamerapro.probe.CameraFrameProbe
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.min

/** Opt-in real WindowManager gestures -> broker -> both actual YUV/JPEG camera routes. */
@RunWith(AndroidJUnit4::class)
class OverlayControlsDeviceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val observations = JSONArray()
    private val gestures = JSONArray()
    private val report = JSONObject().put("passed",false).put("controllerUid",Process.myUid())
        .put("observations",observations).put("gestures",gestures)
    private val directory get() = File(context.getExternalFilesDir(null),"overlay-controls").apply { mkdirs() }
    private var width = 0
    private var height = 0
    private lateinit var ids: List<String>
    private lateinit var generation: String
    private var proxyPid = 0
    private lateinit var sourceHash: String

    private fun save() { File(directory,"report.json").writeText(report.toString(2)) }
    private fun stage(label: String) { report.put("stage",label); save() }

    private fun find(description: String): Rect? {
        fun walk(node: AccessibilityNodeInfo): Rect? {
            try {
                // Moving an application overlay changes screen coordinates;
                // refresh remote nodes instead of reusing accessibility bounds.
                if (!node.refresh()) return null
                if (node.contentDescription?.toString() == description && node.isVisibleToUser) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) return bounds
                }
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    walk(child)?.let { return it }
                }
                return null
            } finally { @Suppress("DEPRECATION") node.recycle() }
        }
        for (window in automation.windows) {
            window.root?.let { root -> walk(root)?.let { return it } }
        }
        return null
    }
    private fun waitBounds(description: String,timeout: Long = 6000): Rect {
        val deadline = SystemClock.elapsedRealtime()+timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            find(description)?.let { return it }
            Thread.sleep(50)
        }
        error("Actual overlay control is not visible: $description")
    }
    private fun waitFor(label: String,condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime()+6000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(40)
        assertTrue(label,condition())
    }

    private fun event(downTime: Long,action: Int,points: List<Pair<Float,Float>>) {
        val properties = Array(points.size) { index -> MotionEvent.PointerProperties().apply {
            id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }
        val coordinates = Array(points.size) { index -> MotionEvent.PointerCoords().apply {
            x = points[index].first; y = points[index].second; pressure = 1f; size = 1f
        } }
        val event = MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,points.size,properties,coordinates,
            0,0,1f,1f,0,0,InputDevice.SOURCE_TOUCHSCREEN,0)
        try { assertTrue("Android must inject the real overlay gesture",automation.injectInputEvent(event,true)) }
        finally { event.recycle() }
    }
    private fun click(description: String) {
        val bounds = waitBounds(description)
        val point = bounds.exactCenterX() to bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        event(down,MotionEvent.ACTION_DOWN,listOf(point))
        SystemClock.sleep(45)
        event(down,MotionEvent.ACTION_UP,listOf(point))
        gestures.put(JSONObject().put("action","click").put("control",description)); save()
    }
    private fun drag(description: String,dx: Float,dy: Float) {
        val bounds = waitBounds(description)
        val x = bounds.exactCenterX(); val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        event(down,MotionEvent.ACTION_DOWN,listOf(x to y))
        for (step in 1..12) {
            SystemClock.sleep(22)
            event(down,MotionEvent.ACTION_MOVE,listOf(x+dx*step/12f to y+dy*step/12f))
        }
        event(down,MotionEvent.ACTION_UP,listOf(x+dx to y+dy))
        instrumentation.waitForIdleSync()
        gestures.put(JSONObject().put("action","drag").put("control",description).put("dx",dx).put("dy",dy)); save()
    }
    private fun pinch(outward: Boolean) {
        val pad = waitBounds(PAD)
        val x = pad.exactCenterX(); val y = pad.exactCenterY()
        val short = pad.width()*0.10f
        val long = pad.width()*0.28f
        val from = if (outward) short else long
        val to = if (outward) long else short
        val down = SystemClock.uptimeMillis()
        event(down,MotionEvent.ACTION_DOWN,listOf(x-from to y))
        event(down,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(x-from to y,x+from to y))
        for (step in 1..16) {
            SystemClock.sleep(22)
            val half = from+(to-from)*step/16f
            event(down,MotionEvent.ACTION_MOVE,listOf(x-half to y,x+half to y))
        }
        event(down,MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(x-to to y,x+to to y))
        event(down,MotionEvent.ACTION_UP,listOf(x-to to y))
        gestures.put(JSONObject().put("action",if (outward) "pinch-out" else "pinch-in")); save()
    }

    private fun quadrants(): File {
        val image = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(image); val paint = Paint()
        for ((index,color) in listOf(Color.RED,Color.GREEN,Color.BLUE,Color.WHITE).withIndex()) {
            paint.color = color
            val left = if (index%2 == 0) 0f else width/2f
            val top = if (index < 2) 0f else height/2f
            canvas.drawRect(left,top,left+width/2f,top+height/2f,paint)
        }
        // Interior markers distinguish real zoom from an unchanged four-quadrant preview.
        paint.color = Color.BLACK
        for (fraction in listOf(0.25f,0.75f)) {
            canvas.drawRect(width*fraction-width/48f,0f,width*fraction+width/48f,height.toFloat(),paint)
        }
        paint.color = Color.GRAY
        for (fraction in listOf(0.25f,0.75f)) {
            canvas.drawRect(0f,height*fraction-height/48f,width.toFloat(),height*fraction+height/48f,paint)
        }
        return File(context.cacheDir,"overlay-quadrants.png").also { file ->
            try { file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { image.recycle() }
        }
    }
    private fun stable(controller: VirtualCameraController): JSONObject {
        val status = controller.status()
        assertEquals("active",status.getString("phase"))
        assertEquals("both",status.getString("replacementMode"))
        val settings = controller.state.settings
        assertEquals("both",settings.replacementMode)
        assertEquals(width,settings.width); assertEquals(height,settings.height)
        val proof = status.getJSONObject("proof")
        assertEquals(generation,proof.getString("generation"))
        assertEquals(proxyPid,proof.getInt("proxyPid"))
        assertEquals(sourceHash,proof.getString("sourceSha256"))
        val routes = proof.getJSONArray("cameraIds")
        assertEquals(ids.toSet(),(0 until routes.length()).map { routes.getString(it) }.toSet())
        return JSONObject().put("generation",generation).put("proxyPid",proxyPid)
            .put("replacementMode",settings.replacementMode).put("cameraIds",JSONArray(ids))
            .put("zoom",settings.zoom).put("panX",settings.panX).put("panY",settings.panY)
            .put("liveTransformSequence",proof.optLong("liveTransformSequence"))
    }
    private fun actualFrames(controller: VirtualCameraController,label: String): Map<String,List<Int>> {
        val result = linkedMapOf<String,List<Int>>()
        for (id in ids) {
            val frame = CameraFrameProbe(context).capture(id,200,width,height)
            assertTrue("Actual route $id must deliver increasing YUV frames",frame.previewFrames >= 3 && frame.lastTimestamp > frame.firstTimestamp)
            assertEquals(width,frame.previewWidth); assertEquals(height,frame.previewHeight)
            assertEquals(width,frame.jpegWidth); assertEquals(height,frame.jpegHeight)
            assertTrue(frame.centerTimestampsNs.zipWithNext().all { (first,last) -> last > first })
            val jpeg = requireNotNull(BitmapFactory.decodeByteArray(frame.jpeg,0,frame.jpeg.size))
            try {
                val pixels = (1..7).flatMap { y -> (1..7).map { x -> jpeg.getPixel(jpeg.width*x/8,jpeg.height*y/8) } }
                result[id] = pixels
                val center = jpeg.getPixel(jpeg.width/2,jpeg.height/2)
                val expectedLuma = 16.0+0.257*Color.red(center)+0.504*Color.green(center)+0.098*Color.blue(center)
                val actualLuma = frame.centerLuma.takeLast(3).average()
                assertTrue("Actual YUV and JPEG must show the same transformed source on camera $id",abs(actualLuma-expectedLuma) < 32.0)
                val bytes = pixels.flatMap { listOf(Color.red(it).toByte(),Color.green(it).toByte(),Color.blue(it).toByte()) }.toByteArray()
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
                observations.put(frame.metrics().put("label",label).put("centerRgb",Integer.toHexString(center))
                    .put("centerYuvLuma",actualLuma).put("jpegExpectedLuma",expectedLuma).put("sampleSha256",digest)
                    .put("session",stable(controller)))
                File(directory,"$label-camera-${ids.indexOf(id)}.jpg").writeBytes(frame.jpeg)
                save()
            } finally { jpeg.recycle() }
        }
        return result
    }
    private fun difference(a: List<Int>,b: List<Int>): Double {
        assertEquals(a.size,b.size)
        return a.indices.sumOf { index ->
            abs(Color.red(a[index])-Color.red(b[index]))+abs(Color.green(a[index])-Color.green(b[index]))+abs(Color.blue(a[index])-Color.blue(b[index]))
        }.toDouble()/(a.size*3)
    }

    @Test fun actualFloatingPinchDragButtonsAndResetChangeBothCameraOutputs() {
        assumeTrue("Enable actual overlay acceptance explicitly with overlayControls=true",
            InstrumentationRegistry.getArguments().getString("overlayControls") == "true")
        assertTrue(Process.myUid() >= 10000)
        assertEquals(PackageManager.PERMISSION_GRANTED,context.checkSelfPermission(Manifest.permission.CAMERA))
        assertTrue("Grant display over other apps through Android Settings before this opt-in test",Settings.canDrawOverlays(context))
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        var fixture: File? = null
        VirtualCameraController(context).use { controller ->
            try {
                context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                instrumentation.waitForIdleSync()
                val support = controller.support()
                assertTrue("Prepare and verify the real camera bridge first: $support",support.getBoolean("supported"))
                assertEquals("vmos_camera_proxy",support.getString("backend"))
                val routes = support.getJSONArray("bothCameraIds")
                ids = (0 until routes.length()).map { routes.getString(it) }
                assertTrue("Both-camera controls must cover actual front/back routes",ids.size >= 2)
                assertEquals("Each detected camera route must be unique",ids.size,ids.toSet().size)
                val choices = support.getJSONArray("bothResolutions")
                val sizes = (0 until choices.length()).map { choices.getJSONObject(it) }
                val selected = sizes.filter { it.getInt("width").toLong()*it.getInt("height") <= 1280L*720 }
                    .maxByOrNull { it.getInt("width").toLong()*it.getInt("height") }
                    ?: sizes.minBy { it.getInt("width").toLong()*it.getInt("height") }
                width = selected.getInt("width"); height = selected.getInt("height")
                report.put("cameraIds",JSONArray(ids)).put("width",width).put("height",height)
                controller.deactivate()
                controller.update(OutputSettings(width=width,height=height,cameraId=ids.first(),scaleMode="fill",replacementMode="both"))
                fixture = quadrants(); controller.select(Uri.fromFile(fixture).toString(),"image")
                assertTrue(controller.activate())
                val proof = controller.status().getJSONObject("proof")
                generation = proof.getString("generation"); proxyPid = proof.getInt("proxyPid"); sourceHash = proof.getString("sourceSha256")
                stage("show-real-overlay")
                FloatingCameraController.setVisible(context,true)
                waitFor("The actual overlay service must attach its window") { FloatingCameraController.status(context).getBoolean("visible") }
                // visible is set when WindowManager attaches the panel, before
                // its first layout/accessibility traversal completes.
                if (runCatching { waitBounds(PAD,1500) }.isFailure) click(COLLAPSE)
                waitBounds(PAD)
                // A real unrelated application remains in front while the overlay controls the feed.
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                instrumentation.waitForIdleSync()
                waitBounds(PAD)
                var activePackage: String? = null
                waitFor("Android Settings must receive focus behind the non-focusable overlay") {
                    activePackage = automation.windows.firstOrNull { it.isFocused }?.root?.let { root ->
                        try { root.packageName?.toString() } finally { @Suppress("DEPRECATION") root.recycle() }
                    }
                    activePackage == "com.android.settings"
                }
                assertNotEquals("A separate app must remain behind the floating controller",context.packageName,activePackage)
                report.put("hostApplicationPackage",activePackage)
                stage("baseline-actual-yuv-jpeg")
                val baseline = actualFrames(controller,"baseline")

                stage("injected-two-pointer-pinch-out")
                pinch(true)
                waitFor("Pinch-out must increase actual renderer zoom") { controller.state.settings.zoom > 1.15 }
                val pinchedZoom = controller.state.settings.zoom
                val zoomed = actualFrames(controller,"pinch-out")
                for (id in ids) assertTrue("Pinch must change actual JPEG camera $id",difference(baseline.getValue(id),zoomed.getValue(id)) > 10.0)

                stage("injected-touchpad-drag")
                val pad = waitBounds(PAD)
                drag(PAD,pad.width()*0.25f,pad.height()*0.25f)
                waitFor("Touchpad dragging must change source pan") { controller.state.settings.panX > 0.25 && controller.state.settings.panY > 0.25 }
                val moved = actualFrames(controller,"drag")
                for (id in ids) assertTrue("Dragging must change actual JPEG camera $id",difference(zoomed.getValue(id),moved.getValue(id)) > 10.0)

                stage("injected-two-pointer-pinch-in")
                pinch(false)
                waitFor("Pinch-in must reduce actual renderer zoom") { controller.state.settings.zoom < pinchedZoom-0.1 }
                stable(controller)
                val beforeOut = controller.state.settings.zoom
                click("Zoom virtual media in")
                waitFor("The zoom button must update the actual source settings") { controller.state.settings.zoom > beforeOut || controller.state.settings.zoom == 8.0 }
                val beforeMinus = controller.state.settings.zoom
                click("Zoom virtual media out")
                waitFor("Zoom-out button must update the source") { controller.state.settings.zoom < beforeMinus || beforeMinus == 1.0 }

                stage("move-window-without-changing-source")
                val beforeWindow = controller.state.settings
                val oldWindow = waitBounds(WINDOW)
                val screenWidth = context.resources.displayMetrics.widthPixels
                val screenHeight = context.resources.displayMetrics.heightPixels
                val dx = if (oldWindow.left > screenWidth/4) -min(oldWindow.left/2f,screenWidth*0.12f)
                    else min((screenWidth-oldWindow.right)/2f,screenWidth*0.12f)
                val dy = if (oldWindow.top > screenHeight/3) -min(oldWindow.top/2f,screenHeight*0.08f)
                    else min((screenHeight-oldWindow.bottom)/2f,screenHeight*0.08f)
                drag("Move floating camera controls",dx,dy)
                waitFor("Dragging the separate header must move the real window") { find(WINDOW)?.let { it.left != oldWindow.left || it.top != oldWindow.top } == true }
                assertEquals("Moving the window must preserve source settings",beforeWindow,controller.state.settings)
                stable(controller)
                click(COLLAPSE)
                waitFor("Collapse must hide the media touchpad") { find(PAD) == null }
                click(COLLAPSE); waitBounds(PAD)
                assertEquals(beforeWindow,controller.state.settings)

                stage("reset-real-frame-transform")
                click("Reset virtual media zoom and position")
                waitFor("Reset must reach persisted source settings") {
                    controller.state.settings.let { it.zoom == 1.0 && it.panX == 0.0 && it.panY == 0.0 }
                }
                val reset = actualFrames(controller,"reset")
                for (id in ids) {
                    val error = difference(baseline.getValue(id),reset.getValue(id))
                    assertTrue("Reset must restore original actual camera $id pixels: MAE=$error",error < 12.0)
                }
                stage("close-controls-with-feed-active")
                click("Close floating camera controls")
                waitFor("Close must remove the actual overlay window") { !FloatingCameraController.status(context).getBoolean("visible") && find(WINDOW) == null }
                stable(controller)
                val afterClose = actualFrames(controller,"after-close")
                for (id in ids) assertTrue(difference(baseline.getValue(id),afterClose.getValue(id)) < 12.0)
                report.put("passed",true); stage("complete")
            } catch (error: Throwable) {
                report.put("failure",error.toString())
                report.put("lastWindow",find(WINDOW)?.flattenToString())
                report.put("lastCollapse",find(COLLAPSE)?.flattenToString())
                report.put("lastPad",find(PAD)?.flattenToString())
                runCatching { automation.takeScreenshot()?.let { bitmap ->
                    try { File(directory,"failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } }
                    finally { bitmap.recycle() }
                } }
                save(); throw error
            }
            finally {
                runCatching { FloatingCameraController.setVisible(context,false) }
                runCatching { controller.deactivate() }
                fixture?.delete()
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                save()
            }
        }
    }

    companion object {
        private const val PAD = "Pinch to zoom and drag to move actual virtual camera frames"
        private const val WINDOW = "Floating virtual camera controller"
        private const val COLLAPSE = "Expand or collapse floating camera controls"
    }
}
