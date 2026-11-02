package com.virtualcamerapro.broker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Resume only an already configured, hash-verified guest bridge; never install one. */
class GuestBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending = goAsync()
        Thread {
            try {
                val runtime = GuestRuntime(context)
                // This checks the exact guest identity, configured journal,
                // installed redirect and original recovery copy before starting.
                runtime.installed()
                context.startForegroundService(Intent(context, BrokerService::class.java)
                    .setAction(BrokerService.ACTION_RECOVER_TRANSPORT))
            } catch (error: Throwable) {
                Log.w("VirtualCameraVMOS", "event=guest_boot_recovery_skipped reason=${error.message}")
            } finally { pending.finish() }
        }.apply { name = "VMOS-guest-boot"; start() }
    }
}
