package com.virtualcamerapro.setup.provisioning

import android.content.Context
import android.system.Os
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files

/** Executes the real manager against in-memory Android boundaries; never starts su or a host process. */
fun main() {
    val temporary = Files.createTempDirectory("vcam-root-manager-").toFile()
    try {
        val manager = RootAccessManager(Context(temporary))
        val probe = manager.javaClass.getDeclaredMethod("runAuthorizationProbe", String::class.java, String::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }
        fun attempt(): Throwable {
            try { probe.invoke(manager, "unused-test-executable", "unused-test-command", 1L); error("Probe unexpectedly completed") }
            catch (error: InvocationTargetException) { return error.targetException }
        }

        Os.reset(); GuestAdapterRegistry.checks = 0; GuestRootAuthorizationBridge.calls = 0
        check(attempt() is ProbeReached)
        check(GuestRootAuthorizationBridge.calls == 1 && GuestAdapterRegistry.checks == 0)
        check(Os.statCalls == 0)
        check(temporary.walkTopDown().none { it.name.startsWith("probe-") }) { "Probe file survived failure" }

        Os.reset(); Os.rejectDirectoryMode = true
        GuestAdapterRegistry.checks = 0; GuestRootAuthorizationBridge.calls = 0
        check(attempt() is ProbeReached)
        check(GuestRootAuthorizationBridge.calls == 1) { "Anonymous pipe probing must not depend on filesystem modes" }
        check(GuestAdapterRegistry.checks == 0) { "An assertion recursively invoked check(true)" }
        check(Os.statCalls == 0)
        check(temporary.walkTopDown().none { it.name.startsWith("probe-") })
        println("ROOT_ACCESS_MANAGER_TESTS_PASSED=2")
    } finally { temporary.deleteRecursively() }
}
