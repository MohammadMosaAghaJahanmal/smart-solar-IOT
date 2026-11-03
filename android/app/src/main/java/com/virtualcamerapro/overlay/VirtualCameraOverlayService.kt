package com.virtualcamerapro.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.virtualcamerapro.MainActivity
import com.virtualcamerapro.R
import com.virtualcamerapro.camera.CameraStateManager
import com.virtualcamerapro.camera.VirtualCameraController
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A small, non-focusable window. Its touch pad changes the actual broker renderer;
 * its separate header moves only the floating window. It never activates a camera.
 */
class VirtualCameraOverlayService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var windows: WindowManager
    private lateinit var panel: LinearLayout
    private lateinit var body: LinearLayout
    private lateinit var bodyWindow: ScrollView
    private lateinit var readout: TextView
    private lateinit var phaseReadout: TextView
    private lateinit var layout: WindowManager.LayoutParams
    private lateinit var state: CameraStateManager
    private lateinit var preferences: SharedPreferences
    private lateinit var dispatcher: TransformDispatcher
    private var transform = SourceTransform.ORIGINAL
    private var cameraPhase = "unknown"
    private var collapsed = false
    private var attached = false
    private var destroyed = false
    private val refresh: Runnable = object : Runnable {
        override fun run() {
          if (!destroyed && attached) {
            if (!Settings.canDrawOverlays(this@VirtualCameraOverlayService)) {
                FloatingCameraController.lastError = "Display over other apps permission was removed"
                stopSelf()
            } else if (!dispatcher.hasPending()) {
                readSettings()
                showReadout()
            }
            main.postDelayed(this, 2000)
          }
        }
    }
    private val settingListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        main.removeCallbacks(refresh)
        main.post(refresh)
    }

    override fun onCreate() {
        super.onCreate()
        windows = getSystemService(WindowManager::class.java)
        state = CameraStateManager(this)
        preferences = getSharedPreferences("virtual-camera-overlay", MODE_PRIVATE)
        val controller = VirtualCameraController(applicationContext)
        dispatcher = TransformDispatcher(apply = { value ->
            controller.applySourceTransform(value.zoom, value.panX, value.panY)
        }, readPhase = controller::cameraPhase, onClosed = controller::close, result = { value, error ->
            main.post {
                if (!destroyed) {
                    if (error != null) {
                        FloatingCameraController.lastError = error
                        readout.text = "Cannot update camera: $error"
                    } else if (!dispatcher.hasPending()) {
                        transform = value
                        FloatingCameraController.lastError = null
                        showReadout()
                    }
                }
            }
        }, phaseResult = { phase ->
            main.post {
                if (!destroyed) {
                    cameraPhase = phase
                    if (attached) showPhase()
                }
            }
        })
        getSharedPreferences("virtual-camera", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(settingListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLOSE) { stopSelf(); return START_NOT_STICKY }
        if (!Settings.canDrawOverlays(this)) {
            FloatingCameraController.lastError = "Allow display over other apps before showing floating controls"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startControlsNotification()
            if (!attached) attachWindow()
        } catch (error: Exception) {
            FloatingCameraController.lastError = error.message ?: "Floating controls could not be opened"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startControlsNotification() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Floating camera controls", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_vcam)
            .setContentTitle("Virtual camera controls")
            .setContentText("Drag or pinch the floating pad to adjust the active feed")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Hide controls", close).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION, notification)
    }

    private fun attachWindow() {
        readSettings()
        collapsed = preferences.getBoolean("collapsed", false)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = background(Color.rgb(24, 30, 45), 14f)
            elevation = dp(10).toFloat()
            contentDescription = "Floating virtual camera controller"
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val handle = TextView(this).apply {
            id = ID_WINDOW_HANDLE
            text = "Camera controls  ⋮⋮"
            setTextColor(Color.WHITE); textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "Move floating camera controls"
            setPadding(dp(4), 0, 0, 0)
            setOnTouchListener(windowDrag())
        }
        header.addView(handle, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(button(if (collapsed) "+" else "−", "Expand or collapse floating camera controls", ID_COLLAPSE) {
            collapsed = !collapsed
            bodyWindow.visibility = if (collapsed) View.GONE else View.VISIBLE
            (header.getChildAt(1) as Button).text = if (collapsed) "+" else "−"
            preferences.edit().putBoolean("collapsed", collapsed).apply()
            panel.post { clampWindow(); updateWindow() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        header.addView(button("×", "Close floating camera controls", ID_CLOSE) { stopSelf() }, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bodyWindow = ScrollView(this).apply {
            visibility = if (collapsed) View.GONE else View.VISIBLE
            isFillViewport = false
            addView(body)
        }
        phaseReadout = TextView(this).apply {
            id = ID_PHASE; textSize = 12f; gravity = Gravity.CENTER
            contentDescription = "Virtual camera feed status"
        }
        body.addView(phaseReadout)
        readout = TextView(this).apply {
            id = ID_READOUT; setTextColor(Color.rgb(184, 211, 255)); textSize = 12f
            gravity = Gravity.CENTER; setPadding(0, dp(5), 0, dp(6))
            maxLines = 3
            contentDescription = "Virtual media zoom and position"
        }
        body.addView(readout)
        body.addView(touchPad(), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(118)))
        body.addView(row(
            button("− Zoom", "Zoom virtual media out", ID_ZOOM_OUT) { change(current().scale(1.0 / 1.15)) },
            button("+ Zoom", "Zoom virtual media in", ID_ZOOM_IN) { change(current().scale(1.15)) },
            button("Reset", "Reset virtual media zoom and position", ID_RESET) { change(SourceTransform.ORIGINAL) }
        ))
        body.addView(row(
            button("←", "Move virtual media left", ID_LEFT) { change(current().move(-0.12, 0.0)) },
            button("↑", "Move virtual media up", ID_UP) { change(current().move(0.0, -0.12)) },
            button("↓", "Move virtual media down", ID_DOWN) { change(current().move(0.0, 0.12)) },
            button("→", "Move virtual media right", ID_RIGHT) { change(current().move(0.12, 0.0)) }
        ))
        body.addView(TextView(this).apply {
            text = "Zoom in to move within the image.\nChanges affect the actual camera feed."
            setTextColor(Color.LTGRAY); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, 0)
        })
        panel.addView(bodyWindow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bodyWindowHeight()))
        showReadout()
        showPhase()
        layout = WindowManager.LayoutParams(windowWidth(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt("x", dp(12)); y = preferences.getInt("y", dp(80))
            title = "Virtual Camera Pro floating controls"
        }
        windows.addView(panel, layout)
        attached = true
        FloatingCameraController.visible = true
        panel.post { clampWindow(); updateWindow() }
        main.post(refresh)
    }

    private fun touchPad(): View = TextView(this).apply {
        id = ID_TOUCH_PAD
        text = "Pinch to zoom\nDrag to move media"
        textSize = 15f; gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = background(Color.rgb(46, 59, 83), 8f)
        contentDescription = "Pinch to zoom and drag to move actual virtual camera frames"
        val scale = ScaleGestureDetector(this@VirtualCameraOverlayService,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    change(transform.scale(detector.scaleFactor.toDouble()))
                    return true
                }
            })
        var lastX = 0f
        var lastY = 0f
        var moved = false
        setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                current(); lastX = event.x; lastY = event.y; moved = false
                view.parent.requestDisallowInterceptTouchEvent(true)
            }
            scale.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount == 1 && !scale.isInProgress) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (abs(dx) + abs(dy) > 0f) {
                            moved = true
                            change(transform.move(2.0 * dx / view.width.coerceAtLeast(1), 2.0 * dy / view.height.coerceAtLeast(1)))
                        }
                    }
                    lastX = event.x; lastY = event.y
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(remaining); lastY = event.getY(remaining)
                }
                MotionEvent.ACTION_UP -> { if (!moved) view.performClick() }
            }
            true
        }
    }

    private fun current(): SourceTransform {
        if (!dispatcher.hasPending()) readSettings()
        return transform
    }
    private fun readSettings() { val settings = state.settings; transform = SourceTransform(settings.zoom, settings.panX, settings.panY) }
    private fun change(value: SourceTransform) { transform = value.bounded(); showReadout(); dispatcher.submit(transform) }
    private fun showReadout() {
        readout.text = String.format(Locale.US, "%.2f×   Horizontal %+.2f   Vertical %+.2f", transform.zoom, transform.panX, transform.panY)
    }
    private fun showPhase() {
        phaseReadout.text = when (cameraPhase) {
            "active" -> "Feed active"
            "activating" -> "Feed activating…"
            "error" -> "Feed error — check Virtual Camera Pro"
            "ready", "stopped" -> "Feed inactive"
            "integration_required" -> "Camera setup required"
            "unavailable" -> "Camera feed unavailable"
            else -> "Checking camera feed…"
        }
        phaseReadout.setTextColor(if (cameraPhase == "active") Color.rgb(147, 230, 170) else Color.LTGRAY)
    }
    private fun row(vararg views: View) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        views.forEach { addView(it, LinearLayout.LayoutParams(0, dp(44), 1f)) }
    }
    private fun button(label: String, description: String, viewId: Int, action: () -> Unit) = Button(this).apply {
        id = viewId; text = label; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; setPadding(dp(2), 0, dp(2), 0)
        contentDescription = description
        setOnClickListener { action() }
    }
    private fun background(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = dp(value.toFloat())
    private fun dp(value: Float) = (value * resources.displayMetrics.density).roundToInt()

    private fun windowDrag(): View.OnTouchListener {
        var startX = 0f; var startY = 0f; var originX = 0; var originY = 0
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = event.rawX; startY = event.rawY; originX = layout.x; originY = layout.y }
                MotionEvent.ACTION_MOVE -> {
                    layout.x = originX + (event.rawX - startX).roundToInt()
                    layout.y = originY + (event.rawY - startY).roundToInt()
                    clampWindow(); updateWindow()
                }
                MotionEvent.ACTION_UP -> {
                    preferences.edit().putInt("x", layout.x).putInt("y", layout.y).apply()
                    view.performClick()
                }
            }
            true
        }
    }
    private fun usableSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            return Pair(metrics.bounds.width() - insets.left - insets.right, metrics.bounds.height() - insets.top - insets.bottom)
        }
        @Suppress("DEPRECATION") val metrics = android.util.DisplayMetrics().also { windows.defaultDisplay.getMetrics(it) }
        return Pair(metrics.widthPixels, metrics.heightPixels)
    }
    private fun windowWidth() = min(dp(296), (usableSize().first - dp(12)).coerceAtLeast(1))
    private fun bodyWindowHeight() = min(dp(286), (usableSize().second - dp(72)).coerceAtLeast(dp(48)))
    private fun clampWindow() {
        val (width, height) = usableSize()
        layout.x = layout.x.coerceIn(0, (width - layout.width).coerceAtLeast(0))
        layout.y = layout.y.coerceIn(0, (height - panel.height).coerceAtLeast(0))
    }
    private fun updateWindow() {
        if (!attached) return
        try { windows.updateViewLayout(panel, layout) }
        catch (error: RuntimeException) { FloatingCameraController.lastError = error.message ?: "Floating controls were closed"; stopSelf() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (attached) {
            layout.width = windowWidth()
            bodyWindow.layoutParams = bodyWindow.layoutParams.apply { height = bodyWindowHeight() }
            panel.post { clampWindow(); updateWindow() }
        }
    }
    override fun onDestroy() {
        destroyed = true
        main.removeCallbacksAndMessages(null)
        getSharedPreferences("virtual-camera", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(settingListener)
        dispatcher.close()
        if (attached) { try { windows.removeView(panel) } catch (_: RuntimeException) {}; attached = false }
        FloatingCameraController.visible = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "virtual-camera-overlay"
        private const val NOTIFICATION = 2402
        private const val ACTION_CLOSE = "com.virtualcamerapro.overlay.CLOSE"
        const val ID_WINDOW_HANDLE = 0x00fc0101
        const val ID_COLLAPSE = 0x00fc0102
        const val ID_CLOSE = 0x00fc0103
        const val ID_TOUCH_PAD = 0x00fc0104
        const val ID_ZOOM_OUT = 0x00fc0105
        const val ID_ZOOM_IN = 0x00fc0106
        const val ID_RESET = 0x00fc0107
        const val ID_LEFT = 0x00fc0108
        const val ID_UP = 0x00fc0109
        const val ID_DOWN = 0x00fc010a
        const val ID_RIGHT = 0x00fc010b
        const val ID_READOUT = 0x00fc010c
        const val ID_PHASE = 0x00fc010d
    }
}
