package com.virtualcamerapro.setup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.broker.GuestCameraMapping
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Routing policy uses isolated app files; these checks never open cameras or change guest files. */
@RunWith(AndroidJUnit4::class)
class GuestCameraMappingPolicyTest {
    private lateinit var context: MappingContext

    @Before fun createIsolatedFiles() {
        context = MappingContext(InstrumentationRegistry.getInstrumentation().targetContext)
    }
    @After fun removeIsolatedFiles() { context.dispose() }

    @Test fun factoryNamesRemainOpaqueAndPreserveTheirReportedFacing() {
        val found = GuestCameraMapping.candidates(
            "name=rear.transport-W framedims=640x480 dir=back\n" +
                "name=front_transport.A dir=front framedims=1280x720\n")
        assertEquals(listOf("rear.transport-W", "front_transport.A"), found.map { it.name })
        assertEquals(listOf("back", "front"), found.map { it.facing })
    }

    @Test fun duplicateAndMalformedFactoryEntriesAreRefused() {
        for (factory in listOf(
            "name=duplicate dir=back\nname=duplicate dir=front",
            "name=missing-facing framedims=640x480",
            "dir=back framedims=640x480",
            "name=bad/name dir=back",
            "name=route dir=unknown",
            "name=route dir=back dir=front",
            "name=routeA name=routeB dir=back",
            "name=bad/name name=route dir=back",
            "name=route dir=unknown dir=back",
            "",
            (0..32).joinToString("\n") { "name=transport-$it dir=back" }
        )) expectFailure("Factory must be refused: ${factory.replace("\n", " | ")}") { GuestCameraMapping.candidates(factory) }
    }

    @Test fun uniqueFacingResolutionUsesNonnumericPublicIdsAndNeverListPosition() {
        val factory = "name=front-route dir=front\nname=rear-route dir=back"
        val public = inventory("camera/front:main" to "front", "rear/primary" to "back")
        val expected = mapOf(
            "camera/front:main" to GuestCameraMapping.FactoryCamera("front-route", "front"),
            "rear/primary" to GuestCameraMapping.FactoryCamera("rear-route", "back"))
        assertEquals(expected, GuestCameraMapping.resolve(context, factory, public))
        assertEquals(expected, GuestCameraMapping.resolve(context,
            factory.lineSequence().toList().reversed().joinToString("\n"), reversed(public)))
        assertFalse(File(context.filesDir, "guest-camera-mapping.json").exists())
    }

    @Test fun ambiguousFacingsWithoutMeasuredEvidenceNeverGuessFromIdsOrOrder() {
        val public = inventory("camera_0" to "back", "camera_1" to "front", "camera_2" to "back", "camera_3" to "front")
        assertTrue(GuestCameraMapping.resolve(context, MULTI_FACTORY, public).isEmpty())
        assertTrue(GuestCameraMapping.resolve(context, MULTI_FACTORY, reversed(public)).isEmpty())
        assertFalse(File(context.filesDir, "guest-camera-mapping.json").exists())

        // A uniquely identifiable facing can still be used alongside unresolved auxiliary cameras.
        val partial = GuestCameraMapping.resolve(context,
            "name=rear-A dir=back\nname=rear-B dir=back\nname=selfie dir=front", public)
        assertTrue(partial.isEmpty()) // Two public front IDs cannot share one inferred route.
        val onlyOneFront = inventory("rear-left" to "back", "selfie/main" to "front", "rear-right" to "back")
        assertEquals(mapOf("selfie/main" to GuestCameraMapping.FactoryCamera("selfie", "front")),
            GuestCameraMapping.resolve(context, "name=rear-A dir=back\nname=rear-B dir=back\nname=selfie dir=front", onlyOneFront))
    }

    @Test fun malformedPublicInventoryIsRefusedBeforeAnyMappingIsUsed() {
        val factory = "name=rear dir=back\nname=front dir=front"
        for (public in listOf(
            inventory("same" to "back", "same" to "front"),
            inventory("" to "back"),
            inventory("rear" to "unknown"),
            JSONArray()
        )) expectFailure { GuestCameraMapping.resolve(context, factory, public) }
    }

    @Test fun completeMeasuredMapSurvivesRecreationAndCapabilityOrderingChanges() {
        assumeMeasuredGuest()
        val public = multiInventory()
        val measured = measuredRoutes()
        GuestCameraMapping.saveMeasured(context, MULTI_FACTORY, public, measured)
        assertEquals(measured, GuestCameraMapping.resolve(context, MULTI_FACTORY, public))

        val reordered = reversed(public)
        for (i in 0 until reordered.length()) reordered.getJSONObject(i).let {
            it.put("resolutions", reversed(it.getJSONArray("resolutions")))
        }
        val recreated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = context.filesDir
        }
        assertEquals(measured, GuestCameraMapping.resolve(recreated,
            MULTI_FACTORY.lineSequence().toList().reversed().joinToString("\n"), reordered))
    }

    @Test fun incompleteDuplicateOrWrongFacingMeasuredRoutesCannotBeSaved() {
        val public = multiInventory()
        val measured = measuredRoutes()
        expectFailure { GuestCameraMapping.saveMeasured(context, MULTI_FACTORY, public, measured - "rear/tele") }
        expectFailure {
            GuestCameraMapping.saveMeasured(context, MULTI_FACTORY, public,
                measured + ("rear/tele" to measured.getValue("rear/main")))
        }
        expectFailure {
            GuestCameraMapping.saveMeasured(context, MULTI_FACTORY, public,
                measured + ("rear/tele" to GuestCameraMapping.FactoryCamera("not-an-actual-route", "back")))
        }
        expectFailure {
            GuestCameraMapping.saveMeasured(context, MULTI_FACTORY, public,
                measured + ("rear/tele" to GuestCameraMapping.FactoryCamera("front-B", "front")))
        }
        assertFalse(File(context.filesDir, "guest-camera-mapping.json").exists())
    }

    @Test fun changedCapabilitiesFactoryOrCorruptedRouteInvalidateMeasuredEvidence() {
        assumeMeasuredGuest()
        val public = multiInventory()
        val measured = measuredRoutes()
        GuestCameraMapping.saveMeasured(context, MULTI_FACTORY, public, measured)

        val changed = JSONArray(public.toString())
        changed.getJSONObject(0).put("sensorOrientation", 270)
        assertTrue(GuestCameraMapping.resolve(context, MULTI_FACTORY, changed).isEmpty())
        assertTrue(GuestCameraMapping.resolve(context, MULTI_FACTORY.replace("framedims=640x480", "framedims=1280x720"), public).isEmpty())
        assertEquals(measured, GuestCameraMapping.resolve(context, MULTI_FACTORY, public))

        val record = File(context.filesDir, "guest-camera-mapping.json")
        val corrupt = JSONObject(record.readText())
        corrupt.getJSONArray("cameras").getJSONObject(0).put("name", "unmeasured-route")
        record.writeText(corrupt.toString())
        assertTrue(GuestCameraMapping.resolve(context, MULTI_FACTORY, public).isEmpty())
        record.writeText("{interrupted-write")
        assertTrue(GuestCameraMapping.resolve(context, MULTI_FACTORY, public).isEmpty())
    }

    private fun assumeMeasuredGuest() {
        assumeTrue("Measured cache identity needs the actual supported guest's readable HAL and bundled bridge",
            GuestAdapterRegistry.profileMatches() && runCatching {
                GuestAdapterRegistry.requireSupportedGuest()
                GuestAdapterRegistry.sha256(File(GuestAdapterRegistry.HAL)) in GuestAdapterRegistry.knownHalHashes
            }.getOrDefault(false))
    }

    private fun inventory(vararg cameras: Pair<String, String>) = JSONArray().apply {
        cameras.forEach { (id, facing) -> put(JSONObject().put("id", id).put("facing", facing)
            .put("sensorOrientation", 90).put("hardwareLevel", 0).put("maxDigitalZoom", 4.0)
            .put("resolutions", JSONArray().put(JSONObject().put("width", 640).put("height", 480))
                .put(JSONObject().put("width", 1280).put("height", 720)))) }
    }
    private fun multiInventory() = inventory("rear/main" to "back", "selfie/wide" to "front", "rear/tele" to "back", "selfie/main" to "front")
    private fun measuredRoutes() = mapOf(
        "rear/main" to GuestCameraMapping.FactoryCamera("rear-B", "back"),
        "selfie/wide" to GuestCameraMapping.FactoryCamera("front-A", "front"),
        "rear/tele" to GuestCameraMapping.FactoryCamera("rear-A", "back"),
        "selfie/main" to GuestCameraMapping.FactoryCamera("front-B", "front"))
    private fun reversed(array: JSONArray) = JSONArray((0 until array.length()).reversed().map { array.getJSONObject(it) })
    private fun expectFailure(detail: String = "Invalid or ambiguous routing must be refused", block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true } catch (_: IllegalStateException) { rejected = true }
        assertTrue(detail, rejected)
    }

    private class MappingContext(actual: Context) : ContextWrapper(actual) {
        private val directory = File(actual.cacheDir, "camera-mapping-policy-${UUID.randomUUID()}").apply { check(mkdir()) }
        override fun getFilesDir(): File = directory
        fun dispose() { check(directory.deleteRecursively()) }
    }
    companion object {
        private const val MULTI_FACTORY = "name=front-B framedims=640x480 dir=front\n" +
            "name=rear-A framedims=640x480 dir=back\nname=front-A framedims=640x480 dir=front\n" +
            "name=rear-B framedims=640x480 dir=back"
    }
}
