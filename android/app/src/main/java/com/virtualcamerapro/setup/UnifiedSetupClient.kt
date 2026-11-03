package com.virtualcamerapro.setup

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import com.virtualcamerapro.ipc.IGuestSetup
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Called from the native module's worker; no root/camera work runs on the UI thread. */
class UnifiedSetupClient(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private val lock = Any()
    @Volatile private var endpoint: IGuestSetup? = null
    private var bound = false
    private var connected = CountDownLatch(1)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            endpoint = IGuestSetup.Stub.asInterface(service)
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            synchronized(lock) { endpoint = null; connected = CountDownLatch(1) }
        }
        override fun onBindingDied(name: ComponentName) {
            endpoint = null
            synchronized(lock) {
                if (bound) runCatching { context.unbindService(this) }
                bound = false
                connected.countDown()
            }
        }
        override fun onNullBinding(name: ComponentName) {
            synchronized(lock) {
                endpoint = null
                // Android retains a null binding until it is explicitly unbound. Leaving
                // bound=true here would make all later status requests fail permanently.
                if (bound) runCatching { context.unbindService(this) }
                bound = false
                connected.countDown()
            }
        }
    }

    private fun service(): IGuestSetup {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Setup service binding must run on the native worker thread" }
        endpoint?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        val waiting: CountDownLatch
        synchronized(lock) {
            endpoint?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            if (!bound) {
                connected = CountDownLatch(1)
                bound = context.bindService(Intent(context, UnifiedSetupService::class.java), connection, Context.BIND_AUTO_CREATE)
                check(bound) { "The integrated guest setup service could not be bound" }
            }
            waiting = connected
        }
        check(waiting.await(8, TimeUnit.SECONDS)) { "The integrated guest setup service did not connect" }
        return endpoint?.takeIf { it.asBinder().isBinderAlive }
            ?: error("The integrated guest setup service disconnected")
    }

    fun getStatus(): JSONObject = JSONObject(service().snapshot)
    fun run(action: String, approved: Boolean): JSONObject = JSONObject(service().execute(action, approved))
    fun setRecoveryPreferences(autoRecover: Boolean, autoResume: Boolean): JSONObject = run(
        JSONObject().put("action", "set_preferences").put("autoRecover", autoRecover).put("autoResume", autoResume).toString(), false)
    fun exportDiagnostics(): String = run("export_diagnostics", false).getString("diagnostics")
    override fun close() {
        synchronized(lock) {
            endpoint = null
            if (bound) runCatching { context.unbindService(connection) }
            bound = false; connected.countDown()
        }
    }
}
