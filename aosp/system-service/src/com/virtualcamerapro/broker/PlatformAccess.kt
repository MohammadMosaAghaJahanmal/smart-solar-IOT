package com.virtualcamerapro.broker

import android.os.IBinder
import android.content.Context
import android.os.ParcelFileDescriptor
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor

/** Hidden APIs are usable only by the platform-signed system UID broker. */
object PlatformAccess {
    fun service(name: String): IBinder? = Class.forName("android.os.ServiceManager").getMethod("getService",String::class.java).invoke(null,name) as IBinder?
    fun get(name: String): String = Class.forName("android.os.SystemProperties").getMethod("get",String::class.java).invoke(null,name) as String
    fun set(name: String, value: String) { Class.forName("android.os.SystemProperties").getMethod("set",String::class.java,String::class.java).invoke(null,name,value) }
    fun cameraRoute(context: Context): JSONObject {
        val camera = service("media.camera") ?: throw IntegrationFailure("CAMERASERVICE_MISSING", "CameraService", "media.camera is not registered", "Restart the camera service or reboot the reference image.")
        val output = File.createTempFile("camera-route-", ".json", context.cacheDir)
        try {
            ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE).use {
                // TransferPipe gives CameraService a pipe, not access to private app files,
                // and bounds an unresponsive dump to the platform's five-second timeout.
                Class.forName("com.android.internal.os.TransferPipe").getMethod("dumpAsync",
                    IBinder::class.java, FileDescriptor::class.java, Array<String>::class.java)
                    .invoke(null, camera, it.fileDescriptor, arrayOf("--virtual-camera-pro"))
            }
            val text = output.inputStream().use { String(it.readNBytes(4096), Charsets.UTF_8) }
            return try { JSONObject(text).also { require(it.optInt("protocol") == 2) } }
            catch (e: Exception) { throw IntegrationFailure("ROUTING_HOOK_MISSING", "CameraService routing hook", "CameraService does not expose routing protocol 2", "Build and boot the vcam_cf_x86_64_phone image with the supplied CameraService patch; an APK update cannot install this hook.") }
        } finally { output.delete() }
    }
}
