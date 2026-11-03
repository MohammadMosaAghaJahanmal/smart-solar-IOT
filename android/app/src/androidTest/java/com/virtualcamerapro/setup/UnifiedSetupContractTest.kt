package com.virtualcamerapro.setup

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeFalse
import org.json.JSONArray
import org.json.JSONObject
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import java.io.File

/** Inspection and authorization tests never approve a root command or alter guest camera files. */
@RunWith(AndroidJUnit4::class)
class UnifiedSetupContractTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun privilegedActionsRejectMissingConsentBeforeStartingAWorker() {
        var foregroundStarts = 0
        val orchestrator = SetupOrchestrator(context) { if (it) foregroundStarts++ }
        try {
            for (action in listOf("prepare", "restore", "check_root")) {
                val result = orchestrator.execute(action, false)
                assertEquals("USER_AUTHORIZATION_REQUIRED", result.getString("actionRejected"))
                assertFalse(result.getBoolean("ready"))
            }
            assertEquals(0, foregroundStarts)
        } finally { orchestrator.close() }
    }

    @Test fun inspectionDoesNotInventHostIdentityOrAssumeNumericCameraIds() {
        val environment = EnvironmentDetector(context).inspect()
        val host = environment.getJSONObject("host")
        assertFalse(host.getBoolean("known"))
        assertTrue(host.isNull("apiLevel"))
        assertTrue(host.isNull("vmosVersion"))
        val manager = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val actualIds = manager.cameraIdList.toSet()
        val reported = environment.getJSONArray("cameras")
        assertEquals(actualIds.size, reported.length())
        for (index in 0 until reported.length()) assertTrue(reported.getJSONObject(index).getString("id") in actualIds)
        assertEquals("requires_frame_verification", environment.getJSONObject("yuvLayout").getString("state"))
    }

    @Test fun externalInspectionDoesNotPrepareTheVmosRuntime() {
        assumeFalse(GuestAdapterRegistry.profileMatches())
        val guestFiles = listOf("camera-runtime", "vmos-camera-proxy", "guest-installation.json", "guest-provisioning")
        fun state() = guestFiles.associateWith { name ->
            val file = File(context.filesDir, name)
            Triple(file.exists(), file.length(), file.lastModified())
        }
        val before = state()
        ExternalBackendInspector(context).inspect()
        assertEquals("An external backend inspection must not install or configure the VMOS runtime", before, state())
    }

    @Test fun sharedDiagnosticsRedactMediaReferencesInsideErrorsAndNestedFields() {
        val input = JSONObject().put("state", "error").put("cameraId", "back-main")
            .put("sourcePath", "/sdcard/private-photo.jpg")
            .put("components", JSONArray().put(JSONObject()
                .put("accessToken", "account-secret")
                .put("error", "Cannot load FILE:///sdcard/private-photo.jpg or content://media/secret/42; " +
                    "retry ${context.filesDir.absolutePath}/source-private.media")))
        val redacted = DiagnosticRedactor(context.filesDir.absolutePath).sanitize(input) as JSONObject
        assertEquals("error", redacted.getString("state"))
        assertEquals("back-main", redacted.getString("cameraId"))
        val text = redacted.toString()
        for (privateValue in listOf("private-photo", "account-secret", "secret/42", "source-private", context.filesDir.absolutePath))
            assertFalse("Export contained $privateValue", text.contains(privateValue))
        assertFalse(redacted.has("sourcePath"))
        assertFalse(redacted.getJSONArray("components").getJSONObject(0).has("accessToken"))
        assertTrue(text.contains("<media-reference>"))
    }
}
