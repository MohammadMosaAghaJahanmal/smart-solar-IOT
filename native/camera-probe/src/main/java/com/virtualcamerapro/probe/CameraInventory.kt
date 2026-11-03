package com.virtualcamerapro.probe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONArray
import org.json.JSONObject
import android.graphics.ImageFormat

object CameraInventory {
    fun query(context: Context): JSONArray {
        val manager = context.getSystemService(CameraManager::class.java)
        return JSONArray().apply {
            manager.cameraIdList.filterNot { it.startsWith("v0_") }.forEach { id ->
                val c = manager.getCameraCharacteristics(id)
                val facing = when (c.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    else -> "external"
                }
                put(JSONObject().put("id", id).put("facing", facing)
                    .put("hardwareLevel", c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL))
                    .put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION))
                    .put("maxDigitalZoom", c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM))
                    .put("resolutions", JSONArray().apply {
                        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        val jpeg = map?.getOutputSizes(ImageFormat.JPEG)?.map { it.width to it.height }?.toSet() ?: emptySet()
                        map?.getOutputSizes(ImageFormat.YUV_420_888)?.filter { size ->
                            size.width in 32..4096 && size.height in 32..4096 && size.width % 2 == 0 && size.height % 2 == 0 &&
                                size.width.toLong() * size.height <= 8_847_360 && size.width to size.height in jpeg
                        }?.sortedBy { it.width.toLong() * it.height }?.forEach {
                            put(JSONObject().put("width",it.width).put("height",it.height))
                        }
                    }))
            }
        }
    }
}
