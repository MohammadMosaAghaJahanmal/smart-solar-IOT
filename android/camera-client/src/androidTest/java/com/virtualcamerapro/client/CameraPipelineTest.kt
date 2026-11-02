package com.virtualcamerapro.client

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assume.assumeTrue

@RunWith(AndroidJUnit4::class)
class CameraPipelineTest {
    private fun await(label: String, check: () -> Boolean) {
        val until = SystemClock.elapsedRealtime()+15000
        while (!check()) { if(SystemClock.elapsedRealtime() >= until) fail("Timed out: $label"); Thread.sleep(100) }
    }
    private fun verify(api: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        if (context.checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        }
        val intent = Intent(context,ClientActivity::class.java).putExtra("api",api).putExtra("cameraId",args.getString("cameraId","0")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ClientActivity>(intent).use { scenario ->
            lateinit var activity: ClientActivity
            scenario.onActivity { activity = it }
            await("$api preview frames") { activity.previewFrames >= 15 || activity.failure != null }
            assertNull(activity.failure)
            scenario.onActivity { it.capture() }
            await("$api JPEG") { activity.lastCapture != null || activity.failure != null }
            assertNull(activity.failure)
            val bitmap = requireNotNull(BitmapFactory.decodeFile(requireNotNull(activity.lastCapture).absolutePath)) { "Capture must be a valid JPEG" }
            assertNotNull("Capture must be a valid JPEG",bitmap)
            // Run with the solid RGB fixture selected + activated to prove external pixels.
            args.getString("expectedRgb")?.let { value ->
                val expected = Color.parseColor("#$value")
                val pixel = bitmap.getPixel(bitmap.width/2,bitmap.height/2)
                assertTrue("Wrong source: got ${Integer.toHexString(pixel)}",kotlin.math.abs(Color.red(pixel)-Color.red(expected)) < 30 && kotlin.math.abs(Color.green(pixel)-Color.green(expected)) < 30 && kotlin.math.abs(Color.blue(pixel)-Color.blue(expected)) < 30)
            }
            bitmap.recycle()
        }
    }
    @Test fun camera2PreviewAndJpeg() = verify("camera2")
    @Test fun cameraXPreviewAndJpeg() = verify("camerax")
    private fun videoLoop(api: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("video") == "true")
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val id=InstrumentationRegistry.getArguments().getString("cameraId","0")
        val intent=Intent(context,ClientActivity::class.java).putExtra("api",api).putExtra("cameraId",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<ClientActivity>(intent).use { scenario ->
            lateinit var activity: ClientActivity;scenario.onActivity { activity=it }
            await("video preview") { activity.previewFrames >= 15 || activity.failure != null };assertNull(activity.failure)
            val colors=mutableListOf<Boolean>()
            repeat(14) {
                scenario.onActivity { it.capture() };await("video JPEG") { activity.lastCapture != null || activity.failure != null };assertNull(activity.failure)
                val bitmap=requireNotNull(BitmapFactory.decodeFile(requireNotNull(activity.lastCapture).absolutePath)) { "Capture must be a valid JPEG" }
                val color=bitmap.getPixel(bitmap.width/2,bitmap.height/2);bitmap.recycle()
                assertTrue("Expected red/blue fixture pixels",Color.red(color)>180 || Color.blue(color)>180)
                colors.add(Color.red(color)>Color.blue(color));Thread.sleep(450)
            }
            assertTrue("Video must change color across repeated 2-second loops",colors.zipWithNext().count { it.first != it.second } >= 4)
        }
    }
    @Test fun camera2VideoLoops() = videoLoop("camera2")
    @Test fun cameraXVideoLoops() = videoLoop("camerax")
}
