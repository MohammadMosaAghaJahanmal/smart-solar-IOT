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
import com.virtualcamerapro.broker.GuestCameraMapping
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraInventory
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in: removes only this app's measured routing cache while the media feed is off. */
@RunWith(AndroidJUnit4::class)
class UnifiedMappingRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun missingPrivateMappingIsRecalibratedWithoutRepeatingRootAuthorization() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicitly enable real private-mapping recovery inside the prepared guest",
            args.getString("unifiedVmos") == "true" && args.getString("unifiedMappingRecovery") == "true")
        assertTrue(GuestAdapterRegistry.profileMatches())
        assertEquals("com.virtualcamerapro", context.packageName)
        assertEquals(context.applicationInfo.uid, Process.myUid())
        assertTrue("Recovery must be performed by the ordinary application UID", Process.myUid() >= 10000)
        val inventory = CameraInventory.query(context)
        val facings = (0 until inventory.length()).map { inventory.getJSONObject(it).getString("facing") }
        assumeTrue("This recovery case needs ambiguous same-facing cameras that require measured routing",
            facings.distinct().any { facing -> facings.count { it == facing } > 1 })
        val report = JSONObject().put("passed", false).put("controllerUid", Process.myUid())
        val mapping = File(context.filesDir, "guest-camera-mapping.json")
        val backup = File(context.filesDir, ".guest-camera-mapping-recovery-${UUID.randomUUID()}.bak")
        UnifiedSetupClient(context).use { setup ->
            BoundBroker(context).use { bound ->
                try {
                    val initialDeadline = SystemClock.elapsedRealtime() + 30_000
                    var before = setup.getStatus()
                    while (before.optString("state") in setOf("inspecting", "preparing", "verifying") &&
                        SystemClock.elapsedRealtime() < initialDeadline) { Thread.sleep(150); before = setup.getStatus() }
                    assertTrue("Actual integrated setup must be Ready before the controlled loss: $before", before.optBoolean("ready"))
                    assertEquals("integrated_vmos", before.getString("setupBackend"))
                    assertTrue("Enable automatic recovery before opting into this test", before.getJSONObject("preferences").getBoolean("autoRecover"))
                    val brokerBefore = JSONObject(bound.service.status)
                    assertTrue("Stop selected media before this test; it must not interrupt an active feed: $brokerBefore",
                        brokerBefore.optString("phase") !in setOf("active", "activating"))
                    val verifiedBefore = before.getLong("lastVerifiedAt")
                    val rootBefore = rootCheckCompletedAt(before)
                    assertTrue(verifiedBefore > 0)
                    report.put("lastVerifiedAtBefore", verifiedBefore).put("rootAuthorizationCompletedAtBefore", rootBefore)
                        .put("brokerPhaseBefore", brokerBefore.getString("phase"))
                    if (mapping.exists()) {
                        assertTrue("The existing mapping must be a regular file", mapping.isFile)
                        assertTrue("Preserve the current private mapping before the controlled loss", mapping.renameTo(backup))
                        report.put("previousMappingPreserved", true)
                    } else report.put("previousMappingPreserved", false)
                    assertFalse(mapping.exists())

                    setup.run("inspect", false)
                    val deadline = SystemClock.elapsedRealtime() + 90_000
                    var after = setup.getStatus()
                    while (!(after.optBoolean("ready") && after.optLong("lastVerifiedAt") > verifiedBefore && mapping.isFile) &&
                        after.optString("state") != "error" && SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(150); after = setup.getStatus()
                    }
                    report.put("lastSnapshot", after)
                    assertTrue("Missing measured routing must trigger real automatic calibration: $after", after.optBoolean("ready"))
                    assertTrue("A saved Ready snapshot is insufficient; fresh verification is required", after.getLong("lastVerifiedAt") > verifiedBefore)
                    assertTrue("Recovery must publish a new complete private mapping", mapping.isFile)
                    assertTrue(after.getJSONObject("components").getJSONObject("bridge").getBoolean("mappingComplete"))
                    assertEquals("Private routing repair must not repeat the root authorization command", rootBefore, rootCheckCompletedAt(after))
                    val current = CameraInventory.query(context)
                    val currentIds = (0 until current.length()).map { current.getJSONObject(it).getString("id") }.toSet()
                    val evidence = after.getJSONObject("verification")
                    assertTrue(evidence.getBoolean("verified"))
                    val proofs = evidence.getJSONArray("cameras")
                    assertEquals(currentIds.size, proofs.length())
                    assertEquals(currentIds, (0 until proofs.length()).map { proofs.getJSONObject(it).getString("cameraId") }.toSet())
                    val factory = after.getJSONObject("components").getJSONObject("bridge").getJSONObject("transport").getString("cameraFactory")
                    val recovered = GuestCameraMapping.resolve(context, factory, current)
                    assertEquals(currentIds, recovered.keys)
                    for (index in 0 until proofs.length()) {
                        val proof = proofs.getJSONObject(index)
                        assertTrue(proof.getBoolean("sourceVerified"))
                        assertTrue(proof.getInt("previewFrames") >= 3)
                        assertTrue(proof.getLong("lastTimestampNs") > proof.getLong("firstTimestampNs"))
                        assertTrue(proof.getDouble("bestMeanAbsoluteError") in 0.0..25.0)
                        assertTrue(proof.getJSONObject("stillColor").getDouble("sourceMeanAbsoluteError") in 0.0..25.0)
                        assertEquals(recovered.getValue(proof.getString("cameraId")).name, proof.getString("cameraName"))
                    }
                    val brokerAfter = JSONObject(bound.service.status)
                    assertTrue("Mapping verification must not activate selected media", brokerAfter.optString("phase") !in setOf("active", "activating"))
                    assertFalse(File(context.filesDir, "camera-runtime/source.cfg").exists())
                    report.put("passed", true).put("lastVerifiedAtAfter", after.getLong("lastVerifiedAt"))
                        .put("rootAuthorizationCompletedAtAfter", rootCheckCompletedAt(after))
                        .put("verifiedCameraIds", JSONArray(currentIds.toList())).put("cameras", proofs)
                        .put("brokerPhaseAfter", brokerAfter.getString("phase"))
                } catch (error: Throwable) {
                    report.put("failure", "${error.javaClass.simpleName}: ${error.message}")
                    throw error
                } finally {
                    if (!report.optBoolean("passed") && backup.exists() && !mapping.exists()) {
                        report.put("previousMappingRestored", backup.renameTo(mapping))
                    }
                    if (backup.exists() && mapping.exists()) report.put("backupRemoved", backup.delete())
                    if (backup.exists()) report.put("preservedBackup", backup.absolutePath)
                    File(requireNotNull(context.getExternalFilesDir(null)), "unified-mapping-recovery.json").writeText(report.toString(2))
                }
            }
        }
    }

    private fun rootCheckCompletedAt(snapshot: JSONObject): Long = snapshot.optJSONObject("components")
        ?.optJSONObject("root")?.optJSONObject("lastAuthorizationCheck")?.optLong("completedAt", 0) ?: 0

    private class BoundBroker(private val context: Context) : AutoCloseable {
        private val endpoint = AtomicReference<IVirtualCameraBroker?>()
        private val connected = CountDownLatch(1)
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                endpoint.set(IVirtualCameraBroker.Stub.asInterface(binder)); connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { endpoint.set(null) }
        }
        val service: IVirtualCameraBroker
        init {
            assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName,
                "com.virtualcamerapro.broker.BrokerService")), connection, Context.BIND_AUTO_CREATE))
            assertTrue(connected.await(8, TimeUnit.SECONDS)); service = requireNotNull(endpoint.get())
        }
        override fun close() { context.unbindService(connection) }
    }
}
