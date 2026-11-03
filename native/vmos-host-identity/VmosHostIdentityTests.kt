package com.virtualcamerapro.broker

/** CPU-only host-layout checks; no Android root access or device modifications. */
fun main() {
    var checked = 0
    fun checkCase(name: String, block: () -> Unit) {
        try { block(); checked++ } catch (error: Throwable) { throw AssertionError(name, error) }
    }
    val legacy = "/data/app/com.vmos.virtual-fYV3H7a_Tb0+=-/lib/arm64/libylinker64.so"
    val modern = "/data/app/~~oL4-oVa7_q0==/com.vmos.virtual-fYV3H7a_Tb0+=-/lib/arm64/libylinker64.so"
    val pinned = VmosHostIdentity.LAUNCHER_SHA256
    checkCase("observed guest proc alias requires exact VMOS linker bytes") {
        check(VmosHostIdentity.isPinnedLauncher("/system/bin/app_process64",pinned,fromProcExe=true))
        check(!VmosHostIdentity.isPinnedLauncher("/system/bin/app_process64","0".repeat(64),fromProcExe=true))
        check(!VmosHostIdentity.isLauncherPath("/system/bin/app_process64"))
        check(!VmosHostIdentity.isPinnedLauncher("/system/bin/app_process32",pinned,fromProcExe=true))
    }
    checkCase("legacy installation keeps its measured path") {
        check(VmosHostIdentity.launcherPath(legacy) == legacy)
        check(VmosHostIdentity.isPinnedLauncher(legacy, pinned))
    }
    checkCase("randomized host parent is accepted without changing the binary pin") {
        check(VmosHostIdentity.launcherPath(modern) == modern)
        check(VmosHostIdentity.isPinnedLauncher(modern, pinned))
    }
    checkCase("host tokens do not become a fixed original-device identifier") {
        for (token in listOf("1", "A_z-0+==", "AnotherDevice")) {
            check(VmosHostIdentity.isPinnedLauncher("/data/app/~~$token/com.vmos.virtual-$token/lib/arm64/libylinker64.so", pinned))
        }
    }
    checkCase("deleted proc executable keeps its exact pinned descriptor identity") {
        for (path in listOf(legacy, modern)) {
            check(VmosHostIdentity.launcherPath("$path (deleted)", fromProcExe = true) == path)
            check(VmosHostIdentity.isPinnedLauncher("$path (deleted)", pinned, fromProcExe = true))
        }
    }
    checkCase("deleted suffix cannot enter an ordinary installation path") {
        check(!VmosHostIdentity.isLauncherPath("$modern (deleted)"))
    }
    checkCase("deleted marker must occur exactly once and at the end") {
        for (path in listOf("$modern (deleted) (deleted)", "$modern (deleted)/anything", "$modern(deleted)", "$modern (DELETED)")) {
            check(!VmosHostIdentity.isLauncherPath(path, fromProcExe = true))
        }
    }
    checkCase("correct layout never authorizes unknown launcher bytes") {
        check(!VmosHostIdentity.isPinnedLauncher(modern, "0".repeat(64)))
        check(!VmosHostIdentity.isPinnedLauncher(modern, pinned.uppercase()))
        check(!VmosHostIdentity.isPinnedLauncher(modern, " $pinned"))
    }
    val rejected = linkedMapOf(
        "relative path" to modern.removePrefix("/"),
        "relative data traversal" to "/data/../data/app/com.vmos.virtual-a/lib/arm64/libylinker64.so",
        "package traversal" to "/data/app/~~a/../com.vmos.virtual-a/lib/arm64/libylinker64.so",
        "embedded token traversal" to "/data/app/~~a/com.vmos.virtual-../lib/arm64/libylinker64.so",
        "extra randomized parent" to "/data/app/~~a/~~b/com.vmos.virtual-a/lib/arm64/libylinker64.so",
        "ordinary arbitrary parent" to "/data/app/other/com.vmos.virtual-a/lib/arm64/libylinker64.so",
        "empty randomized token" to "/data/app/~~/com.vmos.virtual-a/lib/arm64/libylinker64.so",
        "empty package install token" to "/data/app/~~a/com.vmos.virtual-/lib/arm64/libylinker64.so",
        "missing package install token" to "/data/app/com.vmos.virtual/lib/arm64/libylinker64.so",
        "wrong package" to "/data/app/~~a/com.vmos.pro-a/lib/arm64/libylinker64.so",
        "package prefix impersonation" to "/data/app/~~a/com.vmos.virtual.evil-a/lib/arm64/libylinker64.so",
        "wrong architecture" to modern.replace("/arm64/", "/arm/"),
        "wrong library directory" to modern.replace("/lib/", "/libs/"),
        "wrong basename" to modern.replace("libylinker64.so", "libylinker.so"),
        "library prefix impersonation" to "$modern.evil",
        "additional child" to "$modern/child",
        "redundant slash" to modern.replace("/lib/", "//lib/"),
        "trailing slash" to "$modern/",
        "leading whitespace" to " $modern",
        "trailing whitespace" to "$modern ",
        "newline token" to modern.replace("~~oL4-oVa7_q0==", "~~a\nb"),
        "nul token" to modern.replace("~~oL4-oVa7_q0==", "~~a\u0000b"),
        "unicode token" to modern.replace("~~oL4-oVa7_q0==", "~~a\u0430"),
        "percent encoded traversal" to modern.replace("~~oL4-oVa7_q0==", "~~%2e%2e"),
        "backslash path" to modern.replace('/', '\\'),
        "oversized parent component" to "/data/app/~~${"a".repeat(254)}/com.vmos.virtual-a/lib/arm64/libylinker64.so",
        "oversized package component" to "/data/app/com.vmos.virtual-${"a".repeat(256)}/lib/arm64/libylinker64.so",
        "unbounded input" to modern + "a".repeat(1024),
        "apk archive path" to "/data/app/~~a/com.vmos.virtual-a/base.apk!/lib/arm64/libylinker64.so",
        "alternate data root" to "/data_mirror/app/com.vmos.virtual-a/lib/arm64/libylinker64.so"
    )
    for ((name, path) in rejected) checkCase(name) {
        check(!VmosHostIdentity.isLauncherPath(path))
        check(!VmosHostIdentity.isLauncherPath(path, fromProcExe = true))
        check(!VmosHostIdentity.isPinnedLauncher(path, pinned, fromProcExe = true))
    }
    println("VmosHostIdentity: $checked host-layout and executable-pin checks passed")
}
