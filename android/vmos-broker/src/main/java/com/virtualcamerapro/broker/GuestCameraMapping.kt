package com.virtualcamerapro.broker

import android.content.Context
import android.os.Build
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A public Camera2 ID is opaque; neither its number nor its order identifies a transport. */
internal object GuestCameraMapping {
    data class FactoryCamera(val name: String, val facing: String)

    private const val SCHEMA = 1
    private const val FILE_NAME = "guest-camera-mapping.json"
    // Leave each delimiter available to the next match so adjacent duplicate
    // fields cannot disappear when findAll advances past the previous value.
    private val nameField = Regex("(?:^|\\s)name=([A-Za-z0-9_.-]{1,96})(?=\\s|$)")
    private val facingField = Regex("(?:^|\\s)dir=(back|front|external)(?=\\s|$)")

    fun candidates(factory: String): List<FactoryCamera> {
        val cameras = factory.lineSequence().filter { it.isNotBlank() }.mapNotNull { line ->
            if (!line.contains("name=") && !line.contains("dir=")) return@mapNotNull null
            val fields = line.trim().split(Regex("\\s+"))
            val names = nameField.findAll(line).toList()
            val facings = facingField.findAll(line).toList()
            require(fields.count { it.startsWith("name=") } == 1 && fields.count { it.startsWith("dir=") } == 1 &&
                names.size == 1 && facings.size == 1) { "Malformed actual camera factory entry" }
            FactoryCamera(names.single().groupValues[1], facings.single().groupValues[1])
        }.toList()
        require(cameras.isNotEmpty() && cameras.size <= 32 && cameras.map { it.name }.distinct().size == cameras.size) {
            "The actual camera factory has missing or duplicate transport identities"
        }
        return cameras
    }

    /** Ambiguous facings are usable only after full setup has measured their real frame route. */
    fun resolve(context: Context, factory: String, inventory: JSONArray): Map<String, FactoryCamera> {
        val found = candidates(factory)
        val public = inventoryCameras(inventory)
        val cached = runCatching {
            val file = File(context.filesDir, FILE_NAME)
            check(file.isFile && file.length() in 1..65536)
            val saved = JSONObject(file.readText())
            check(saved.getInt("schema") == SCHEMA && saved.getString("identity") == identity(context, factory, inventory))
            val routes = saved.getJSONArray("cameras")
            val result = linkedMapOf<String, FactoryCamera>()
            for (i in 0 until routes.length()) {
                val item = routes.getJSONObject(i)
                val id = item.getString("cameraId")
                check(id !in result)
                result[id] = FactoryCamera(item.getString("name"), item.getString("facing"))
            }
            validateComplete(found, public, result)
            result.toMap()
        }.getOrNull()
        if (cached != null) return cached

        // Preserve the existing two-camera path without mutating setup state.
        // Activation still verifies the selected media's actual camera pixels.
        return public.mapNotNull { (id, facing) ->
            val matching = found.filter { it.facing == facing }
            if (matching.size == 1 && public.values.count { it == facing } == 1) id to matching.single() else null
        }.toMap()
    }

    /** Called only after every public camera passed timing, source pixel and still-color proof. */
    fun saveMeasured(context: Context, factory: String, inventory: JSONArray, measured: Map<String, FactoryCamera>) {
        validateComplete(candidates(factory), inventoryCameras(inventory), measured)
        val routes = JSONArray()
        measured.toSortedMap().forEach { (id, route) ->
            routes.put(JSONObject().put("cameraId", id).put("name", route.name).put("facing", route.facing))
        }
        val record = JSONObject().put("schema", SCHEMA).put("identity", identity(context, factory, inventory))
            .put("verifiedAt", System.currentTimeMillis()).put("cameras", routes)
        GuestRuntime.writeAtomic(File(context.filesDir, FILE_NAME), record.toString().toByteArray(), 0x180)
    }

    private fun inventoryCameras(inventory: JSONArray): Map<String, String> {
        require(inventory.length() in 1..32) { "No bounded public camera inventory is available" }
        val result = linkedMapOf<String, String>()
        for (i in 0 until inventory.length()) {
            val item = inventory.getJSONObject(i)
            val id = item.getString("id")
            val facing = item.getString("facing")
            require(id.isNotEmpty() && id.length <= 256 && id !in result && facing in setOf("front", "back", "external")) {
                "The public camera inventory has invalid or duplicate identities"
            }
            result[id] = facing
        }
        return result
    }

    private fun validateComplete(factory: List<FactoryCamera>, public: Map<String, String>, measured: Map<String, FactoryCamera>) {
        check(measured.keys == public.keys && measured.values.map { it.name }.distinct().size == measured.size &&
            measured.all { (id, route) -> route in factory && public[id] == route.facing }) {
            "Measured camera routing is incomplete, duplicated or differs from the actual inventory"
        }
    }

    internal fun environmentIdentity(context: Context, factory: String, inventory: JSONArray): String = identity(context,factory,inventory)

    private fun identity(context: Context, factory: String, inventory: JSONArray): String {
        fun assetHash(name: String) = context.assets.open(name).bufferedReader().use { it.readText().trim() }.also {
            check(it.matches(Regex("[a-f0-9]{64}"))) { "Invalid bundled camera identity digest" }
        }
        val cameras = (0 until inventory.length()).map { index ->
            JSONObject(inventory.getJSONObject(index).toString()).apply {
                optJSONArray("resolutions")?.let { sizes ->
                    put("resolutions", JSONArray((0 until sizes.length()).map { sizes.getJSONObject(it) }
                        .sortedWith(compareBy<JSONObject> { it.getInt("width") }.thenBy { it.getInt("height") })))
                }
            }
        }.sortedBy { it.getString("id") }
        val kernel = Os.uname()
        val fields = JSONObject().put("schema", SCHEMA).put("fingerprint", Build.FINGERPRINT)
            .put("sdk", Build.VERSION.SDK_INT).put("hardware", Build.HARDWARE)
            .put("kernelRelease", kernel.release).put("kernelMachine", kernel.machine).put("kernelVersion", kernel.version)
            .put("factory", JSONArray(factory.lineSequence().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }.sorted().toList()))
            .put("inventory", JSONArray(cameras)).put("proxy", assetHash("vmos-camera-proxy.sha256"))
            .put("wrapper", assetHash("camera.vphw90.crop-wrapper.so.sha256"))
            .put("hal", GuestRuntime.sha256(File(GuestRuntime.HAL)))
        File(GuestRuntime.CROP_TRANSPORT).takeIf { it.isFile }?.let { fields.put("transport", GuestRuntime.sha256(it)) }
        return GuestRuntime.digest(canonical(fields).toByteArray())
    }

    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.get(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
}
