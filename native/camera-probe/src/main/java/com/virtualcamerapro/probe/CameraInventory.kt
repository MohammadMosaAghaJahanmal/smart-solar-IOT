package com.virtualcamerapro.probe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import org.json.JSONArray
import org.json.JSONObject

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
                    .put("sensorOrientation", c.get(CameraCharacteristics.SENSOR_ORIENTATION)))
            }
        }
    }
}
