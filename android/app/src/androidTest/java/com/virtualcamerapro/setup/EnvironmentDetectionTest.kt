package com.virtualcamerapro.setup

import android.os.Build
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.camera.DeviceCompatibility
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnvironmentDetectionTest {
    @Test fun cameraMetadataAndKernelSignalsDoNotClaimPhysicalHostValidation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = EnvironmentDetector(context).inspect()
        assertFalse(report.getJSONObject("host").getBoolean("known"))
        assertTrue(report.getJSONObject("host").isNull("apiLevel"))
        val execution = report.getJSONObject("executionEnvironment")
        assertEquals(Process.is64Bit(), execution.getBoolean("process64Bit"))
        assertTrue(execution.getString("kernelIdentityScope").contains("does_not_authenticate"))
        assertEquals(Build.VERSION.SDK_INT, report.getJSONObject("guest").getInt("sdk"))
        val service = report.getJSONObject("cameraService")
        assertEquals("not_performed_by_metadata_inspection", service.getString("openVerification"))
        val cameras = report.getJSONArray("cameras")
        if (service.getBoolean("enumerated")) {
            assertEquals(cameras.length(), service.getInt("reportedCameraCount"))
            for (index in 0 until cameras.length()) {
                val camera = cameras.getJSONObject(index)
                assertEquals(camera.getBoolean("accessible"), camera.getBoolean("metadataAccessible"))
                if (camera.getBoolean("metadataAccessible"))
                    assertEquals("requires_actual_frame_probe", camera.getString("openVerification"))
                else assertTrue(camera.has("error"))
            }
        } else {
            assertEquals(0, cameras.length())
            assertTrue(service.has("errorCode"))
        }
        val appOp = report.getJSONObject("permissions").getJSONObject("cameraAppOp")
        if (appOp.getBoolean("known")) assertFalse(appOp.getBoolean("isCameraOpenProof"))
    }

    @Test fun declaredBackendEvidenceSeparatesVmosFromStockFirmware() {
        val evidence = DeviceCompatibility.current().backendSupport()
        val guest = evidence.getJSONObject(0)
        assertEquals("integrated_vmos", guest.getString("backend"))
        assertFalse(guest.getBoolean("otherPhysicalHostsVerified"))
        assertFalse(guest.getBoolean("directStockCameraReplacement"))
        assertTrue(guest.getBoolean("requiresMeasuredGuestProfile"))
        assertTrue(guest.getBoolean("requiresLiveFrameVerification"))
        for (sdk in 29..36) {
            val descriptor = DeviceCompatibility(sdk, "$sdk", "Any", "Phone", listOf("arm64-v8a"))
            assertTrue(descriptor.controllerInstallCompatible)
            assertFalse(descriptor.missingBroker().getBoolean("supported"))
        }
    }
}
