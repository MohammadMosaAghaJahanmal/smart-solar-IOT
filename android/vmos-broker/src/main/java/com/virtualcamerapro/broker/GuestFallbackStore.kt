package com.virtualcamerapro.broker

import android.content.Context
import com.virtualcamerapro.engine.OutputSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, bounded hints. A saved winner never substitutes for fresh frame verification. */
internal class GuestFallbackStore(context: Context) {
    private val file = File(context.filesDir,"guest-working-outputs.json")
    data class Working(val settings: OutputSettings,val strategy: String)
    private fun request(type: String,value: OutputSettings) =
        "$type|${value.replacementMode}|${value.cameraId}|${value.width}x${value.height}"
    private fun records(identity: String): JSONArray = runCatching {
        check(file.isFile && file.length() in 1..65536)
        val root = JSONObject(file.readText())
        check(root.getInt("schema") == 1 && root.getString("environment") == identity)
        root.getJSONArray("outputs").also { check(it.length() <= 24) }
    }.getOrElse { JSONArray() }
    fun load(identity: String,type: String,requested: OutputSettings): Working? = runCatching {
        val entries = records(identity)
        val found = (0 until entries.length()).map { entries.getJSONObject(it) }
            .lastOrNull { it.getString("request") == request(type,requested) } ?: return null
        val strategy = found.getString("strategy")
        check(strategy in listOf("combined","sequential"))
        Working(requested.copy(cameraId=found.getString("cameraId"),width=found.getInt("width"),height=found.getInt("height")),strategy)
    }.getOrNull()
    fun save(identity: String,type: String,requested: OutputSettings,actual: OutputSettings,strategy: String) {
        require(strategy in listOf("combined","sequential") && type in listOf("image","video"))
        val key = request(type,requested)
        val entries = records(identity)
        val retained = (0 until entries.length()).map { entries.getJSONObject(it) }
            .filter { it.optString("request") != key }.takeLast(23)
        val outputs = JSONArray(retained).put(JSONObject().put("request",key).put("cameraId",actual.cameraId)
            .put("width",actual.width).put("height",actual.height).put("strategy",strategy)
            .put("verifiedAt",System.currentTimeMillis()))
        GuestRuntime.writeAtomic(file,JSONObject().put("schema",1).put("environment",identity)
            .put("outputs",outputs).toString().toByteArray(),0x180)
    }
}
