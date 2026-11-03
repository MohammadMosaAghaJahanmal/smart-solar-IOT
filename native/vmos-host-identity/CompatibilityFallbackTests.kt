package com.virtualcamerapro.engine

private fun requireThat(value: Boolean, message: String) = check(value) { message }

fun main() {
    var cases = 0
    fun test(name: String, run: () -> Unit) {
        try { run(); cases++ } catch (error: Throwable) { throw AssertionError(name, error) }
    }
    val hd = 1920 to 1080
    val mid = 1280 to 720
    val low = 640 to 480
    val tiny = 320 to 240
    val rear = CompatibilityFallback.Camera("rear_main", "back", listOf(hd, mid, low, tiny))
    val rearAlt = CompatibilityFallback.Camera("rear_wide", "back", listOf(mid, low))
    val front = CompatibilityFallback.Camera("front_selfie", "front", listOf(mid, low, tiny))
    val cameras = listOf(rear, rearAlt, front)
    val requested = OutputSettings(cameraId = rear.id, width = hd.first, height = hd.second,
        loop = false, rotation = 270, scaleMode = "fill", zoom = 2.4, panX = -0.7, panY = 0.3)
    fun plan(value: OutputSettings = requested, all: List<CompatibilityFallback.Camera> = cameras,
             both: List<String> = listOf(rear.id, front.id), cache: OutputSettings? = null, limit: Int = 8) =
        CompatibilityFallback.plan(value, all, both, cache, limit)
    fun shape(candidate: CompatibilityFallback.Candidate) = candidate.output.width to candidate.output.height

    test("requested route stays first; advertised resolution ladder") {
        val choices = plan()
        requireThat(choices.first().output == requested, "Known-good request was changed")
        requireThat(choices.take(4).map(::shape) == listOf(hd, mid, low, tiny), "Expected measured decreasing resolution ladder")
        requireThat(choices.map { it.output.cameraId }.none { it == front.id }, "Switched to the opposite facing")
        requireThat(choices.drop(4).all { it.output.cameraId == rearAlt.id }, "Alternative rear lens not included")
    }
    test("all media transforms survive every fallback") {
        for (choice in plan()) {
            requireThat(choice.output.copy(width = requested.width, height = requested.height, cameraId = requested.cameraId) == requested,
                "Fallback changed media playback or transformation")
        }
    }
    test("no supported advertised size is invented") {
        val awkward = rear.copy(sizes = listOf(1000 to 750, 500 to 376, 250 to 188, 641 to 481, 30 to 30, 4096 to 4096))
        val choices = plan(all = listOf(awkward))
        requireThat(choices.isNotEmpty(), "No advertised alternative selected")
        requireThat(choices.all { shape(it) in listOf(1000 to 750, 500 to 376, 250 to 188) }, "Used invalid or assumed dimensions")
        requireThat(choices.none { shape(it) == low }, "Invented the conventional 640x480 output")
    }
    test("cache reused without replacing new zoom and position") {
        val cache = requested.copy(width = low.first, height = low.second, zoom = 1.0, panX = 0.0, panY = 0.0, rotation = 0)
        val first = plan(cache = cache).first()
        requireThat(first.reason == "verified-cache" && shape(first) == low, "Working configuration not tried first")
        requireThat(first.output.zoom == requested.zoom && first.output.panX == requested.panX && first.output.rotation == requested.rotation,
            "Cache overwrote current media controls")
    }
    test("stale or incompatible caches are rejected") {
        for (cache in listOf(requested.copy(cameraId = "gone"), requested.copy(cameraId = front.id),
            requested.copy(width = 800, height = 600), requested.copy(replacementMode = "both"))) {
            requireThat(plan(cache = cache).first().output == requested, "Invalid cached configuration was trusted")
        }
    }
    test("advertised request never gets a larger retry") {
        val value = requested.copy(width = low.first, height = low.second)
        requireThat(plan(value).all { it.output.width.toLong() * it.output.height <= low.first.toLong() * low.second },
            "Fallback increased output beyond requested buffer area")
        requireThat(plan(value, cache = requested).first().output == value, "Larger cached output bypassed the request limit")
    }
    test("unsupported request uses real substitute including only-larger output") {
        val only = rear.copy(sizes = listOf(hd))
        val smallRequest = requested.copy(width = tiny.first, height = tiny.second)
        requireThat(plan(smallRequest, listOf(only)).single().output == requested, "No supported substitute for unadvertised request")
    }
    test("both replacement keeps all verified camera IDs and common sizes") {
        val value = requested.copy(replacementMode = "both")
        val ids = listOf(rear.id, rearAlt.id, front.id)
        val choices = plan(value, both = ids)
        requireThat(choices.isNotEmpty(), "Common both-camera output missing")
        for (choice in choices) {
            requireThat(choice.output.replacementMode == "both" && choice.cameraIds == ids, "Both mode weakened to fewer cameras")
            requireThat(shape(choice) in listOf(mid, low), "Both selected a non-common output")
            requireThat(choice.output.cameraId == requested.cameraId, "Both fallback changed selected primary ID")
        }
    }
    test("both with no common output fails without a silent single-camera downgrade") {
        val value = requested.copy(replacementMode = "both")
        requireThat(plan(value, all = listOf(rear.copy(sizes = listOf(hd)), front.copy(sizes = listOf(low)))).isEmpty(),
            "No-common-size route silently succeeded")
    }
    test("both missing, repeated or same-facing IDs rejected") {
        val value = requested.copy(replacementMode = "both")
        for (ids in listOf(emptyList(), listOf(rear.id), listOf(rear.id, rearAlt.id),
            listOf(rear.id, "missing"), listOf(rear.id, front.id, front.id)))
            requireThat(plan(value, both = ids).isEmpty(), "Unverified or incomplete both route accepted")
    }
    test("unknown-facing cameras never cause a guessed lens swap") {
        val unknown = rear.copy(facing = "unknown")
        requireThat(plan(all = listOf(unknown, rearAlt, front)).all { it.output.cameraId == rear.id }, "Unknown facing used to guess another lens")
    }
    test("selected empty capability can fall back to measured same-facing lens") {
        val choices = plan(all = listOf(rear.copy(sizes = emptyList()), rearAlt, front))
        requireThat(choices.isNotEmpty() && choices.all { it.output.cameraId == rearAlt.id }, "Empty primary sizes blocked verified alternative lens")
    }
    test("non-numeric IDs and camera capability ordering are deterministic") {
        val forward = plan()
        val reversed = plan(all = cameras.reversed().map { it.copy(sizes = it.sizes.reversed()) })
        requireThat(forward == reversed, "Capability enumeration order changed fallback decisions")
    }
    test("ambiguous, missing and unusable capabilities return no candidates") {
        requireThat(plan(all = cameras + rear.copy(sizes = listOf(low))).isEmpty(), "Duplicate camera identity was guessed")
        requireThat(plan(all = listOf(front)).isEmpty(), "Missing selected lens silently changed facing")
        requireThat(plan(all = listOf(rear.copy(sizes = listOf(63 to 63, 4096 to 4096)))).isEmpty(), "Invalid dimensions accepted")
    }
    test("candidate count bounded and candidate combinations unique") {
        val many = (0..14).map { n -> CompatibilityFallback.Camera("rear_$n", "back",
            (1..18).map { step -> 128 * step to 72 * step }) }
        for (limit in 1..10) {
            val choices = plan(requested.copy(cameraId = "rear_0"), many, limit = limit)
            requireThat(choices.size in 1..limit, "Unbounded retry list")
            requireThat(choices.map { Triple(it.output.cameraId, it.output.width, it.output.height) }.distinct().size == choices.size,
                "Duplicate configuration retry")
        }
        for (limit in listOf(0, 11, Int.MAX_VALUE))
            requireThat(runCatching { plan(limit = limit) }.isFailure, "Invalid retry budget accepted")
    }
    test("only recoverable output failures are retried") {
        for (failure in CompatibilityFallback.Failure.entries) {
            val retryable = failure in listOf(CompatibilityFallback.Failure.CONFIGURATION, CompatibilityFallback.Failure.SESSION,
                CompatibilityFallback.Failure.FRAME_TIMEOUT, CompatibilityFallback.Failure.FRAME_MISMATCH)
            requireThat(CompatibilityFallback.shouldRetry(failure) == retryable, "Incorrect retry for $failure")
        }
    }
    var capabilityChecks = 0
    // Exercise intersections and bounds over many different camera size inventories. Each returned
    // both-mode route must be supported by every target, rather than only by the selected lens.
    val dimensions = listOf(hd, mid, 960 to 540, low, tiny)
    for (rearMask in 0..31) for (frontMask in 0..31) {
        val measuredRear = rear.copy(sizes = dimensions.filterIndexed { i, _ -> rearMask and (1 shl i) != 0 })
        val measuredFront = front.copy(sizes = dimensions.filterIndexed { i, _ -> frontMask and (1 shl i) != 0 })
        val choices = plan(requested.copy(replacementMode = "both"), listOf(measuredRear, measuredFront))
        val common = measuredRear.sizes.toSet().intersect(measuredFront.sizes.toSet())
        requireThat(choices.isEmpty() == common.isEmpty(), "Planner lost or invented a common size")
        requireThat(choices.all { shape(it) in common && it.cameraIds == listOf(rear.id, front.id) }, "Cross-product advertised-size violation")
        capabilityChecks++
    }
    println("PASS $cases camera fallback scenarios and $capabilityChecks measured capability intersection checks")
}
