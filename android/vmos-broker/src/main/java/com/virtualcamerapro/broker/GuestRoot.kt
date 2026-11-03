package com.virtualcamerapro.broker

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Optional guest superuser access. It accepts no caller supplied shell commands. */
internal class GuestRoot(context: Context) {
    private val runtime = GuestRuntime(context)

    private fun runFixed(command: String, timeoutSeconds: Long = 6): String {
        runtime.requireGuest()
        val script = "[ \"$(/system/bin/id -u)\" = 0 ] || exit 73\n$command"
        val executable = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/bin/.ext/.su")
            .firstOrNull { File(it).isFile && File(it).canExecute() }
            ?: throw unavailable("No executable guest root implementation is available")
        val process = try { ProcessBuilder(executable, "-c", script).redirectErrorStream(true).start() }
        catch (error: Exception) { throw unavailable(error.message ?: "The guest su executable could not be started") }
        val output = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { input ->
                val bytes = CharArray(1024)
                while (true) { val read = input.read(bytes); if (read < 0) break; synchronized(output) { if (output.length < 16384) output.append(bytes, 0, minOf(read, 16384 - output.length)) } }
            } }
        }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly(); reader.join(500)
            throw unavailable("The guest superuser manager did not grant verified UID 0 within $timeoutSeconds seconds")
        }
        reader.join(500)
        val text = synchronized(output) { output.toString() }
        if (process.exitValue() != 0) throw unavailable(text.take(1500).ifBlank { "Guest su rejected the verified root command (${process.exitValue()})" })
        return text
    }
    private fun unavailable(message: String) = GuestFailure("VMOS_GUEST_ROOT_UNAVAILABLE", message,
        "Guest superuser access is required to control the guest camera provider. The bridge remains uninstalled until this is verified.")

    fun verifyProvider(): JSONObject {
        val text = runFixed("""
            echo VCAM_UID=$(/system/bin/id -u)
            echo VCAM_SERVICE=$(/system/bin/getprop init.svc.$SERVICE)
            vcam_pid=$(/system/bin/pidof $BASENAME)
            case "${'$'}vcam_pid" in ''|*[!0-9]*) exit 74 ;; esac
            echo VCAM_PID=${'$'}vcam_pid
            echo VCAM_EXE=$(/system/bin/readlink /proc/${'$'}vcam_pid/exe)
            echo VCAM_CMD=$(/system/bin/tr '\000' '\n' < /proc/${'$'}vcam_pid/cmdline | /system/bin/head -1)
            echo VCAM_BINARY_SHA=$(/system/bin/sha256sum $EXECUTABLE | /system/bin/cut -d' ' -f1)
            echo VCAM_EXE_SHA=$(/system/bin/sha256sum /proc/${'$'}vcam_pid/exe | /system/bin/cut -d' ' -f1)
        """.trimIndent(), 30)
        val fields = text.lineSequence().filter { it.startsWith("VCAM_") && '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        if (fields["VCAM_UID"] != "0") throw unavailable("The guest command did not return actual UID 0")
        val pid = fields["VCAM_PID"]?.toIntOrNull()?.takeIf { it > 1 } ?: throw unavailable("The exact guest camera provider PID is unavailable")
        // VMOS executes this inspected guest binary through its own linker. The
        // kernel's /proc/exe therefore identifies the launcher, not the provider.
        val direct = fields["VCAM_EXE"] == EXECUTABLE && fields["VCAM_EXE_SHA"] == PROVIDER_SHA
        val virtualized = VmosHostIdentity.isPinnedLauncher(fields["VCAM_EXE"].orEmpty(), fields["VCAM_EXE_SHA"].orEmpty(),
            fromProcExe = true)
        if ((!direct && !virtualized) || fields["VCAM_CMD"] != EXECUTABLE ||
            fields["VCAM_BINARY_SHA"] != PROVIDER_SHA || fields["VCAM_SERVICE"] != "running")
            throw GuestFailure("VMOS_PROVIDER_RESTART_UNVERIFIED", "The guest provider process or init-service identity does not match the measured restart target",
                "The original HAL remains unchanged until the exact guest provider and its recovery restart can be verified.")
        return JSONObject().put("uid", 0).put("service", SERVICE).put("pid", pid).put("executable", EXECUTABLE)
            .put("launcher", fields["VCAM_EXE"]).put("providerSha256", PROVIDER_SHA)
    }

    /** Called only after exact identity checks and a recoverable, verified HAL write. */
    fun restartProvider() {
        val before = verifyProvider().getInt("pid")
        runFixed("/system/bin/setprop ctl.restart $SERVICE")
        val deadline = SystemClock.elapsedRealtime() + 12000
        var last: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            try { if (verifyProvider().getInt("pid") != before) return }
            catch (error: Throwable) { last = error }
            Thread.sleep(200)
        }
        throw GuestFailure("VMOS_PROVIDER_RESTART_FAILED", last?.message ?: "The exact guest provider did not restart with a new verified PID",
            "Restore the preserved guest HAL and restart the VMOS guest before attempting activation.")
    }
    fun preserveHalAttributes(file: File, uid: Int, gid: Int, mode: Int) {
        runtime.requireGuest()
        require(file.parentFile?.canonicalPath == "/vendor/lib64/hw" &&
            file.name.startsWith(".camera.vphw90.so.") && file.name.endsWith(".tmp") &&
            file.name.matches(Regex("[A-Za-z0-9.-]+"))) { "Root metadata operation is restricted to an owned staged guest HAL replacement" }
        require(uid in 0..1999999 && gid in 0..1999999 && mode in 0..4095)
        runFixed("/system/bin/chown $uid:$gid '${file.absolutePath}' && /system/bin/chmod ${mode.toString(8)} '${file.absolutePath}'")
    }
    companion object {
        private const val SERVICE = "vendor.camera-provider-2-4"
        private const val BASENAME = "android.hardware.camera.provider@2.4-service"
        private const val EXECUTABLE = "/vendor/bin/hw/$BASENAME"
        private const val PROVIDER_SHA = "fe58ac53d066187a0fd499a38354101ccff9cc0d3379164bd9f36b5b631d7e87"
    }
}
