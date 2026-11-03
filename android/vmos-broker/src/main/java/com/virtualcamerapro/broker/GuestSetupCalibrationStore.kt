package com.virtualcamerapro.broker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Successful sizes are hints for another real probe, never cached frame proof. */
internal object GuestSetupCalibrationStore {
    private const val SCHEMA = 1
    private const val FILE_NAME = "guest-setup-calibration.json"
    private const val ATTEMPTS_FILE = "guest-calibration-attempts.json"
    const val MAX_ATTEMPTS_PER_CAMERA = 4

    data class Size(val width: Int, val height: Int) {
        val area get() = width.toLong() * height
        fun json() = JSONObject().put("width", width).put("height", height)
        fun usable() = width > 0 && height > 0 && area <= 8_847_360L
        fun frameUsable() = usable() && width in 32..4096 && height in 32..4096 && width % 2 == 0 && height % 2 == 0
    }
    data class Plan(val frame: Size, val preview: Size, val still: Size, val streamStrategy: String = "combined") {
        fun json() = JSONObject().put("frame", frame.json()).put("privatePreview", preview.json()).put("yuvStill", still.json())
            .put("streamStrategy", streamStrategy)
        fun validStrategy() = streamStrategy in setOf("combined", "sequential")
    }

    fun load(context: Context, identity: String): Map<String, Plan> = runCatching {
        val file = File(context.filesDir, FILE_NAME)
        check(file.isFile && file.length() in 1..131072)
        val record = JSONObject(file.readText())
        check(record.getInt("schema") == SCHEMA && record.getString("identity") == identity)
        val cameras = record.getJSONArray("cameras")
        check(cameras.length() in 1..32)
        val found = linkedMapOf<String, Plan>()
        for (i in 0 until cameras.length()) {
            val item = cameras.getJSONObject(i)
            val id = item.getString("cameraId")
            check(id.isNotEmpty() && id.length <= 256 && id !in found)
            fun size(name: String) = item.getJSONObject(name).let { Size(it.getInt("width"), it.getInt("height")) }
            val plan = Plan(size("frame"), size("privatePreview"), size("yuvStill"), item.optString("streamStrategy", "combined"))
            check(plan.frame.frameUsable() && plan.preview.usable() && plan.still.usable() && plan.validStrategy())
            found[id] = plan
        }
        found.toMap()
    }.getOrDefault(emptyMap())

    /** Only the verifier calls this, after every camera's complete proof succeeds. */
    fun save(context: Context, identity: String, verified: Map<String, Plan>) {
        require(identity.matches(Regex("[a-f0-9]{64}")) && verified.size in 1..32)
        val cameras = JSONArray()
        verified.toSortedMap().forEach { (id, plan) ->
            require(id.isNotEmpty() && id.length <= 256 && plan.frame.frameUsable() && plan.preview.usable() && plan.still.usable() && plan.validStrategy())
            cameras.put(plan.json().put("cameraId", id))
        }
        val record = JSONObject().put("schema", SCHEMA).put("identity", identity)
            .put("verifiedAt", System.currentTimeMillis()).put("cameras", cameras)
        GuestRuntime.writeAtomic(File(context.filesDir, FILE_NAME), record.toString().toByteArray(), 0x180)
    }

    fun recordAttempts(context: Context, identity: String, attempts: JSONArray) {
        require(attempts.length() <= 32 * MAX_ATTEMPTS_PER_CAMERA)
        val record = JSONObject().put("schema", SCHEMA).put("identity", identity)
            .put("updatedAt", System.currentTimeMillis()).put("attempts", attempts)
        GuestRuntime.writeAtomic(File(context.filesDir, ATTEMPTS_FILE), record.toString().toByteArray(), 0x180)
    }

    fun diagnostics(context: Context): JSONObject? = runCatching {
        val file = File(context.filesDir, ATTEMPTS_FILE)
        check(file.isFile && file.length() in 1..262144)
        JSONObject(file.readText()).also { check(it.getInt("schema") == SCHEMA) }
    }.getOrNull()

    /** Every dimension comes from the current Camera2 inventory. No nominal resolution is invented. */
    fun candidates(frames: List<Size>, privateSizes: List<Size>, yuvSizes: List<Size>, cached: Plan?): List<Plan> {
        val common = frames.filter { it.frameUsable() }.distinct().sortedWith(compareByDescending<Size> { it.area }.thenByDescending { it.width })
        val ordering = compareByDescending<Size> { it.area }.thenByDescending { it.width }
        val privateChoices = privateSizes.filter { it.usable() }.distinct().sortedWith(ordering)
        val stills = yuvSizes.filter { it.usable() }.distinct().sortedWith(ordering)
        require(common.isNotEmpty()) { "No actual common YUV/JPEG size is usable within the frame-memory limit" }
        require(privateChoices.isNotEmpty() && stills.isNotEmpty()) { "No actual PRIVATE/YUV still stream pair is advertised" }
        val preferred = common.firstOrNull { it.area <= 1_000_000L } ?: common.last()
        fun preview(still: Size, frame: Size): Size = privateChoices.filter { it.width == still.width && it.height < still.height }
            .maxByOrNull { it.height } ?: privateChoices.minWithOrNull(compareBy<Size> { abs(it.area - frame.area) }.thenBy { it.width })!!
        fun lighter(frame: Size): Plan {
            val still = stills.firstOrNull { it == frame }
                ?: stills.filter { it.area <= frame.area }.maxByOrNull { it.area } ?: stills.minByOrNull { it.area }!!
            return Plan(frame, preview(still, frame), still, "sequential")
        }
        val largestStill = stills.maxByOrNull { it.area }!!
        val all = buildList {
            cached?.takeIf { it.validStrategy() && it.frame in common && it.preview in privateChoices && it.still in stills }?.let { add(it) }
            // Keep the proven Infinix/Samsung stream-transition regression probe first unless a
            // previously verified configuration for this exact environment exists.
            add(Plan(preferred, preview(largestStill, preferred), largestStill))
            add(Plan(preferred, preview(largestStill, preferred), largestStill, "sequential"))
            add(lighter(preferred))
            common.filter { it.area < preferred.area }.forEach { add(lighter(it)) }
        }.distinct()
        // Include the smallest advertised source as the final bounded attempt, rather than
        // exhausting the budget on many almost-identical high-resolution sizes.
        return if (all.size <= MAX_ATTEMPTS_PER_CAMERA) all
            else all.take(MAX_ATTEMPTS_PER_CAMERA - 1) + all.last()
    }
}
