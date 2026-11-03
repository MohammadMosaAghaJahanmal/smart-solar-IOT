package com.virtualcamerapro.overlay

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.virtualcamerapro.camera.CameraStateManager
import org.json.JSONObject

/** Explicit opt-in only. Permission, showing controls, and activation are separate actions. */
object FloatingCameraController {
    @Volatile internal var visible = false
    @Volatile internal var lastError: String? = null

    fun status(context: Context): JSONObject {
        val settings = CameraStateManager(context).settings
        return JSONObject().put("supported", true).put("permissionGranted", Settings.canDrawOverlays(context))
            .put("visible", visible).put("zoom", settings.zoom)
            .put("panX", settings.panX).put("panY", settings.panY)
            .put("message", lastError ?: "")
            .put("error", lastError ?: JSONObject.NULL)
    }

    fun requestPermission(context: Context): JSONObject {
        if (!Settings.canDrawOverlays(context)) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return status(context)
    }

    fun setVisible(context: Context, show: Boolean): JSONObject {
        val app = context.applicationContext
        if (show) {
            check(Settings.canDrawOverlays(app)) { "Allow display over other apps before showing floating controls" }
            lastError = null
            // Called by the foreground app after the user presses Show controls.
            app.startForegroundService(Intent(app, VirtualCameraOverlayService::class.java))
        } else {
            app.stopService(Intent(app, VirtualCameraOverlayService::class.java))
            visible = false
        }
        return status(app).put("requestedVisible", show)
    }
}
