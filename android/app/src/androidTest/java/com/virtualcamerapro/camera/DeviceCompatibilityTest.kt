package com.virtualcamerapro.camera

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DeviceCompatibilityTest {
    @Test fun installCompatibilityDoesNotClaimSystemCameraSupport() {
        for (sdk in 28..36) {
            val device = DeviceCompatibility(sdk, if (sdk == 29) "10" else "$sdk", "Vendor", "Phone", listOf("arm64-v8a"))
            assertTrue("The controller targets API 28 and newer", device.controllerInstallCompatible)
            assertFalse("API compatibility is not a system camera route", device.missingBroker().getBoolean("supported"))
        }
        assertFalse(DeviceCompatibility(27, "8.1", "Vendor", "Phone", listOf("arm64-v8a")).controllerInstallCompatible)
        assertFalse(DeviceCompatibility(29, "10", "Vendor", "Phone", listOf("armeabi-v7a")).controllerInstallCompatible)
        val phone = DeviceCompatibility(29, "10", "Infinix", "X687B", listOf("arm64-v8a", "armeabi-v7a")).missingBroker()
        assertEquals("BROKER_MISSING", phone.getString("code"))
        assertTrue(phone.getString("recovery").contains("requires Android 16 (API 36)"))
        assertFalse("Do not suggest flashing the x86 reference image onto a phone", phone.getString("recovery").contains("vcam_cf_x86_64_phone"))
    }

    @Test fun reportsActualHostAndNeverActivatesAnAbsentBroker() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val device = DeviceCompatibility.current()
        assertEquals(Build.VERSION.SDK_INT, device.diagnostics().getInt("androidSdk"))
        assertEquals(Build.MODEL, device.diagnostics().getString("model"))
        val report = VirtualCameraController(context).use { controller ->
            val support = controller.support()
            assertEquals(Build.VERSION.SDK_INT, support.getJSONObject("device").getInt("androidSdk"))
            assertEquals(Build.MODEL, support.getJSONObject("device").getString("model"))
            assertEquals(2, support.getJSONArray("backendSupport").length())
            if (context.packageManager.resolveService(android.content.Intent().setComponent(android.content.ComponentName(
                    "com.virtualcamerapro.broker", "com.virtualcamerapro.broker.BrokerService")), 0) == null) {
                assertFalse(support.getBoolean("supported"))
                assertEquals("BROKER_MISSING", support.getString("code"))
                val status = controller.status()
                assertNotEquals("active", status.getString("phase"))
                assertEquals("BROKER_MISSING", status.getString("code"))
                assertEquals(support.getString("message"), status.getString("message"))
            }
            JSONObject().put("device", device.diagnostics()).put("support", support)
        }
        File(context.getExternalFilesDir(null), "device-compatibility.json").writeText(report.toString(2))
    }
}
