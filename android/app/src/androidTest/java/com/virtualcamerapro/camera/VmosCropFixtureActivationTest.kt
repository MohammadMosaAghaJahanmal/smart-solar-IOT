package com.virtualcamerapro.camera

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.engine.OutputSettings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Activates a caller-provided local test fixture through the ordinary controller. */
class VmosCropFixtureActivationTest {
    @Test fun activateFixtureThroughController() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertEquals(28, Build.VERSION.SDK_INT)
        assertEquals("vphw90", Build.HARDWARE)
        val args = InstrumentationRegistry.getArguments()
        val path = requireNotNull(args.getString("fixture"))
        require(path.startsWith("/sdcard/Download/vcam-"))
        val fixture = File(path)
        check(fixture.isFile && fixture.length() > 0)
        context.startActivity(requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        VirtualCameraController(context).use { controller ->
            controller.deactivate()
            val width = args.getString("width", "1280").toInt()
            controller.update(OutputSettings(cameraId = args.getString("cameraId", "1"), width = width,
                height = if (width == 1920) 1080 else 720,
                rotation = args.getString("rotation", "0").toInt(), scaleMode = args.getString("scaleMode", "fill"),
                replacementMode = args.getString("replacementMode", "single")))
            controller.select(Uri.fromFile(fixture).toString(), args.getString("sourceType", "image"))
            args.getString("displayName")?.let { controller.state.name = it }
            assertTrue(controller.activate())
            assertEquals("active", controller.status().getString("phase"))
            File(context.getExternalFilesDir(null), "crop-fixture-activation.json").writeText(controller.status().toString(2))
        }
    }
}
