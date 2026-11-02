package com.virtualcamerapro.camera

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs as the real controller UID against the installed production broker. */
@RunWith(AndroidJUnit4::class)
class BrokerConnectionTest {
    @Test fun platformBrokerRespondsToController() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val expected = args.getString("expectedSupportCode", "READY")
        val info = context.packageManager.getApplicationInfo("com.virtualcamerapro.broker", 0)
        assertEquals("The production broker must use the system UID", 1000, info.uid)
        assertTrue("Broker must be installed on a system image partition", info.flags and ApplicationInfo.FLAG_SYSTEM != 0)
        assertEquals(PackageManager.SIGNATURE_MATCH, context.packageManager.checkSignatures("android", info.packageName))
        assertEquals("Known controller certificate must grant CONTROL", PackageManager.PERMISSION_GRANTED,
            context.checkSelfPermission("com.virtualcamerapro.permission.CONTROL"))
        assertEquals("com.virtualcamerapro", context.packageName)
        assertNotEquals("Controller must remain a separate unprivileged app", 1000, Process.myUid())
        val intent = Intent().setComponent(ComponentName(info.packageName, "com.virtualcamerapro.broker.BrokerService"))
        val ready = CountDownLatch(1)
        var remote: IVirtualCameraBroker? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                remote = IVirtualCameraBroker.Stub.asInterface(binder); ready.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) { remote = null }
        }
        assertTrue("Android rejected binding to the production broker", context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("No broker Binder response", ready.await(10, TimeUnit.SECONDS))
            val broker = requireNotNull(remote)
            assertTrue(broker.asBinder().pingBinder())
            val support = JSONObject(broker.support)
            val status = JSONObject(broker.status)
            assertEquals(2, support.getInt("protocol"))
            assertEquals(expected, support.getString("code"))
            assertEquals("READY" == expected, support.getBoolean("supported"))
            for (result in listOf(support, status)) {
                assertEquals(1000, result.getInt("processUid"))
                assertEquals(info.packageName, result.getString("brokerPackage"))
                assertTrue(result.getBoolean("platformSigned"))
                assertTrue(result.getBoolean("systemApp"))
                assertTrue(result.getInt("processPid") > 0)
                assertNotEquals(Process.myPid(), result.getInt("processPid"))
            }
            if (expected != "READY") assertNotEquals("active", status.getString("phase"))
            val report = JSONObject().put("support", support).put("status", status)
                .put("controllerUid", Process.myUid()).put("controllerPid", Process.myPid())
            File(context.getExternalFilesDir(null), "broker-connection.json").writeText(report.toString(2))
            // This checks the same Binder wrapper used by the React Native TurboModule.
            VirtualCameraController(context).use { controller ->
                assertEquals(expected, controller.support().getString("code"))
                if (expected != "READY") assertNotEquals("active", controller.status().getString("phase"))
            }
            context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            context.startForegroundService(intent)
            val deadline = SystemClock.elapsedRealtime() + 8000
            var foreground = false
            while (!foreground && SystemClock.elapsedRealtime() < deadline) {
                val dump = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
                    "dumpsys activity services com.virtualcamerapro.broker")).bufferedReader().use { it.readText() }
                foreground = dump.contains("isForeground=true")
                if (!foreground) Thread.sleep(100)
            }
            assertTrue("The real foreground broker did not start", foreground)
            broker.deactivate()
            assertEquals("stopped", JSONObject(broker.status).getString("phase"))
            assertEquals(expected, JSONObject(broker.support).getString("code"))
            File(context.getExternalFilesDir(null), "broker-lifecycle.json").writeText(JSONObject()
                .put("foregroundServiceStarted", foreground).put("afterStop", JSONObject(broker.status)).toString(2))
        } finally { context.unbindService(connection) }
    }
}
