package org.json

class JSONArray(value: Any)
class JSONObject {
    private val values = mutableMapOf<String, Any?>()
    fun put(key: String, value: Any?): JSONObject { values[key] = value; return this }
    fun optBoolean(key: String): Boolean = values[key] == true
    fun optString(key: String): String = values[key]?.toString() ?: ""
}
