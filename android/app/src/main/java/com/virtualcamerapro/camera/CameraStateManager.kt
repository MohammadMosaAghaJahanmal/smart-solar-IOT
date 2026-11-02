package com.virtualcamerapro.camera

import android.content.Context
import com.virtualcamerapro.engine.OutputSettings
import org.json.JSONObject

class CameraStateManager(context: Context) {
    private val prefs = context.getSharedPreferences("virtual-camera",Context.MODE_PRIVATE)
    var uri: String get() = prefs.getString("uri", "")!!; set(value) { prefs.edit().putString("uri",value).apply() }
    var type: String get() = prefs.getString("type", "image")!!; set(value) { prefs.edit().putString("type",value).apply() }
    var name: String get() = prefs.getString("name", "")!!; set(value) { prefs.edit().putString("name",value).apply() }
    var settings: OutputSettings
        get() = OutputSettings(prefs.getInt("width",1280),prefs.getInt("height",720),prefs.getBoolean("loop",true),prefs.getInt("rotation",0),prefs.getString("scaleMode","fit")!!,prefs.getString("cameraId","0")!!,
            prefs.getFloat("zoom",1f).toDouble(),prefs.getFloat("panX",0f).toDouble(),prefs.getFloat("panY",0f).toDouble())
        set(s) { prefs.edit().putInt("width",s.width).putInt("height",s.height).putBoolean("loop",s.loop).putInt("rotation",s.rotation).putString("scaleMode",s.scaleMode).putString("cameraId",s.cameraId)
            .putFloat("zoom",s.zoom.toFloat()).putFloat("panX",s.panX.toFloat()).putFloat("panY",s.panY.toFloat()).apply() }
    fun settingsJson(s: OutputSettings = settings) = JSONObject().put("width",s.width).put("height",s.height).put("loop",s.loop).put("rotation",s.rotation).put("scaleMode",s.scaleMode).put("cameraId",s.cameraId)
        .put("zoom",s.zoom).put("panX",s.panX).put("panY",s.panY)
}
