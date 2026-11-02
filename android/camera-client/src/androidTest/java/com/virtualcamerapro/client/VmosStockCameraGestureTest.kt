package com.virtualcamerapro.client

import android.app.UiAutomation
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Xml
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Injects into the already-open stock Camera; never opens a camera or Activity. */
@RunWith(AndroidJUnit4::class)
class VmosStockCameraGestureTest {
    @Suppress("DEPRECATION")
    @Test fun twoFingerPinchInAlreadyForegroundStockCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals(28, Build.VERSION.SDK_INT)
        assertEquals("vphw90", Build.HARDWARE)
        val zoom = args.getString("zoom", "in")
        require(zoom == "in" || zoom == "out") { "zoom must be in or out" }
        val centerX = args.getString("centerX", "540").toFloat()
        val centerY = args.getString("centerY", "1000").toFloat()
        val near = args.getString("nearRadius", "100").toFloat()
        val far = args.getString("farRadius", "350").toFloat()
        val duration = args.getString("durationMs", "400").toLong()
        val moves = args.getString("moves", "20").toInt()
        require(listOf(centerX, centerY, near, far).all { it.isFinite() })
        require(near > 0 && far > near && duration in 200..2000 && moves in 8..80)
        val size = Point()
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealSize(size)
        require(centerX - far >= 0 && centerX + far < size.x && centerY >= 0 && centerY < size.y) {
            "Requested two-finger coordinates lie outside the guest display ${size.x}x${size.y}"
        }
        val startRadius = if (zoom == "in") near else far
        val endRadius = if (zoom == "in") far else near
        val directory = File(context.getExternalFilesDir(null), "stock-camera-gesture/zoom-$zoom").apply { check(mkdirs() || isDirectory) }
        val report = JSONObject().put("injectionPassed", false).put("cameraZoomVerified", false)
            .put("foregroundRequired", STOCK_CAMERA).put("zoomDirection", zoom)
            .put("centerX", centerX).put("centerY", centerY).put("startRadius", startRadius).put("endRadius", endRadius)
            .put("requestedDurationMs", duration).put("moves", moves).put("displayWidth", size.x).put("displayHeight", size.y)
            .put("scope", "Input injection only. Actual camera crop metadata and image pixels require independent observation.")
        val events = JSONArray(); report.put("events", events)
        val automation = instrumentation.uiAutomation
        var down = false
        var activePointers = 1
        var currentRadius = startRadius
        val downTime = SystemClock.uptimeMillis()
        fun save() = File(directory, "gesture-result.json").writeText(report.toString(2))
        fun foreground() {
            val root = requireNotNull(automation.rootInActiveWindow) { "No foreground accessibility window" }
            try { check(root.packageName?.toString() == STOCK_CAMERA) { "Stock Camera must remain the foreground application" } }
            finally { root.recycle() }
        }
        fun inject(action: Int, count: Int, radius: Float, requireCamera: Boolean = true) {
            if (requireCamera) foreground()
            val properties = Array(count) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(count) { index -> MotionEvent.PointerCoords().apply {
                x = centerX + if (index == 0) -radius else radius
                y = centerY; pressure = 1f; this.size = 0.1f
            } }
            val eventTime = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(downTime, eventTime, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            val ok = try { automation.injectInputEvent(event, true) } finally { event.recycle() }
            events.put(JSONObject().put("action", action).put("pointerCount", count).put("radius", radius)
                .put("eventTimeMs", eventTime).put("injected", ok))
            check(ok) { "UiAutomation rejected MotionEvent action=$action count=$count" }
        }
        try {
            foreground()
            dump(automation, File(directory, "before.xml"))
            inject(MotionEvent.ACTION_DOWN, 1, startRadius); down = true
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, startRadius)
            activePointers = 2
            val moveStart = SystemClock.uptimeMillis()
            for (index in 1..moves) {
                val due = moveStart + duration * index / moves
                val wait = due - SystemClock.uptimeMillis()
                if (wait > 0) SystemClock.sleep(wait)
                currentRadius = startRadius + (endRadius - startRadius) * index / moves
                inject(MotionEvent.ACTION_MOVE, 2, currentRadius)
            }
            inject(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, endRadius)
            activePointers = 1
            SystemClock.sleep(16)
            inject(MotionEvent.ACTION_UP, 1, endRadius); down = false
            report.put("actualInputSequenceMs", SystemClock.uptimeMillis() - downTime)
            foreground()
            SystemClock.sleep(300)
            foreground()
            dump(automation, File(directory, "after.xml"))
            report.put("injectionPassed", true)
        } catch (error: Throwable) {
            report.put("failure", error.toString())
            runCatching { dump(automation, File(directory, "failure.xml")) }
            throw error
        } finally {
            if (down) runCatching {
                // Release only while the intended app still owns the window.
                foreground(); inject(MotionEvent.ACTION_CANCEL, activePointers, currentRadius)
            }.onFailure { report.put("cancelFailure", it.toString()) }
            save()
        }
    }

    @Suppress("DEPRECATION")
    private fun dump(automation: UiAutomation, destination: File) {
        val root = requireNotNull(automation.rootInActiveWindow) { "No active window to dump" }
        try {
            check(root.packageName?.toString() == STOCK_CAMERA) { "Stock Camera foreground is required for the UI dump" }
            destination.outputStream().use { output ->
                val xml = Xml.newSerializer(); xml.setOutput(output, "UTF-8"); xml.startDocument("UTF-8", true)
                xml.startTag(null, "hierarchy")
                var count = 0
                fun node(value: AccessibilityNodeInfo, depth: Int) {
                    check(depth <= 64 && ++count <= 10000) { "Accessibility hierarchy exceeds diagnostic bounds" }
                    val bounds = Rect(); value.getBoundsInScreen(bounds)
                    xml.startTag(null, "node")
                    xml.attribute(null, "package", value.packageName?.toString().orEmpty())
                    xml.attribute(null, "class", value.className?.toString().orEmpty())
                    xml.attribute(null, "resource-id", value.viewIdResourceName.orEmpty())
                    xml.attribute(null, "text", value.text?.toString().orEmpty())
                    xml.attribute(null, "content-desc", value.contentDescription?.toString().orEmpty())
                    xml.attribute(null, "bounds", bounds.toShortString())
                    xml.attribute(null, "clickable", value.isClickable.toString())
                    xml.attribute(null, "enabled", value.isEnabled.toString())
                    for (index in 0 until value.childCount) value.getChild(index)?.let { child ->
                        try { node(child, depth + 1) } finally { child.recycle() }
                    }
                    xml.endTag(null, "node")
                }
                node(root, 0); xml.endTag(null, "hierarchy"); xml.endDocument()
            }
        } finally { root.recycle() }
    }

    companion object { private const val STOCK_CAMERA = "com.android.camera2" }
}
