package com.virtualcamerapro.broker

import android.content.Context
import com.virtualcamerapro.engine.OutputSettings
import org.json.JSONObject
import java.io.File

/** Private native-service state; a persisted request is never persisted Active proof. */
internal class BrokerSessionStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("guest-broker-session-v1",Context.MODE_PRIVATE)
    data class Saved(val file: File,val type: String,val hash: String,val settings: OutputSettings)
    fun requested() = runCatching { preferences.getBoolean("requestedActive",false) }.getOrDefault(false)
    fun inactive() { check(preferences.edit().putBoolean("requestedActive",false).commit()) }
    fun clear() {
        val value = runCatching { JSONObject(preferences.getString("source",null) ?: "{}") }.getOrNull()
        val name = value?.optString("file")
        check(preferences.edit().clear().commit())
        if (name != null && name.matches(Regex("source-[A-Za-z0-9._-]+\\.media"))) {
            val file = File(context.filesDir,name)
            if (file.canonicalFile.parentFile == context.filesDir.canonicalFile) file.delete()
        }
    }
    fun save(file: File,type: String,hash: String,settings: OutputSettings) {
        val value = JSONObject().put("file",file.name).put("type",type).put("sha256",hash)
            .put("width",settings.width).put("height",settings.height).put("loop",settings.loop)
            .put("rotation",settings.rotation).put("scaleMode",settings.scaleMode).put("cameraId",settings.cameraId)
            .put("zoom",settings.zoom).put("panX",settings.panX).put("panY",settings.panY)
            .put("replacementMode",settings.replacementMode)
        check(preferences.edit().putString("source",value.toString()).putBoolean("requestedActive",true).commit())
    }
    fun load(): Saved? = runCatching {
        val value = JSONObject(preferences.getString("source",null) ?: return null)
        val name = value.getString("file")
        require(name.matches(Regex("source-[A-Za-z0-9._-]+\\.media")))
        val file = File(context.filesDir,name)
        require(file.isFile && file.canonicalFile.parentFile == context.filesDir.canonicalFile)
        val hash = value.getString("sha256")
        require(GuestRuntime.sha256(file) == hash)
        val type = value.getString("type"); require(type in setOf("image","video"))
        Saved(file,type,hash,OutputSettings(value.getInt("width"),value.getInt("height"),value.getBoolean("loop"),
            value.getInt("rotation"),value.getString("scaleMode"),value.getString("cameraId"),
            value.optDouble("zoom",1.0),value.optDouble("panX",0.0),value.optDouble("panY",0.0),
            value.optString("replacementMode","single")))
    }.getOrNull()
}
