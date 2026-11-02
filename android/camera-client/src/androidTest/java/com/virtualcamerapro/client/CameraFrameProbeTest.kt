package com.virtualcamerapro.client

import android.graphics.BitmapFactory
import android.hardware.camera2.CameraManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.virtualcamerapro.probe.CameraFrameProbe
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraFrameProbeTest {
    @Test fun probeReceivesActualYuvAndJpegBuffers() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.CAMERA").close()
        val ids = context.getSystemService(CameraManager::class.java).cameraIdList
        assertTrue("A real camera is required", ids.isNotEmpty())
        val id = InstrumentationRegistry.getArguments().getString("cameraId") ?: ids.first()
        val proof = CameraFrameProbe(context).capture(id)
        assertTrue(proof.previewFrames >= 3)
        assertTrue(proof.lastTimestamp > proof.firstTimestamp)
        assertEquals(proof.previewFrames, proof.centerLuma.size)
        val bitmap = BitmapFactory.decodeByteArray(proof.jpeg, 0, proof.jpeg.size)
        assertNotNull(bitmap); assertTrue(bitmap.width > 0 && bitmap.height > 0); bitmap.recycle()
    }
}
