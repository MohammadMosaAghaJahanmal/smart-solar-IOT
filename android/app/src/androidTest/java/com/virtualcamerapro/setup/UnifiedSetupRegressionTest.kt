package com.virtualcamerapro.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraInventory
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Read-only regression checks after the real single-APK guest preparation has completed. */
@RunWith(AndroidJUnit4::class)
class UnifiedSetupRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun requirePreparedGuest() {
        assumeTrue("Explicitly target the prepared one-APK VMOS guest",
            InstrumentationRegistry.getArguments().getString("unifiedVmos") == "true")
        assertTrue("This integration test requires a validated guest profile", GuestAdapterRegistry.profileMatches())
        assertEquals("com.virtualcamerapro", context.packageName)
        assertNotEquals(0, Process.myUid())
        assertNotEquals(1000, Process.myUid())
    }

    @Test fun integratedBrokerUsesTheSameUidAndItsOwnPrivateProcess() {
        requirePreparedGuest()
        val component = ComponentName(context.packageName, "com.virtualcamerapro.broker.BrokerService")
        val info = context.packageManager.getServiceInfo(component, 0)
        assertFalse("The integrated broker must not expose a public Binder endpoint", info.exported)
        assertEquals(context.applicationInfo.uid, info.applicationInfo.uid)
        assertEquals("${context.packageName}:camera", info.processName)
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IVirtualCameraBroker?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote.set(IVirtualCameraBroker.Stub.asInterface(binder)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        assertTrue(context.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("The private integrated broker did not bind", connected.await(8, TimeUnit.SECONDS))
            val broker = requireNotNull(remote.get())
            val support = JSONObject(broker.support)
            assertEquals(2, support.getInt("protocol"))
            assertTrue("The real prepared guest camera transport is unavailable: $support", support.getBoolean("supported"))
            val status = JSONObject(broker.status)
            for (value in listOf(support, status)) {
                assertEquals(context.packageName, value.getString("brokerPackage"))
                assertEquals(Process.myUid(), value.getInt("processUid"))
                assertTrue(value.getInt("processPid") > 1)
                assertNotEquals("Media decoding must run outside the UI process", Process.myPid(), value.getInt("processPid"))
            }
            File(context.getExternalFilesDir(null), "unified-broker-identity.json").writeText(JSONObject()
                .put("passed", true).put("brokerPackage", context.packageName).put("uid", Process.myUid())
                .put("controllerPid", Process.myPid()).put("brokerPid", status.getInt("processPid"))
                .put("serviceExported", info.exported).put("supported", support.getBoolean("supported")).toString(2))
        } finally { context.unbindService(connection) }
    }

    @Test fun privateSetupBinderRejectsUnapprovedActionsAndKeepsRealVerificationEvidence() {
        requirePreparedGuest()
        UnifiedSetupClient(context).use { client ->
            for (action in listOf("prepare", "restore", "check_root"))
                assertEquals("USER_AUTHORIZATION_REQUIRED", client.run(action, false).getString("actionRejected"))
            val deadline = SystemClock.elapsedRealtime() + 30_000
            var status = client.getStatus()
            while (status.optString("state") in setOf("inspecting", "preparing", "verifying") && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(100)
                status = client.getStatus()
            }
            assertEquals("Preparation and actual calibration must already be complete: $status", "ready", status.getString("state"))
            assertTrue(status.getBoolean("supported"))
            assertTrue(status.getBoolean("ready"))
            assertEquals("integrated_vmos", status.getString("setupBackend"))
            val evidence = status.getJSONObject("verification")
            assertTrue(evidence.getBoolean("verified"))
            val cameras = evidence.getJSONArray("cameras")
            val actual = CameraInventory.query(context)
            val expectedIds = (0 until actual.length()).map { actual.getJSONObject(it).getString("id") }.toSet()
            val verifiedIds = (0 until cameras.length()).map { cameras.getJSONObject(it).getString("cameraId") }.toSet()
            assertEquals("Every actual guest camera needs its own routing and pixel proof", expectedIds, verifiedIds)
            for (index in 0 until cameras.length()) {
                val proof = cameras.getJSONObject(index)
                assertTrue(proof.getBoolean("sourceVerified"))
                assertTrue(proof.getInt("previewFrames") >= 3)
                assertTrue(proof.getLong("lastTimestampNs") > proof.getLong("firstTimestampNs"))
                assertTrue(proof.getInt("jpegBytes") > 0)
                assertTrue(proof.getDouble("bestMeanAbsoluteError") in 0.0..25.0)
                val still = proof.getJSONObject("stillColor")
                assertTrue(still.getInt("previewFramesAtShutter") >= 3)
                assertTrue(still.getLong("lastPreviewTimestampNs") > still.getLong("firstPreviewTimestampNs"))
                assertTrue(still.getDouble("zeroChromaFraction") < 0.90)
                assertTrue(still.getDouble("sourceMeanAbsoluteError") in 0.0..25.0)
                val planes = still.getJSONArray("planes")
                assertEquals(3, planes.length())
                for (plane in 0 until planes.length()) {
                    assertTrue(planes.getJSONObject(plane).getInt("rowStride") > 0)
                    assertTrue(planes.getJSONObject(plane).getInt("pixelStride") > 0)
                }
            }
            val diagnostics = JSONObject(client.exportDiagnostics())
            assertFalse("Diagnostic export must omit selected-media hashes", diagnostics.toString().contains("sourceSha256"))
            File(context.getExternalFilesDir(null), "unified-setup-regression.json").writeText(JSONObject()
                .put("passed", true).put("state", status.getString("state"))
                .put("verifiedCameras", verifiedIds.toList()).put("permissionConsentRejected", true)
                .put("lastVerifiedAt", status.getLong("lastVerifiedAt")).toString(2))
        }
    }
}
