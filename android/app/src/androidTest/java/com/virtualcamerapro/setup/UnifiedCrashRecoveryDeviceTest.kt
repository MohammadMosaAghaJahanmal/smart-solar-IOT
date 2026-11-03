package com.virtualcamerapro.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.broker.VmosHostIdentity
import com.virtualcamerapro.ipc.IVirtualCameraBroker
import com.virtualcamerapro.probe.CameraFrameProbe
import com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Explicit opt-in: replaces the active source and crashes only this application's verified processes. */
@RunWith(AndroidJUnit4::class)
class UnifiedCrashRecoveryDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceColors = listOf(Color.rgb(220,42,38),Color.rgb(40,192,80),Color.rgb(38,80,220),Color.rgb(216,180,40))
    private var replacementMode = "single"
    private var requestedCameraIds = emptyList<String>()

    @Test fun realProcessCrashesRespectResumePreferenceAndRequireFreshCameraProof() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicitly enable destructive-to-session guest crash checks",
            args.getString("unifiedVmos") == "true" && args.getString("unifiedCrash") == "true")
        replacementMode = args.getString("replacementMode","single")
        require(replacementMode in setOf("single","both"))
        assertTrue(GuestAdapterRegistry.profileMatches())
        assertEquals("com.virtualcamerapro",context.packageName)
        assertEquals(context.applicationInfo.uid,Process.myUid())
        assertTrue("Crash tests must never run as guest root or system",Process.myUid() >= 10000)
        val preferences = requirePrepared()
        val fixture = File(context.cacheDir,"unified-crash-recovery.png")
        createFixture(fixture)
        val sourceHash = GuestAdapterRegistry.sha256(fixture)
        val report = JSONObject().put("passed",false).put("controllerUid",Process.myUid())
            .put("controllerPid",Process.myPid()).put("replacementMode",replacementMode).put("scenarios",JSONArray())
        try {
            BoundBroker(context).use { bound ->
                bound.endpoint().deactivate()
                val support = JSONObject(bound.endpoint().support)
                assertTrue(support.toString(),support.getBoolean("supported"))
                requestedCameraIds = if (replacementMode == "both") {
                    val modes = support.getJSONArray("replacementModes")
                    assertTrue("Both-camera recovery requires actual both-route support",(0 until modes.length()).any { modes.getString(it) == "both" })
                    val ids = support.getJSONArray("bothCameraIds")
                    assertTrue(ids.length() >= 2)
                    (0 until ids.length()).map { ids.getString(it) }.also { assertEquals(it.size,it.toSet().size) }
                } else listOf(support.getString("cameraId"))
                val choices = support.getJSONArray(if (replacementMode == "both") "bothResolutions" else "resolutions")
                assertTrue("Requested routes need an actual common resolution",choices.length() > 0)
                val resolution = (0 until choices.length()).map { choices.getJSONObject(it) }
                    .firstOrNull { it.getInt("width") == 640 && it.getInt("height") == 480 }
                    ?: (0 until choices.length()).map { choices.getJSONObject(it) }
                        .minBy { it.getInt("width").toLong()*it.getInt("height") }
                val settings = JSONObject().put("width",resolution.getInt("width")).put("height",resolution.getInt("height"))
                    .put("loop",true).put("rotation",0).put("scaleMode","fit").put("cameraId",requestedCameraIds.first())
                    .put("replacementMode",replacementMode)
                report.put("requestedCameraIds",JSONArray(requestedCameraIds)).put("width",settings.getInt("width")).put("height",settings.getInt("height"))

                setPreferences(true,false)
                val beforeOff = activate(bound.endpoint(),fixture,settings,sourceHash)
                val offCrash = crash(beforeOff.getInt("processPid"),"service")
                val restartedOff = awaitState(bound,45_000) { it.getInt("processPid") != offCrash.getInt("pid") }
                assertNotEquals(offCrash.getInt("pid"),restartedOff.getInt("processPid"))
                val offDeadline = SystemClock.elapsedRealtime()+13_000
                var offSamples = 0
                do {
                    val state = JSONObject(bound.endpoint().status)
                    assertTrue("Opt-out must not silently reactivate: $state",state.getString("phase") !in setOf("active","activating"))
                    assertTrue("A restarted service must drop the previous Active proof",state.isNull("proof"))
                    assertFalse("Old publication must be removed after process restart",File(context.filesDir,"camera-runtime/source.cfg").exists())
                    offSamples++; Thread.sleep(200)
                } while (SystemClock.elapsedRealtime() < offDeadline)
                val passThrough = requestedCameraIds.map { id ->
                    val frames = CameraFrameProbe(context).capture(id,300,settings.getInt("width"),settings.getInt("height"))
                    assertTrue(frames.previewFrames >= 3 && frames.jpeg.isNotEmpty() && frames.lastTimestamp > frames.firstTimestamp)
                    JSONObject().put("cameraId",id).put("previewFrames",frames.previewFrames).put("jpegBytes",frames.jpeg.size)
                }
                report.getJSONArray("scenarios").put(offCrash.put("scenario","service_restart_resume_off")
                    .put("passed",true).put("restartedPid",restartedOff.getInt("processPid"))
                    .put("inactiveSamples",offSamples).put("realPassThroughPreviewFrames",passThrough.first().getInt("previewFrames"))
                    .put("passThroughCameras",JSONArray(passThrough)))

                setPreferences(true,true)
                val beforeOn = activate(bound.endpoint(),fixture,settings,sourceHash)
                val onCrash = crash(beforeOn.getInt("processPid"),"service")
                val resumed = awaitFreshProof(bound,beforeOn,onCrash,sourceHash,90_000)
                assertNotEquals(onCrash.getInt("pid"),resumed.getInt("processPid"))
                val onPixels = captureSourceColors(settings)
                assertEquals("Camera switching must preserve the resumed generation",resumed.getJSONObject("proof").getString("generation"),
                    JSONObject(bound.endpoint().status).getJSONObject("proof").getString("generation"))
                report.getJSONArray("scenarios").put(onCrash.put("scenario","service_restart_resume_on")
                    .put("passed",true).put("restartedPid",resumed.getInt("processPid"))
                    .put("freshProof",proofSummary(resumed)).put("independentPixels",onPixels))

                val beforeProxy = JSONObject(bound.endpoint().status)
                assertActive(beforeProxy,sourceHash)
                val nativeCrash = crash(beforeProxy.getJSONObject("proof").getInt("proxyPid"),"proxy")
                val nativeResumed = awaitFreshProof(bound,beforeProxy,nativeCrash,sourceHash,90_000)
                assertNotEquals(nativeCrash.getInt("pid"),nativeResumed.getJSONObject("proof").getInt("proxyPid"))
                val nativePixels = captureSourceColors(settings)
                assertEquals("Camera switching must preserve the replacement proxy's resumed generation",nativeResumed.getJSONObject("proof").getString("generation"),
                    JSONObject(bound.endpoint().status).getJSONObject("proof").getString("generation"))
                report.getJSONArray("scenarios").put(nativeCrash.put("scenario","native_proxy_resume_on")
                    .put("passed",true).put("replacementProxyPid",nativeResumed.getJSONObject("proof").getInt("proxyPid"))
                    .put("freshProof",proofSummary(nativeResumed)).put("independentPixels",nativePixels))
                report.put("passed",true)
            }
        } finally {
            // A crash must not turn test media into a later user-requested resume.
            runCatching { BoundBroker(context).use { it.endpoint().deactivate() } }
            runCatching { setPreferences(preferences.getBoolean("autoRecover"),preferences.getBoolean("autoResume")) }
                .onFailure { report.put("preferenceRestoreError",it.javaClass.simpleName); report.put("passed",false) }
            fixture.delete()
            File(context.getExternalFilesDir(null),if (replacementMode == "both") "unified-crash-recovery-both.json" else "unified-crash-recovery.json").writeText(report.toString(2))
        }
        assertTrue("Crash recovery or preference cleanup failed: $report",report.getBoolean("passed"))
    }

    private fun requirePrepared(): JSONObject = UnifiedSetupClient(context).use { setup ->
        val deadline = SystemClock.elapsedRealtime()+30_000
        var state = setup.getStatus()
        while (state.optString("state") == "inspecting" && SystemClock.elapsedRealtime() < deadline) {
            Thread.sleep(100); state = setup.getStatus()
        }
        assertTrue("Prepare and verify the integrated guest first: $state",state.optBoolean("ready"))
        assertEquals("integrated_vmos",state.getString("setupBackend"))
        state.getJSONObject("preferences")
    }

    private fun setPreferences(recover: Boolean,resume: Boolean) = UnifiedSetupClient(context).use {
        it.setRecoveryPreferences(recover,resume)
    }

    private fun createFixture(file: File) {
        val bitmap = Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap); val paint = Paint()
            sourceColors.forEachIndexed { index,color ->
                paint.color = color
                val left = (index%2)*160f; val top = (index/2)*120f
                canvas.drawRect(left,top,left+160f,top+120f,paint)
            }
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { bitmap.recycle() }
    }

    private fun activate(broker: IVirtualCameraBroker,file: File,settings: JSONObject,hash: String): JSONObject {
        ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY).use {
            assertTrue("Actual fixture activation failed",broker.activate(it,"image",settings.toString()))
        }
        return JSONObject(broker.status).also { assertActive(it,hash) }
    }

    private fun assertActive(state: JSONObject,hash: String) {
        assertEquals("active",state.getString("phase"))
        assertEquals(Process.myUid(),state.getInt("processUid"))
        val proof = state.getJSONObject("proof")
        assertTrue(proof.getBoolean("sourceVerified")); assertTrue(proof.getBoolean("rendererConfigured"))
        assertEquals(hash,proof.getString("sourceSha256"))
        assertTrue(proof.getInt("previewFrames") >= 3 && proof.getInt("jpegBytes") > 0)
        assertTrue(proof.getLong("lastTimestampNs") > proof.getLong("firstTimestampNs"))
        assertTrue(proof.getDouble("bestMeanAbsoluteError") in 0.0..25.0)
        assertTrue(proof.getLong("verifiedAtElapsedMs") > 0 && proof.getString("generation").isNotBlank())
        if (replacementMode == "both") {
            assertEquals("both",state.getString("replacementMode")); assertEquals("both",proof.getString("replacementMode"))
            val ids = proof.getJSONArray("cameraIds")
            assertEquals(requestedCameraIds.size,ids.length())
            assertEquals(requestedCameraIds.toSet(),(0 until ids.length()).map { ids.getString(it) }.toSet())
            val cameras = proof.getJSONArray("cameras")
            assertEquals(requestedCameraIds.size,cameras.length())
            assertEquals(requestedCameraIds.toSet(),(0 until cameras.length()).map { cameras.getJSONObject(it).getString("cameraId") }.toSet())
            for (index in 0 until cameras.length()) {
                val camera = cameras.getJSONObject(index)
                assertTrue(camera.getBoolean("sourceVerified"))
                assertTrue(camera.getInt("previewFrames") >= 3 && camera.getInt("jpegBytes") > 0 && camera.getLong("proxyFrames") >= 3)
                assertTrue(camera.getLong("lastTimestampNs") > camera.getLong("firstTimestampNs"))
                assertTrue(camera.getDouble("bestMeanAbsoluteError") in 0.0..25.0)
                assertTrue(camera.getString("cameraName").isNotBlank())
            }
        }
    }

    private fun awaitState(bound: BoundBroker,timeout: Long,predicate: (JSONObject) -> Boolean): JSONObject {
        val deadline = SystemClock.elapsedRealtime()+timeout
        var last: JSONObject? = null
        do {
            runCatching { JSONObject(bound.endpoint(1000).status) }.getOrNull()?.let {
                last = it
                if (predicate(it)) return it
            }
            Thread.sleep(100)
        } while (SystemClock.elapsedRealtime() < deadline)
        throw AssertionError("The verified guest did not recover within ${timeout}ms: $last")
    }

    private fun awaitFreshProof(bound: BoundBroker,before: JSONObject,crash: JSONObject,hash: String,timeout: Long): JSONObject {
        val previousGeneration = before.getJSONObject("proof").getString("generation")
        return awaitState(bound,timeout) { state ->
            val proof = state.optJSONObject("proof")
            state.optString("phase") == "active" && proof != null &&
                proof.optString("generation") != previousGeneration &&
                proof.optLong("verifiedAtElapsedMs") > crash.getLong("killedAtElapsedMs")
        }.also { assertActive(it,hash) }
    }

    private fun proofSummary(state: JSONObject): JSONObject = state.getJSONObject("proof").let {
        JSONObject().put("generation",it.getString("generation")).put("verifiedAtElapsedMs",it.getLong("verifiedAtElapsedMs"))
            .put("previewFrames",it.getInt("previewFrames")).put("jpegBytes",it.getInt("jpegBytes"))
            .put("sourceMeanAbsoluteError",it.getDouble("bestMeanAbsoluteError"))
            .put("replacementMode",replacementMode).put("cameraIds",JSONArray(requestedCameraIds))
            .put("cameras",it.optJSONArray("cameras"))
    }

    /** Independent post-recovery Camera2 capture; quadrant order may rotate or mirror. */
    private fun captureSourceColors(settings: JSONObject): JSONObject {
        if (replacementMode == "single") return captureSourceColorsFor(settings.getString("cameraId"),settings)
        val observations = JSONArray()
        // Reopen the first route again to verify front/back switching after each recovery.
        for (id in requestedCameraIds + requestedCameraIds.first()) observations.put(captureSourceColorsFor(id,settings))
        return JSONObject().put("cameras",observations)
    }
    private fun captureSourceColorsFor(cameraId: String,settings: JSONObject): JSONObject {
        val captured = CameraFrameProbe(context).capture(cameraId,300,settings.getInt("width"),settings.getInt("height"))
        assertTrue(captured.previewFrames >= 3 && captured.lastTimestamp > captured.firstTimestamp && captured.jpeg.isNotEmpty())
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(captured.jpeg,0,captured.jpeg.size))
        try {
            val colors = listOf(1 to 1,3 to 1,1 to 3,3 to 3).map { (x,y) -> bitmap.getPixel(bitmap.width*x/4,bitmap.height*y/4) }
            val errors = sourceColors.map { expected -> colors.minOf { actual ->
                (abs(Color.red(expected)-Color.red(actual))+abs(Color.green(expected)-Color.green(actual))+abs(Color.blue(expected)-Color.blue(actual))) / 3.0
            } }
            assertTrue("Actual post-recovery JPEG lost the selected fixture colors: $errors",errors.all { it < 35.0 })
            return JSONObject().put("previewFrames",captured.previewFrames).put("jpegBytes",captured.jpeg.size)
                .put("cameraId",cameraId)
                .put("width",bitmap.width).put("height",bitmap.height).put("quadrantColorErrors",JSONArray(errors))
        } finally { bitmap.recycle() }
    }

    private data class ProcessIdentity(val pid: Int,val ticks: String,val argv: List<String>,val exe: String,val sha256: String)
    private fun field(pid: Int,name: String,limit: Int): String = File("/proc/$pid/$name").inputStream().use { input ->
        val bytes = ByteArray(limit+1); var count = 0
        while (count < bytes.size) { val read = input.read(bytes,count,bytes.size-count); if (read < 0) break; count += read }
        check(count <= limit) { "Process identity exceeds its fixed bound" }
        String(bytes,0,count,Charsets.UTF_8)
    }

    private fun identity(pid: Int,kind: String): ProcessIdentity {
        require(pid > 1 && pid != Process.myPid() && kind in setOf("service","proxy"))
        val uids = field(pid,"status",16384).lineSequence().first { it.startsWith("Uid:") }
            .substringAfter(':').trim().split(Regex("\\s+")).map { it.toInt() }
        assertEquals("Only all-four-UID matching app processes may be crashed",List(4) { Process.myUid() },uids)
        val argv = field(pid,"cmdline",4096).trimEnd('\u0000').split('\u0000')
        val executable = File(context.filesDir,"vmos-camera-proxy").absolutePath
        assertEquals(if (kind == "service") listOf("${context.packageName}:camera") else listOf(executable,"--listen",
            GuestRuntimeSocket,"--original","/dev/socket/socket/qemu_pipe","--state-dir",File(context.filesDir,"camera-runtime").absolutePath),argv)
        val exe = Os.readlink("/proc/$pid/exe")
        val hash = GuestAdapterRegistry.sha256(File("/proc/$pid/exe"))
        if (kind == "service") {
            assertEquals("The remote Java process must use the same launcher as this app",GuestAdapterRegistry.sha256(File("/proc/self/exe")),hash)
        } else {
            val bundled = context.assets.open("vmos-camera-proxy.sha256").bufferedReader().use { it.readText().trim() }
            assertTrue(bundled.matches(Regex("[a-f0-9]{64}")))
            assertEquals(bundled,GuestAdapterRegistry.sha256(File(executable)))
            val direct = exe == executable && hash == bundled
            val mapped = VmosHostIdentity.isPinnedLauncher(exe, hash, fromProcExe = true)
            assertTrue("The native process is neither the bundled proxy nor its pinned VMOS launcher",direct || mapped)
        }
        val stat = field(pid,"stat",4096)
        val ticks = stat.substring(stat.lastIndexOf(')')+2).split(' ').getOrNull(19)
        require(ticks != null && ticks.matches(Regex("[0-9]+")))
        return ProcessIdentity(pid,ticks,argv,exe,hash)
    }

    private fun crash(pid: Int,kind: String): JSONObject {
        val checked = identity(pid,kind)
        assertEquals("Process identity changed before its test-only crash",checked,identity(pid,kind))
        val whenKilled = SystemClock.elapsedRealtime()
        Os.kill(pid,OsConstants.SIGKILL)
        return JSONObject().put("pid",pid).put("kind",kind).put("uid",Process.myUid())
            .put("startTicks",checked.ticks).put("executableSha256",checked.sha256).put("killedAtElapsedMs",whenKilled)
    }

    private class BoundBroker(private val context: Context) : AutoCloseable {
        private val remote = AtomicReference<IVirtualCameraBroker?>()
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName,binder: IBinder) { remote.set(IVirtualCameraBroker.Stub.asInterface(binder)) }
            override fun onServiceDisconnected(name: ComponentName) { remote.set(null) }
        }
        init {
            assertTrue(context.bindService(Intent().setComponent(ComponentName(context.packageName,"com.virtualcamerapro.broker.BrokerService")),connection,Context.BIND_AUTO_CREATE))
        }
        fun endpoint(timeout: Long = 8000): IVirtualCameraBroker {
            val deadline = SystemClock.elapsedRealtime()+timeout
            do {
                remote.get()?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
                Thread.sleep(25)
            } while (SystemClock.elapsedRealtime() < deadline)
            throw IllegalStateException("The integrated broker did not reconnect")
        }
        override fun close() { context.unbindService(connection) }
    }

    private companion object { const val GuestRuntimeSocket = "/data/local/tmp/vcam_pipe" }
}
