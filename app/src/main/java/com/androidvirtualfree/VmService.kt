package com.androidvirtualfree

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Serviço em primeiro plano: impede que o Android físico encerre a VM enquanto ela roda. */
class VmService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var listening = false
    private val listener: (VmState) -> Unit = { s ->
        if (s.phase == Phase.STOPPED || s.phase == Phase.ERROR) stopSelf()
        else notifyState(s)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val n = build(getString(R.string.msg_preparing))
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, n)
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avf:vm").apply { acquire(12 * 60 * 60 * 1000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VmController.start(this)
        if (!listening) { listening = true; VmController.addListener(listener) }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun notifyState(s: VmState) {
        getSystemService(NotificationManager::class.java).notify(ID, build(s.message))
    }

    override fun onDestroy() {
        VmController.removeListener(listener)
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "vm"
        private const val ID = 1
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, VmService::class.java))
    }
}
