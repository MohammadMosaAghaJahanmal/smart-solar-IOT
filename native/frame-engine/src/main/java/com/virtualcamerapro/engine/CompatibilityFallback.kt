package com.virtualcamerapro.engine

import kotlin.math.abs
import kotlin.math.ln

/**
 * A bounded camera-output search over measured capabilities, with no Android dependencies.
 *
 * Sizes must already be the formats the bridge and the frame verifier can both deliver (currently
 * their advertised YUV/JPEG intersection). This planner never invents a format, size or camera ID.
 * A cached working output is a hint scoped by the caller to the same environment and user request;
 * its media transforms are deliberately ignored so a new zoom or position is never overwritten.
 */
object CompatibilityFallback {
    data class Camera(val id: String, val facing: String, val sizes: List<Pair<Int, Int>>)
    data class Candidate(val output: OutputSettings, val cameraIds: List<String>, val reason: String)

    enum class Failure {
        CONFIGURATION, SESSION, FRAME_TIMEOUT, FRAME_MISMATCH,
        PERMISSION, ROOT, UNTRUSTED_ADAPTER, CANCELLED, DEADLINE_EXCEEDED
    }

    /** A timeout of one frame attempt is recoverable; exhaustion of the overall budget is not. */
    fun shouldRetry(failure: Failure): Boolean = failure in setOf(
        Failure.CONFIGURATION, Failure.SESSION, Failure.FRAME_TIMEOUT, Failure.FRAME_MISMATCH
    )

    fun plan(
        requested: OutputSettings,
        cameras: List<Camera>,
        bothIds: List<String>,
        cachedWorking: OutputSettings? = null,
        maxAttempts: Int = 8
    ): List<Candidate> {
        require(maxAttempts in 1..10) { "Camera fallback attempts must be bounded between 1 and 10" }
        // Duplicate IDs are ambiguous evidence. Do not silently merge or choose one of them.
        val unique = cameras.groupBy { it.id }.filterValues { it.size == 1 }
            .mapValues { it.value.single() }.filterKeys { validId(it) }
        val selected = unique[requested.cameraId] ?: return emptyList()
        val result = mutableListOf<Candidate>()
        val visited = mutableSetOf<Triple<String, Int, Int>>()
        val requestedArea = requested.width.toLong() * requested.height
        val both = requested.replacementMode == "both"
        val routes: List<Camera>
        val common: List<Pair<Int, Int>>
        val targets: List<String>
        if (both) {
            targets = bothIds.distinct()
            if (targets.size != bothIds.size || selected.id !in targets || targets.any { it !in unique }) return emptyList()
            val selectedCameras = targets.map { unique.getValue(it) }
            if (selectedCameras.none { it.facing == "front" } || selectedCameras.none { it.facing == "back" }) return emptyList()
            common = selectedCameras.map { validSizes(it.sizes).toSet() }.reduce { a, b -> a.intersect(b) }.toList()
            routes = listOf(selected)
        } else {
            targets = listOf(selected.id)
            common = emptyList()
            // An unknown-facing camera can use its own resolutions, but cannot justify a lens swap.
            val alternatives = if (selected.facing in listOf("front", "back")) unique.values
                .filter { it.id != selected.id && it.facing == selected.facing }
                .sortedBy { it.id } else emptyList()
            routes = listOf(selected) + alternatives
        }
        fun sizes(camera: Camera) = if (both) common else validSizes(camera.sizes)
        fun add(camera: Camera, size: Pair<Int, Int>, reason: String) {
            if (result.size >= maxAttempts || !visited.add(Triple(camera.id, size.first, size.second))) return
            result += Candidate(requested.copy(cameraId = camera.id, width = size.first, height = size.second),
                if (both) targets else listOf(camera.id), reason)
        }
        fun route(cameraId: String) = routes.firstOrNull { it.id == cameraId }
        val selectedSizes = sizes(selected)
        if (routes.all { sizes(it).isEmpty() }) return emptyList()
        val requestedSize = requested.width to requested.height
        val requestedAdvertised = requestedSize in selectedSizes

        // A working cached smaller output avoids retrying the same known-bad request every launch.
        // When the request is advertised, a cache may never force an unrequested resolution upscale.
        cachedWorking?.takeIf { it.replacementMode == requested.replacementMode }?.let { cached ->
            val camera = route(cached.cameraId)
            val size = cached.width to cached.height
            if (camera != null && size in sizes(camera) && (!requestedAdvertised || area(size) <= requestedArea))
                add(camera, size, "verified-cache")
        }
        if (requestedAdvertised) add(selected, requestedSize, "requested")

        val ranked = routes.associateWith { camera ->
            val advertised = sizes(camera)
            // A non-advertised request needs a real substitute; only then may the nearest valid
            // initial choice exceed the request. Later attempts still move down from that ceiling.
            val initial = advertised.minWithOrNull(sizeDistance(requested))
            val ceiling = if (requestedAdvertised) requestedArea else maxOf(requestedArea, initial?.let(::area) ?: 0)
            descendingChoices(advertised.filter { area(it) <= ceiling }, requested)
        }
        // Four decreasing sizes cover the requested route before trying another lens of the same
        // facing. Reserve bounded room for those lens trials instead of spending it on every size.
        val primary = ranked.getValue(selected)
        primary.take(if (routes.size > 1) 4 else maxAttempts).forEach { add(selected, it, "smaller-supported-output") }
        for (camera in routes.drop(1)) {
            ranked.getValue(camera).take(2).forEach { add(camera, it, "same-facing-lens") }
        }
        primary.forEach { add(selected, it, "smaller-supported-output") }
        for (camera in routes.drop(1)) ranked.getValue(camera).forEach { add(camera, it, "same-facing-lens") }
        return result
    }

    private fun validId(id: String) = id.matches(Regex("[A-Za-z0-9_]{1,32}")) && !id.startsWith("v0_")
    private fun area(size: Pair<Int, Int>) = size.first.toLong() * size.second
    private fun validSizes(sizes: List<Pair<Int, Int>>) = sizes.distinct().filter { (w, h) ->
        w in 32..4096 && h in 32..4096 && w % 2 == 0 && h % 2 == 0 && area(w to h) <= 8_847_360
    }
    private fun aspectDistance(size: Pair<Int, Int>, requested: OutputSettings) =
        abs(ln((size.first.toDouble() / size.second) / (requested.width.toDouble() / requested.height)))
    private fun sizeDistance(requested: OutputSettings) = compareBy<Pair<Int, Int>> {
        aspectDistance(it, requested)
    }.thenBy { abs(area(it) - requested.width.toLong() * requested.height) }
        .thenBy { it.first }.thenBy { it.second }

    private fun descendingChoices(sizes: List<Pair<Int, Int>>, requested: OutputSettings): List<Pair<Int, Int>> {
        if (sizes.isEmpty()) return emptyList()
        val result = mutableListOf<Pair<Int, Int>>()
        // These are area ceilings, never assumed supported resolutions. Preserve the source aspect
        // wherever possible while progressively lowering buffer memory and bandwidth requirements.
        for (ceiling in listOf(requested.width.toLong() * requested.height, 1280L * 720, 640L * 480, 320L * 240)) {
            val best = sizes.filter { area(it) <= ceiling && it !in result }
                .minWithOrNull(compareBy<Pair<Int, Int>> { aspectDistance(it, requested) }
                    .thenByDescending { area(it) }.thenBy { it.first }.thenBy { it.second })
            if (best != null) result += best
        }
        result += sizes.filter { it !in result }.sortedWith(compareByDescending<Pair<Int, Int>> { area(it) }
            .thenBy { aspectDistance(it, requested) }.thenBy { it.first }.thenBy { it.second })
        return result
    }
}
