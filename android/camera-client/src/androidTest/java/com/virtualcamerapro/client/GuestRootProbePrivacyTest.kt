package com.virtualcamerapro.client

import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device evidence for the guest's file and anonymous descriptor boundaries. */
@RunWith(AndroidJUnit4::class)
class GuestRootProbePrivacyTest {
    @Test fun anotherApplicationCannotReadOrDuplicateRootProbeOutput() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        assertEquals("com.virtualcamerapro.client", context.packageName)
        assertTrue(Process.myUid() >= 10000)
        assertNotEquals(context.packageManager.getApplicationInfo("com.virtualcamerapro", 0).uid, Process.myUid())
        val pid = requireNotNull(args.getString("probePid")).toInt().also { require(it > 1) }
        val path = args.getString("probePath")
        val anonymous = args.getString("anonymousPipe") == "true"
        if (anonymous) assertTrue(Os.readlink("/proc/$pid/fd/1").matches(Regex("pipe:\\[[0-9]+]")))
        else require(requireNotNull(path).matches(Regex("/data/(?:data|user/0)/com\\.virtualcamerapro/files/root-authorization/probe-[0-9]+\\.log")))
        val results = JSONArray()
        var allDenied = true
        val descriptors = mutableListOf("/proc/$pid/fd/1")
        if (anonymous) {
            val readerPid = requireNotNull(args.getString("readerPid")).toInt().also { require(it > 1) }
            val readerFd = requireNotNull(args.getString("readerFd")).toInt().also { require(it >= 0) }
            val reader = "/proc/$readerPid/fd/$readerFd"
            assertEquals(Os.readlink("/proc/$pid/fd/1"), Os.readlink(reader))
            descriptors.add(reader)
        }
        for (target in (if (anonymous) emptyList() else listOf(requireNotNull(path))) + descriptors) {
            val item = JSONObject().put("path", target)
            for ((accessName, flags) in listOf("read" to OsConstants.O_RDONLY, "write" to OsConstants.O_WRONLY)) {
                try {
                    val fd = Os.open(target, flags or OsConstants.O_NONBLOCK or OsConstants.O_CLOEXEC, 0)
                    Os.close(fd)
                    item.put("${accessName}Accessible", true)
                    allDenied = false
                } catch (error: ErrnoException) {
                    item.put("${accessName}Errno", error.errno)
                    assertEquals("The live private capability must be denied, not simply missing", OsConstants.EACCES, error.errno)
                }
            }
            try { File(target).inputStream().use { }; item.put("javaReadable", true); allDenied = false }
            catch (error: java.io.FileNotFoundException) { item.put("javaDenied", true) }
            results.put(item)
        }
        File(context.getExternalFilesDir(null), "root-probe-privacy.json").writeText(JSONObject()
            .put("passed", allDenied).put("uid", Process.myUid()).put("pid", Process.myPid())
            .put("kind", if (anonymous) "anonymousPipe" else "regularFile")
            .put("probePid", pid).put("checks", results).toString(2))
        assertTrue("Guest does not preserve the private regular-file boundary; inspect the saved checks", allDenied)
    }
}
