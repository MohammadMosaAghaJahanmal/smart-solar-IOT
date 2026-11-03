package com.virtualcamerapro.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.camera.VirtualCameraController
import com.virtualcamerapro.engine.OutputSettings
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in integration tests. These explicitly stop the current guest media session. */
@RunWith(AndroidJUnit4::class)
class UnifiedRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun prepared(client: UnifiedSetupClient) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in after one-APK guest preparation",args.getString("unifiedVmos") == "true" && args.getString("unifiedRecovery") == "true")
        assertTrue(GuestAdapterRegistry.profileMatches())
        assertNotEquals(0,Process.myUid()); assertNotEquals(1000,Process.myUid())
        val deadline = SystemClock.elapsedRealtime()+30_000
        var status = client.getStatus()
        while (!status.optBoolean("ready") && status.optString("state") == "inspecting" && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100); status = client.getStatus()
        }
        assertTrue("Real integrated camera preparation must finish first: $status",status.optBoolean("ready"))
        assertEquals("integrated_vmos",status.getString("setupBackend"))
    }
    private fun journal(): JSONObject {
        val file = File(context.applicationInfo.dataDir,"shared_prefs/guest-broker-session-v1.xml")
        val result = JSONObject().put("requestedActive",false)
        if (!file.isFile) return result
        file.reader().use { input ->
            val parser = Xml.newPullParser().apply { setInput(input) }
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG) {
                    val name = parser.getAttributeValue(null,"name")
                    if (parser.name == "boolean" && name == "requestedActive")
                        result.put(name,parser.getAttributeValue(null,"value") == "true")
                    else if (parser.name == "string" && name == "source") result.put(name,JSONObject(parser.nextText()))
                }
                parser.next()
            }
        }
        return result
    }
    private fun sourceCopies() = context.filesDir.listFiles()?.filter { it.name.matches(Regex("source-[A-Za-z0-9._-]+\\.media")) }
        ?.associate { it.name to it.length() } ?: emptyMap()
    private fun assertRemainsOff(broker: IVirtualCameraBroker): Int {
        val until = SystemClock.elapsedRealtime()+12_000 // Includes at least one real recovery heartbeat.
        var samples = 0
        do {
            val state = JSONObject(broker.status)
            assertEquals("Explicit Stop must survive recovery supervision: $state","stopped",state.getString("phase"))
            assertTrue(state.isNull("proof"))
            assertFalse(journal().getBoolean("requestedActive"))
            assertFalse(File(context.filesDir,"camera-runtime/source.cfg").exists())
            samples++; Thread.sleep(200)
        } while (SystemClock.elapsedRealtime() < until)
        return samples
    }
    private fun report(name: String,value: JSONObject) = File(context.getExternalFilesDir(null),"$name.json").writeText(value.toString(2))

    @Test fun setupVerificationClearsThePreviouslyRequestedResume() {
        assumeTrue("Explicitly enable setup interruption of an active source",
            InstrumentationRegistry.getArguments().getString("unifiedSetupIntent") == "true")
        UnifiedSetupClient(context).use { setup ->
            prepared(setup)
            val previous = setup.getStatus()
            val preferences = previous.getJSONObject("preferences")
            val fixture = File(context.cacheDir,"unified-setup-intent.png")
            val bitmap = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(182,116,72)) }
            try { fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
            BoundBroker(context).use { bound ->
                try {
                    setup.setRecoveryPreferences(true,true)
                    val support = JSONObject(bound.service.support)
                    val resolution = support.getJSONArray("resolutions").let { choices ->
                        (0 until choices.length()).map { choices.getJSONObject(it) }.minBy { it.getInt("width").toLong()*it.getInt("height") }
                    }
                    val settings = JSONObject().put("width",resolution.getInt("width")).put("height",resolution.getInt("height"))
                        .put("cameraId",support.getString("cameraId")).put("loop",true).put("rotation",0).put("scaleMode","fit")
                    ParcelFileDescriptor.open(fixture,ParcelFileDescriptor.MODE_READ_ONLY).use {
                        assertTrue(bound.service.activate(it,"image",settings.toString()))
                    }
                    assertTrue(journal().getBoolean("requestedActive"))
                    setup.run("verify",false)
                    val deadline = SystemClock.elapsedRealtime()+60_000
                    var state = setup.getStatus()
                    while (!(state.optBoolean("ready") && state.optLong("lastVerifiedAt") > previous.optLong("lastVerifiedAt")) &&
                        SystemClock.elapsedRealtime() < deadline) { Thread.sleep(150); state = setup.getStatus() }
                    assertTrue("Fresh actual setup verification must finish: $state",state.optBoolean("ready"))
                    assertTrue(state.optLong("lastVerifiedAt") > previous.optLong("lastVerifiedAt"))
                    val until = SystemClock.elapsedRealtime()+12_000
                    var samples = 0
                    do {
                        val broker = JSONObject(bound.service.status)
                        assertTrue(broker.optString("phase") !in setOf("active","activating"))
                        assertTrue(broker.isNull("proof")); assertFalse(journal().getBoolean("requestedActive"))
                        assertFalse(File(context.filesDir,"camera-runtime/source.cfg").exists())
                        samples++; Thread.sleep(200)
                    } while (SystemClock.elapsedRealtime() < until)
                    report("unified-setup-clears-resume",JSONObject().put("passed",true).put("autoResumeEnabledDuringTest",true)
                        .put("previousRequestedActive",true).put("requestedActiveAfterVerification",false)
                        .put("inactiveSamples",samples).put("freshVerificationAt",state.getLong("lastVerifiedAt")))
                } finally {
                    runCatching { bound.service.deactivate() }; fixture.delete()
                    setup.setRecoveryPreferences(preferences.getBoolean("autoRecover"),preferences.getBoolean("autoResume"))
                }
            }
        }
    }

    @Test fun explicitDeactivateCancelsAnInFlightSourceCopyAndPreventsResume() {
        UnifiedSetupClient(context).use { setup ->
            prepared(setup)
            val preferences = setup.getStatus().getJSONObject("preferences")
            BoundBroker(context).use { bound ->
                val broker = bound.service
                assertTrue("Start this cancellation test with media already stopped",JSONObject(broker.status).getString("phase") in setOf("ready","stopped"))
                val before = sourceCopies()
                val pipe = ParcelFileDescriptor.createPipe()
                val writer = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
                val worker = Executors.newSingleThreadExecutor()
                try {
                    setup.setRecoveryPreferences(true,true)
                    val activation = worker.submit<Boolean> {
                        // The pipe deliberately blocks before decoding. It is never
                        // accepted as selected media or used to fabricate Active proof.
                        broker.activate(pipe[0],"image",JSONObject().put("width",640).put("height",480)
                            .put("loop",true).put("rotation",0).put("scaleMode","fit").put("cameraId","0").toString())
                    }
                    writer.write(ByteArray(1024) { (it and 255).toByte() }); writer.flush()
                    val deadline = SystemClock.elapsedRealtime()+8_000
                    while (sourceCopies().none { it.key !in before && it.value >= 1024 } && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
                    assertTrue("The real remote copy must be blocked before Stop is issued",sourceCopies().any { it.key !in before && it.value >= 1024 })
                    assertFalse(activation.isDone)
                    broker.deactivate()
                    assertEquals("stopped",JSONObject(broker.status).getString("phase"))
                    writer.close()
                    assertFalse("Cancelled activation must not report success",activation.get(20,TimeUnit.SECONDS))
                    assertEquals("The uncommitted source copy must be removed",before,sourceCopies())
                    val samples = assertRemainsOff(broker)
                    report("unified-deactivate-race",JSONObject().put("passed",true).put("blockedRealBinderCopy",true)
                        .put("activationReturned",false).put("stoppedSamples",samples).put("autoResumeEnabledDuringTest",true))
                } finally {
                    runCatching { broker.deactivate() }; runCatching { writer.close() }; runCatching { pipe[0].close() }
                    worker.shutdownNow()
                    setup.setRecoveryPreferences(preferences.getBoolean("autoRecover"),preferences.getBoolean("autoResume"))
                }
            }
        }
    }

    @Test fun clearSelectionRemovesBothCopiesAndCannotBeAutoResumed() {
        assumeTrue("This test explicitly replaces and clears the current selected media",
            InstrumentationRegistry.getArguments().getString("unifiedClearMedia") == "true")
        UnifiedSetupClient(context).use { setup ->
            prepared(setup)
            val preferences = setup.getStatus().getJSONObject("preferences")
            val fixture = File(context.cacheDir,"unified-clear-selection.png")
            val bitmap = Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(176,102,62)) }
            try { fixture.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) } }
            finally { bitmap.recycle() }
            VirtualCameraController(context).use { controller ->
                try {
                    setup.setRecoveryPreferences(true,true)
                    controller.deactivate()
                    val support = controller.support()
                    assertTrue(support.toString(),support.getBoolean("supported"))
                    val resolution = support.getJSONArray("resolutions").let { choices ->
                        (0 until choices.length()).map { choices.getJSONObject(it) }.minBy { it.getInt("width").toLong()*it.getInt("height") }
                    }
                    controller.update(OutputSettings(width=resolution.getInt("width"),height=resolution.getInt("height"),cameraId=support.getString("cameraId")))
                    controller.select(Uri.fromFile(fixture).toString(),"image")
                    assertTrue(controller.activate())
                    val controllerCopy = File(Uri.parse(controller.state.uri).path!!)
                    val source = journal().getJSONObject("source").getString("file")
                    assertTrue(source.matches(Regex("source-[A-Za-z0-9._-]+\\.media")))
                    val brokerCopy = File(context.filesDir,source)
                    assertTrue(controllerCopy.isFile); assertTrue(brokerCopy.isFile)
                    assertTrue(journal().getBoolean("requestedActive"))
                    controller.clearMedia(); setup.run("clear_media",false)
                    assertEquals("",controller.state.uri); assertEquals("",controller.state.name)
                    assertFalse(controllerCopy.exists()); assertFalse(brokerCopy.exists())
                    assertFalse(journal().has("source")); assertFalse(journal().getBoolean("requestedActive"))
                    BoundBroker(context).use { bound ->
                        val samples = assertRemainsOff(bound.service)
                        report("unified-clear-selection",JSONObject().put("passed",true).put("controllerCopyRemoved",true)
                            .put("brokerCopyRemoved",true).put("stoppedSamples",samples))
                    }
                } finally {
                    runCatching { controller.deactivate() }; fixture.delete()
                    setup.setRecoveryPreferences(preferences.getBoolean("autoRecover"),preferences.getBoolean("autoResume"))
                }
            }
        }
    }

    private class BoundBroker(private val context: Context) : AutoCloseable {
        private val endpoint = AtomicReference<IVirtualCameraBroker?>()
        private val connected = CountDownLatch(1)
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) { endpoint.set(IVirtualCameraBroker.Stub.asInterface(binder)); connected.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { endpoint.set(null) }
        }
        val service: IVirtualCameraBroker
        init {
            assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName,"com.virtualcamerapro.broker.BrokerService")),connection,Context.BIND_AUTO_CREATE))
            assertTrue(connected.await(8,TimeUnit.SECONDS)); service = requireNotNull(endpoint.get())
        }
        override fun close() { context.unbindService(connection) }
    }
}
