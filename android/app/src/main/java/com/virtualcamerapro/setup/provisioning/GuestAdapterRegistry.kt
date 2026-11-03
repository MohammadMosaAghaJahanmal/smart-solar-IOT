package com.virtualcamerapro.setup.provisioning

import android.os.Build
import com.virtualcamerapro.broker.VmosGuestBuild
import android.system.Os
import android.system.OsConstants
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Versioned guest profiles pin binaries; no physical phone manufacturer is a selector. */
object GuestAdapterRegistry {
    const val PROFILE_ID = "vmos-android9-arm64-rom10075"
    const val PROFILE_VERSION = 1
    const val HAL = "/vendor/lib64/hw/camera.vphw90.so"
    const val FACTORY_HAL = "362261e9d1924bde73652cd8f2c467fbaabc59f50ad66e0b4de0215291ec6d3c"
    const val LEGACY_TRANSPORT = "1c80cb9711b08d28625e140263b6bd85aadaf2c6a1de50d161e19fc6e1773a97"
    const val REPAIRED_TRANSPORT = "02042c56980813c89611698d6501ccd78ca05c8a9827a55aa2c21e634bd5086a"
    const val CROP_WRAPPER = "9d13ca05e5963c9486f34435b8c96baf755ebdbca0112c382e7f2c103d717157"
    const val PROVIDER = "/vendor/bin/hw/android.hardware.camera.provider@2.4-service"
    const val PROVIDER_SHA = "fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87"
    const val IMPORTER = "/vendor/lib64/camera.device@3.2-impl.so"
    const val IMPORTER_ORIGINAL = "be8cbff7d20ec7f9e8ea16645ca919196e174911bb7cfcfbcac458acaa8b6acc"
    const val IMPORTER_REPAIRED = "b572750c30143af55a8b8ec4bc1a60017cac3ffec512deb5edffb054877817ab"
    const val JPEG_HELPER = "/vendor/lib64/hw/camera.vphw90.jpeg.so"
    const val JPEG_SHA = "0975abc8d3eec3f57bd607458101f69a11f0a7c93b2de97094cfab534175c2e3"
    const val JPEG_COMPAT = "/vendor/lib/hw/camera.vphw90.jpeg.so"
    const val SOCKET = "/data/local/tmp/vcam_pipe"
    const val VMOS_LINKER = "d3806cf0d56869b4fed7f0f8674498d4e6d4e2e7011903ce3380a21e6c1168a4"
    val knownHalHashes = setOf(FACTORY_HAL, LEGACY_TRANSPORT, REPAIRED_TRANSPORT, CROP_WRAPPER)
    val knownProxyHashes = setOf("8855fbf54d0ecae94ba2c13338c52cf90d2997eba465f4ad548477f30e0c4591",
        "53e32e2d80beefbb77399d34974d89da4fbf8a8fd08313fa67c1b51edee6cee3",
        "1162c3ef0f3dc4a9b2ebb8fabf558dbbe31aead0ef4fa0c04897c5ca10721bcc",
        "540d4fb9593574f51a8cf94da2d2dc43aded5b777ee86720c8eba0ccd406e2c6",
        "14232f10185851bded52a882fcb1d6509e8560fcccb8fe8034163770ee160d1e",
        "f6d508f0b5412fca1f169a082eec4ce66c844168e9cfae26490e838d546d1e11",
        "97e4b1ae9be3a8fe422e8a37175f3f440e558ecf3ca64cf38ac1264c41c7a08d")

    fun profileMatches(): Boolean = VmosGuestBuild.matches(Build.VERSION.SDK_INT,Build.HARDWARE,
        Build.SUPPORTED_ABIS.toList(),Build.FINGERPRINT)

    fun requireSupportedGuest() {
        if (!profileMatches()) throw GuestProvisioningException("GUEST_PROFILE_UNSUPPORTED",
            "No validated camera adapter matches this guest framework and architecture",
            "Use a validated VMOS guest profile. The physical phone operating system is never modified.")
        check(sha256(File(PROVIDER)) == PROVIDER_SHA) { "Guest provider differs from the validated adapter profile" }
    }

    fun hashes(): JSONObject = JSONObject().put("hal", sha256OrAbsent(File(HAL)))
        .put("jpegImporter", sha256OrAbsent(File(IMPORTER))).put("jpegHelper", sha256OrAbsent(File(JPEG_HELPER)))
        .put("provider", sha256OrAbsent(File(PROVIDER)))
        .put("cropTransport", sha256OrAbsent(File("/vendor/lib64/hw/.vcam-transport-camera.vphw90.so")))

    fun regularOwned(file: File, uid: Int): Boolean = runCatching {
        val stat = Os.lstat(file.absolutePath)
        stat.st_uid == uid && stat.st_nlink == 1L && stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG
    }.getOrDefault(false)
    fun sha256OrAbsent(file: File): String = runCatching { sha256(file) }.getOrDefault("unavailable")
    fun sha256(file: File): String = file.inputStream().use { input ->
        val hash = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
        while (true) { val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count) }
        hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
