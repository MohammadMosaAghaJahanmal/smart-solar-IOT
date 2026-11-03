package com.virtualcamerapro.broker

/**
 * Recognizes the installation layouts used by the same pinned VMOS launcher.
 * A host install-directory token is not an executable identity: callers still
 * verify its bytes and the guest process UID, argv and lifetime independently.
 * This parser is deliberately lexical; it never follows a caller supplied path.
 */
object VmosHostIdentity {
    const val LAUNCHER_SHA256 = "d3806cf0d56869b4fed7f0f8674498d4e6d4e2e7011903ce3380a21e6c1168a4"
    private const val PACKAGE_PREFIX = "com.vmos.virtual-"
    private const val DELETED_SUFFIX = " (deleted)"
    private val token = Regex("[A-Za-z0-9_=+-]+")

    /** Returns the exact normalized path, or null for an unrecognized layout. */
    fun launcherPath(value: String, fromProcExe: Boolean = false): String? {
        if (value.length !in 1..1024) return null
        val path = if (fromProcExe && value.endsWith(DELETED_SUFFIX)) value.removeSuffix(DELETED_SUFFIX) else value
        // On the measured Android 13 host, VMOS rewrites proc readlink results
        // to this guest alias. isPinnedLauncher still requires the exact linker
        // bytes; this never authorizes a real app_process executable by name.
        if (fromProcExe && path == "/system/bin/app_process64") return path
        val parts = path.split('/')
        if (parts.size !in 7..8 || parts[0].isNotEmpty() || parts[1] != "data" || parts[2] != "app") return null
        val packageIndex = if (parts.size == 8) {
            val randomizedParent = parts[3]
            if (!randomizedParent.startsWith("~~") || randomizedParent.length > 255 ||
                !validToken(randomizedParent.removePrefix("~~"))) return null
            4
        } else 3
        val installedPackage = parts[packageIndex]
        if (!installedPackage.startsWith(PACKAGE_PREFIX) || installedPackage.length > 255 ||
            !validToken(installedPackage.removePrefix(PACKAGE_PREFIX))) return null
        if (parts[packageIndex + 1] != "lib" || parts[packageIndex + 2] != "arm64" ||
            parts[packageIndex + 3] != "libylinker64.so") return null
        return path
    }

    fun isLauncherPath(value: String, fromProcExe: Boolean = false): Boolean = launcherPath(value, fromProcExe) != null

    fun isPinnedLauncher(value: String, sha256: String, fromProcExe: Boolean = false): Boolean =
        sha256 == LAUNCHER_SHA256 && isLauncherPath(value, fromProcExe)

    private fun validToken(value: String): Boolean = value.isNotEmpty() && token.matches(value)
}
