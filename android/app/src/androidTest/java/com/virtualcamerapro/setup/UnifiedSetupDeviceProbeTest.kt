package com.virtualcamerapro.setup

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Read-only status capture: no activity launch, root authorization or setup action.
 * Android 9 instrumentation restarts its target package before this method runs. During a live
 * root prompt/installation use scripts/extract-unified-setup-snapshot.py on the persisted XML.
 */
@RunWith(AndroidJUnit4::class)
class UnifiedSetupDeviceProbeTest {
    @Test fun captureSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UnifiedSetupClient(context).use { client ->
            // Starts only the normal asynchronous health inspection when one is needed.
            // Do not wait for it: inspecting/preparing snapshots are useful live evidence.
            client.getStatus()
            val diagnostic = JSONObject(client.exportDiagnostics())
            val snapshot = diagnostic.getJSONObject("setup").put("deviceProbe", JSONObject()
                .put("capturedAt", System.currentTimeMillis()).put("uid", Process.myUid()).put("pid", Process.myPid()))
            assertTrue(snapshot.has("state"))
            assertFalse("The exported snapshot must not contain private media references", snapshot.toString().contains("content://"))
            assertFalse("The exported snapshot must omit selected-media hashes", snapshot.toString().contains("sourceSha256"))
            val directory = requireNotNull(context.getExternalFilesDir(null)) { "Guest external app storage is unavailable" }
            val output = File(directory, "unified-setup-snapshot.json")
            output.outputStream().use { stream -> stream.write(snapshot.toString(2).toByteArray()); stream.fd.sync() }
            assertTrue(output.isFile && output.length() > 0)
        }
    }
}
