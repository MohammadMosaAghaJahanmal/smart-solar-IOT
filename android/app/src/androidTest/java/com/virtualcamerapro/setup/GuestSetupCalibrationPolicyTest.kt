package com.virtualcamerapro.setup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.broker.GuestSetupCalibrationStore
import com.virtualcamerapro.broker.GuestSetupCalibrationStore.Plan
import com.virtualcamerapro.broker.GuestSetupCalibrationStore.Size
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Isolated planning/cache policy. Camera output is independently tested by device frame tests. */
@RunWith(AndroidJUnit4::class)
class GuestSetupCalibrationPolicyTest {
    private lateinit var context: CalibrationContext
    @Before fun createIsolatedFiles() {
        context = CalibrationContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @After fun removeIsolatedFiles() { context.dispose() }

    @Test fun preferredPlanKeepsActualFullStillColorTransitionAndAddsSeparateHalJpegFallback() {
        val frames = listOf(Size(640,480), Size(1280,720), Size(1920,1080))
        val previews = listOf(Size(640,480), Size(1280,720), Size(1920,720))
        val plans = GuestSetupCalibrationStore.candidates(frames, previews, frames, null)
        assertEquals(Plan(Size(1280,720), Size(1920,720), Size(1920,1080)), plans.first())
        assertEquals(plans.first().copy(streamStrategy="sequential"), plans[1])
        assertTrue(plans.any { it.still.area < plans.first().still.area })
        assertTrue(plans.any { it.frame == Size(640,480) })
    }

    @Test fun boundedRetriesUseOnlyAdvertisedDimensionsAndReachSmallestRatherThanAdjacentLargeSizes() {
        val frames = listOf(Size(1280,720), Size(1024,768), Size(960,720), Size(800,600), Size(640,480), Size(320,240))
        val previews = frames + Size(1920,720)
        val yuv = frames + Size(1920,1080)
        val plans = GuestSetupCalibrationStore.candidates(frames, previews, yuv, null)
        assertEquals(GuestSetupCalibrationStore.MAX_ATTEMPTS_PER_CAMERA, plans.size)
        assertEquals(Size(320,240), plans.last().frame)
        assertEquals(plans.size, plans.distinct().size)
        plans.forEach {
            assertTrue(it.frame in frames)
            assertTrue(it.preview in previews)
            assertTrue(it.still in yuv)
            assertTrue(it.streamStrategy in setOf("combined", "sequential"))
        }
    }

    @Test fun cachedVerifiedPlanRunsFirstOnlyWhileEveryDimensionAndStrategyRemainSupported() {
        val frames = listOf(Size(640,480), Size(1280,720))
        val cached = Plan(frames.first(), frames.first(), frames.first(), "sequential")
        assertEquals(cached, GuestSetupCalibrationStore.candidates(frames, frames, frames, cached).first())
        val missingPreview = cached.copy(preview=Size(800,600))
        assertFalse(missingPreview in GuestSetupCalibrationStore.candidates(frames, frames, frames, missingPreview))
        val invalidStrategy = cached.copy(streamStrategy="pretend-jpeg")
        assertFalse(invalidStrategy in GuestSetupCalibrationStore.candidates(frames, frames, frames, invalidStrategy))
        val missingFrame = cached.copy(frame=Size(320,240))
        assertFalse(missingFrame in GuestSetupCalibrationStore.candidates(frames, frames, frames, missingFrame))
    }

    @Test fun reorderedAndInvalidSizesDoNotInventCapabilitiesOrChangePreferredPlan() {
        val frames = listOf(Size(640,480), Size(1280,720), Size(1920,1080))
        val invalid = listOf(Size(-640,480), Size(0,480), Size(8192,8192))
        val first = GuestSetupCalibrationStore.candidates(frames + invalid, frames + invalid, frames + invalid, null)
        val second = GuestSetupCalibrationStore.candidates((frames + invalid).reversed(), frames.reversed(), frames.reversed(), null)
        assertEquals(first, second)
        for (choices in listOf(emptyList(), listOf(Size(639,479)), invalid))
            expectFailure { GuestSetupCalibrationStore.candidates(choices, frames, frames, null) }
        expectFailure { GuestSetupCalibrationStore.candidates(frames, emptyList(), frames, null) }
        expectFailure { GuestSetupCalibrationStore.candidates(frames, frames, emptyList(), null) }
    }

    @Test fun verifiedCachePersistsOpaqueIdsButInvalidatesOnExactEnvironmentChange() {
        val plan = Plan(Size(640,480), Size(640,480), Size(1280,720), "sequential")
        val expected = mapOf("rear/primary" to plan, "camera.front:wide" to plan.copy(streamStrategy="combined"))
        GuestSetupCalibrationStore.save(context, IDENTITY, expected)
        val recreated = object : ContextWrapper(context) { override fun getFilesDir() = context.filesDir }
        assertEquals(expected, GuestSetupCalibrationStore.load(recreated, IDENTITY))
        assertTrue(GuestSetupCalibrationStore.load(recreated, OTHER_IDENTITY).isEmpty())
        assertEquals(expected, GuestSetupCalibrationStore.load(recreated, IDENTITY))
    }

    @Test fun corruptInterruptedOversizedOrUnknownStrategyCacheFailsClosed() {
        val plan = Plan(Size(640,480), Size(640,480), Size(640,480))
        GuestSetupCalibrationStore.save(context, IDENTITY, mapOf("id" to plan))
        val file = File(context.filesDir, "guest-setup-calibration.json")
        val original = file.readText()
        file.writeText("{interrupted")
        assertTrue(GuestSetupCalibrationStore.load(context, IDENTITY).isEmpty())
        val unknown = JSONObject(original)
        unknown.getJSONArray("cameras").getJSONObject(0).put("streamStrategy", "unverified-format")
        file.writeText(unknown.toString())
        assertTrue(GuestSetupCalibrationStore.load(context, IDENTITY).isEmpty())
        file.writeText("x".repeat(131073))
        assertTrue(GuestSetupCalibrationStore.load(context, IDENTITY).isEmpty())
        file.writeText(original)
        assertEquals(plan, GuestSetupCalibrationStore.load(context, IDENTITY)["id"])
    }

    @Test fun failedAttemptDiagnosticsNeverReplaceVerifiedConfiguration() {
        val plan = Plan(Size(640,480), Size(640,480), Size(640,480))
        GuestSetupCalibrationStore.save(context, IDENTITY, mapOf("camera" to plan))
        val attempts = JSONArray().put(plan.json().put("cameraId", "camera").put("verified", false)
            .put("error", "Actual camera rejected concurrent streams"))
        GuestSetupCalibrationStore.recordAttempts(context, IDENTITY, attempts)
        assertEquals(plan, GuestSetupCalibrationStore.load(context, IDENTITY)["camera"])
        val diagnostics = requireNotNull(GuestSetupCalibrationStore.diagnostics(context))
        assertFalse(diagnostics.getJSONArray("attempts").getJSONObject(0).getBoolean("verified"))
        expectFailure {
            GuestSetupCalibrationStore.recordAttempts(context, IDENTITY,
                JSONArray((0..32 * GuestSetupCalibrationStore.MAX_ATTEMPTS_PER_CAMERA).map { JSONObject() }))
        }
    }

    private fun expectFailure(block: () -> Unit) {
        var refused = false
        try { block() } catch (_: IllegalArgumentException) { refused = true }
        assertTrue("Unsupported dimensions, missing formats or unbounded attempts must be refused", refused)
    }
    private class CalibrationContext(actual: Context) : ContextWrapper(actual) {
        private val directory = File(actual.cacheDir, "setup-calibration-policy-${UUID.randomUUID()}").apply { check(mkdir()) }
        override fun getFilesDir() = directory
        fun dispose() { check(directory.deleteRecursively()) }
    }
    companion object {
        private val IDENTITY = "1".repeat(64)
        private val OTHER_IDENTITY = "2".repeat(64)
    }
}
