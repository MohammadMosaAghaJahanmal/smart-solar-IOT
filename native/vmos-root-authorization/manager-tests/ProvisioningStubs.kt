package com.virtualcamerapro.setup.provisioning

import android.content.Context
import org.json.JSONObject
import java.io.File

object GuestAdapterRegistry {
    var checks = 0
    fun requireSupportedGuest() { checks++ }
    fun sha256(file: File): String = "unavailable"
}
class ProbeReached : IllegalStateException("Expected host-only probe boundary")
class GuestRootAuthorizationBridge : AutoCloseable {
    fun watch(process: java.lang.Process) = Unit
    fun report(): JSONObject = JSONObject()
    override fun close() = Unit
    companion object {
        var calls = 0
        fun capability(context: Context): JSONObject = JSONObject()
        fun beforeProbe(context: Context, executable: String, command: String): GuestRootAuthorizationBridge? {
            calls++
            check(command == "unused-test-command" && executable == "unused-test-executable")
            throw ProbeReached()
        }
    }
}
