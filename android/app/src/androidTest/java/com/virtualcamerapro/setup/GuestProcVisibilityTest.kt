package com.virtualcamerapro.setup

import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only device diagnostic for VMOS's ordinary-UID proc namespace. */
@RunWith(AndroidJUnit4::class)
class GuestProcVisibilityTest {
    @Test fun recordOrdinaryApplicationProcVisibility() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = JSONObject().put("uid", Process.myUid()).put("pid", Process.myPid())
        val paths = listOf("/proc/self/net/unix", "/proc/${Process.myPid()}/net/unix", "/proc/net/unix",
            "/proc/self/status", "/proc/self/stat", "/proc/self/cmdline")
        val reads = JSONArray()
        for (path in paths) {
            val result = JSONObject().put("path", path)
            try {
                val text = File(path).inputStream().use { stream ->
                    val bytes = ByteArray(1_048_577)
                    var used = 0
                    while (used < bytes.size) { val count = stream.read(bytes, used, bytes.size - used); if (count < 0) break; used += count }
                    check(used <= 1_048_576)
                    bytes.copyOf(used).toString(Charsets.UTF_8)
                }
                result.put("readable", true).put("characters", text.length)
                if (path.endsWith("/unix")) result.put("rootRequestEndpoints", text.lineSequence().count { it.contains("com.koushikdutta.superuser/cache/.socket") })
                else result.put("sample", text.take(2048))
            } catch (error: Throwable) { result.put("readable", false).put("error", error.toString()) }
            reads.put(result)
        }
        report.put("reads", reads)
        report.put("ownExeLink", runCatching { Os.readlink("/proc/self/exe") }.getOrElse { it.toString() })
        report.put("ownExeHash", runCatching { com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry.sha256(File("/proc/self/exe")) }.getOrElse { it.toString() })
        report.put("ownExeStat", runCatching {
            val value = Os.stat("/proc/self/exe"); JSONObject().put("dev", value.st_dev).put("ino", value.st_ino).put("mode", value.st_mode)
        }.getOrElse { it.toString() })
        val processes = JSONArray()
        val entries = File("/proc").listFiles()
        report.put("listedProcessEntries", entries?.size ?: -1)
        for (entry in entries.orEmpty()) {
            if (entry.name.toIntOrNull() == null) continue
            val command = runCatching { File(entry, "cmdline").readText().take(4096) }.getOrNull() ?: continue
            if (!command.contains("daemonsu") && !command.contains("/system/xbin/su")) continue
            val process = JSONObject().put("pid", entry.name).put("command", command.replace('\u0000', ' '))
            for (relative in listOf("status", "stat")) process.put(relative,
                runCatching { File(entry, relative).readText().take(4096) }.getOrElse { it.toString() })
            for (relative in listOf("exe", "fd/1")) process.put(relative,
                runCatching { Os.readlink(File(entry, relative).absolutePath) }.getOrElse { it.toString() })
            processes.put(process)
        }
        report.put("rootProcesses", processes)
        val direct = JSONArray()
        for (pid in InstrumentationRegistry.getArguments().getString("processIds", "").split(',').mapNotNull { it.toIntOrNull() }.filter { it > 1 }) {
            val item = JSONObject().put("pid", pid)
            for (relative in listOf("status", "stat", "cmdline")) item.put(relative,
                runCatching { File("/proc/$pid/$relative").readText().take(4096) }.getOrElse { it.toString() })
            for (relative in listOf("exe", "fd/1")) item.put(relative,
                runCatching { Os.readlink("/proc/$pid/$relative") }.getOrElse { it.toString() })
            for (relative in listOf("status", "stat", "cmdline", "fd/1")) item.put("os/$relative", runCatching {
                val descriptor = Os.open("/proc/$pid/$relative", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                try { val bytes = ByteArray(4096); val count = Os.read(descriptor, bytes, 0, bytes.size); bytes.copyOf(count).toString(Charsets.UTF_8) }
                finally { Os.close(descriptor) }
            }.getOrElse { it.toString() })
            for (relative in listOf("", "/fd/1")) item.put("osStat$relative", runCatching {
                val value = Os.stat("/proc/$pid$relative")
                JSONObject().put("uid", value.st_uid).put("dev", value.st_dev).put("ino", value.st_ino).put("mode", value.st_mode)
            }.getOrElse { it.toString() })
            item.put("descriptors", File("/proc/$pid/fd").list()?.toList())
            val links = JSONObject()
            for (number in 0..15) links.put(number.toString(), runCatching { Os.readlink("/proc/$pid/fd/$number") }.getOrElse { it.toString() })
            item.put("fdLinks", links)
            item.put("exeHash", runCatching { com.virtualcamerapro.setup.provisioning.GuestAdapterRegistry.sha256(File("/proc/$pid/exe")) }.getOrElse { it.toString() })
            item.put("procDirectoryOpen", runCatching {
                val descriptor = Os.open("/proc/$pid", OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                try { val value = Os.fstat(descriptor); JSONObject().put("dev", value.st_dev).put("ino", value.st_ino).put("mode", value.st_mode) }
                finally { Os.close(descriptor) }
            }.getOrElse { it.toString() })
            item.put("exeStat", runCatching {
                val value = Os.stat("/proc/$pid/exe"); JSONObject().put("dev", value.st_dev).put("ino", value.st_ino).put("mode", value.st_mode)
            }.getOrElse { it.toString() })
            direct.put(item)
        }
        report.put("directProcessReads", direct)
        File(context.getExternalFilesDir(null), "guest-proc-visibility.json").writeText(report.toString(2))
    }
}
