package com.virtualcamerapro.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Process
import com.virtualcamerapro.ipc.IGuestSetup

/** A private endpoint in the same :camera process as the integrated broker. */
class UnifiedSetupService : Service() {
    private lateinit var orchestrator: SetupOrchestrator
    @Volatile private var operationActive = false
    private fun authorize() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Setup is private to Virtual Camera Pro")
    }
    private val binder = object : IGuestSetup.Stub() {
        override fun getSnapshot(): String { authorize(); return orchestrator.getSnapshot().toString() }
        override fun execute(action: String, approved: Boolean): String {
            authorize(); return orchestrator.execute(action, approved).toString()
        }
    }
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Guest camera preparation", NotificationManager.IMPORTANCE_LOW))
        orchestrator = SetupOrchestrator(this, ::operationForeground)
    }
    override fun onBind(intent: Intent): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!operationActive) stopSelf(startId)
        return START_NOT_STICKY
    }
    private fun operationForeground(enabled: Boolean) {
        operationActive = enabled
        if (!enabled) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        // Foreground status alone does not make a bound-only service a started service.
        // Keep the approved operation alive if its last UI binding goes away mid-install.
        try {
            val keepAlive = Intent(this, UnifiedSetupService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(keepAlive) else startService(keepAlive)
            val launch = packageManager.getLaunchIntentForPackage(packageName)
            val open = launch?.let { PendingIntent.getActivity(this, 7720, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            val notification = builder.setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("Virtual Camera Pro guest setup")
                .setContentText("Preparing or verifying the guest camera; originals are preserved for recovery")
                .setOngoing(true).apply { if (open != null) setContentIntent(open) }.build()
            startForeground(NOTIFICATION, notification)
        } catch (error: Throwable) { operationActive = false; stopSelf(); throw error }
    }
    override fun onDestroy() { orchestrator.close(); super.onDestroy() }
    companion object {
        private const val CHANNEL = "virtual-camera-guest-setup"
        private const val NOTIFICATION = 7720
    }
}
