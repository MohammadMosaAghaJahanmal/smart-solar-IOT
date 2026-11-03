package com.virtualcamerapro.setup.provisioning

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class GuestProvisioningException(val code: String, message: String, val recovery: String) : IllegalStateException(message)

/** Guest su discovery. Inspection never invokes su or opens an authorization dialog. */
class RootAccessManager(private val context: Context) {
    private val candidates = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/bin/.ext/.su")
    @Volatile private var verifiedExecutable: String? = null
    @Volatile private var bridgeReport: JSONObject? = null

    fun check(requestAuthorization: Boolean = false): JSONObject {
        val available = candidates.filter { File(it).isFile && File(it).canExecute() }
        val report = JSONObject().put("available", available.isNotEmpty()).put("verified", false)
            .put("authorizationRequested", requestAuthorization).put("candidates", JSONArray(available))
            .put("granted", context.checkSelfPermission("android.permission.ACCESS_SUPERUSER") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            .put("requiresAuthorization", !requestAuthorization).put("checkedAt", System.currentTimeMillis())
            .put("rootManagerPackage", "com.koushikdutta.superuser")
            .put("canOpenRootManager", context.packageManager.getLaunchIntentForPackage("com.koushikdutta.superuser") != null)
            .put("rootCompatibilityRequired", !File("/system/bin/app_process32").exists() && File("/system/bin/app_process64").isFile)
            .put("rootAuthorizationBridge", GuestRootAuthorizationBridge.capability(context))
        if (!requestAuthorization) return report.put("code", if (available.isEmpty()) "GUEST_ROOT_MISSING" else "GUEST_ROOT_NOT_CHECKED")
        GuestAdapterRegistry.requireSupportedGuest()
        var failure = "No executable guest su implementation was discovered"
        for (candidate in available) {
            try {
                val output = run(candidate, "printf 'VCAM_GUEST_UID='; /system/bin/id -u", 35, authorizationProbe = true)
                bridgeReport?.let { report.put("authorizationBridge", it) }
                if (output.lineSequence().any { it.trim() == "VCAM_GUEST_UID=0" }) {
                    verifiedExecutable = candidate
                    return report.put("verified", true).put("uid", 0).put("executable", candidate)
                        .put("requiresAuthorization", false)
                        .put("checkedElapsedRealtimeMs", SystemClock.elapsedRealtime()).put("code", "GUEST_ROOT_VERIFIED")
                }
                failure = output.take(1000)
            } catch (error: Exception) {
                Log.e(TAG, "Guest root authorization probe failed", error)
                failure = error.message ?: "Guest su authorization failed"
                bridgeReport?.let { report.put("authorizationBridge", it) }
            }
        }
        val missingLauncher = report.optBoolean("rootCompatibilityRequired") && bridgeReport?.optBoolean("launched") != true &&
            runCatching { GuestAdapterRegistry.sha256(File("/system/xbin/su")) == INSPECTED_SU_SHA }.getOrDefault(false)
        return report.put("code", if (missingLauncher) "VMOS_ROOT_BOOTSTRAP_MISSING" else "ROOT_MANAGER_AUTH_REQUIRED")
            .put("requiresAuthorization", true)
            .put("message", if (missingLauncher) "VMOS root is installed, but its ARM64 permission launcher is missing. The guest root service could not grant this application actual UID 0." else failure)
            .put("recovery", if (missingLauncher)
                "Repair or update root in the VMOS guest, or use a compatible prepared guest. This root build cannot show a new application's permission prompt until its launcher is repaired. Physical-phone root is not required."
                else "Enable root inside VMOS and grant Virtual Camera Pro Superuser permission. If its policy already appears in Superuser, change that application's policy to Allow, then check again. Physical-phone root is not required.")
    }

    /** Explicit user action; opens only the already installed VMOS root manager. */
    fun openRootManager(): Boolean {
        GuestAdapterRegistry.requireSupportedGuest()
        val intent = context.packageManager.getLaunchIntentForPackage("com.koushikdutta.superuser") ?: return false
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    /** Internal fixed-operation boundary; no exported IPC accepts shell text. */
    internal fun approved(command: String, timeoutSeconds: Long = 45): String {
        GuestAdapterRegistry.requireSupportedGuest()
        if (verifiedExecutable == null) {
            val result = this.check(true)
            if (!result.optBoolean("verified")) throw GuestProvisioningException("GUEST_ROOT_UNAVAILABLE",
                result.optString("message"), result.optString("recovery"))
        }
        return run(requireNotNull(verifiedExecutable), "[ \"$(/system/bin/id -u)\" = 0 ] || exit 73\n$command", timeoutSeconds)
    }

    private fun run(executable: String, command: String, timeoutSeconds: Long, authorizationProbe: Boolean = false): String {
        if (authorizationProbe) return runAuthorizationProbe(executable, command, timeoutSeconds)
        // The pinned daemon rejects an individual argv string over PATH_MAX.
        // Send larger fixed internal operations through an anonymous stdin pipe,
        // avoiding both that limit and this guest's unreliable private-file DAC.
        val bytes = command.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 262144) { "Guest operation exceeds its internal bound" }
        val pipedScript = bytes.size > 3000
        val process = ProcessBuilder(executable, "-c", if (pipedScript) "/system/bin/sh" else command)
            .redirectErrorStream(true).start()
        val text = StringBuilder()
        val reader = Thread {
            runCatching { process.inputStream.bufferedReader().use { input ->
                val bytes = CharArray(2048)
                while (true) {
                    val count = input.read(bytes)
                    if (count < 0) break
                    synchronized(text) { if (text.length < 65536) text.append(bytes, 0, minOf(count, 65536 - text.length)) }
                }
            } }
        }.apply { isDaemon = true; start() }
        val inputFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val writer = if (pipedScript) Thread {
            try { process.outputStream.use { stream -> stream.write(bytes); stream.write('\n'.code); stream.flush() } }
            catch (error: Throwable) { inputFailure.set(error) }
        }.apply { name = "VMOS-approved-operation-input"; isDaemon = true; start() } else null
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly(); reader.join(500); writer?.join(500)
            throw IllegalStateException("Guest root authorization or operation exceeded $timeoutSeconds seconds")
        }
        reader.join(500); writer?.join(500)
        val output = synchronized(text) { text.toString() }
        kotlin.check(process.exitValue() == 0) { output.takeLast(3000).ifBlank { "Guest root operation failed (${process.exitValue()})" } }
        inputFailure.get()?.let { throw IllegalStateException("Guest operation input could not be delivered", it) }
        return output
    }

    private fun runAuthorizationProbe(executable: String, command: String, timeoutSeconds: Long): String {
        // This guest does not preserve ordinary-file DAC between virtual UIDs.
        // Anonymous ProcessBuilder stdout has no discoverable pathname; device
        // probes establish that foreign ordinary UIDs cannot open its proc FD.
        var bridge: GuestRootAuthorizationBridge? = null
        var process: java.lang.Process? = null
        var reader: Thread? = null
        val output = StringBuilder()
        var overflow = false
        try {
            bridge = GuestRootAuthorizationBridge.beforeProbe(context, executable, command)
            process = ProcessBuilder(executable, "-c", command).redirectErrorStream(true).start()
            val current = process
            reader = Thread {
                runCatching { current.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(output) {
                            val keep = minOf(count, 65536 - output.length)
                            if (keep < count) overflow = true
                            if (keep > 0) output.append(buffer, 0, keep)
                        }
                    }
                } }
            }.apply { name = "VMOS-root-probe-output"; isDaemon = true; start() }
            bridge?.watch(current)
            if (!current.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                current.destroyForcibly()
                throw IllegalStateException("Guest root authorization exceeded $timeoutSeconds seconds")
            }
            reader.join(500)
            val text = synchronized(output) {
                kotlin.check(!overflow) { "Root authorization output exceeded its bound" }
                output.toString()
            }
            kotlin.check(current.exitValue() == 0) { text.takeLast(3000).ifBlank { "Guest root authorization was rejected (${current.exitValue()})" } }
            return text
        } catch (error: Throwable) {
            Log.e(TAG, "Guest root authorization failed before completion", error)
            throw error
        } finally {
            if (process?.isAlive == true) process.destroyForcibly()
            bridge?.close(); bridgeReport = bridge?.report()
            runCatching { process?.inputStream?.close() }
            reader?.join(500)
        }
    }
    internal companion object {
        private const val TAG = "VcamGuestRoot"
        private const val INSPECTED_SU_SHA = "27dc135ac7e931415216a96a28100fcbf396e3e0fbaeb036d810fdeaa57a554a"
        fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    }
}
