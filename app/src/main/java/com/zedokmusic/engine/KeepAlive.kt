package com.zedokmusic.engine

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.zedokmusic.MainActivity

/**
 * Keeps the app's process alive and its CPU running while long Windows work is going on (a plugin installer, the Windows desktop),
 * so the app can be sent to the background without Android freezing or killing Wine. A foreground service with a notification;
 * several reasons can be active at once and the service ends when the last one is released.
 */
object KeepAlive {
    private val reasons = linkedMapOf<String, String>()  // key -> notification text

    @Synchronized fun acquire(ctx: Context, key: String, text: String) {
        reasons[key] = text
        val app = ctx.applicationContext
        val i = Intent(app, KeepAliveService::class.java)
        runCatching { if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i) }
    }

    @Synchronized fun release(ctx: Context, key: String) {
        if (reasons.remove(key) == null) return
        val app = ctx.applicationContext
        if (reasons.isEmpty()) runCatching { app.stopService(Intent(app, KeepAliveService::class.java)) }
        else runCatching { app.startService(Intent(app, KeepAliveService::class.java)) }  // refreshes the text
    }

    @Synchronized internal fun text(): String? = reasons.values.lastOrNull()
    @Synchronized internal fun active() = reasons.isNotEmpty()
}

class KeepAliveService : Service() {
    private var wake: PowerManager.WakeLock? = null
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!KeepAlive.active()) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIF_ID, notification(KeepAlive.text() ?: "Windows is running"))
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zedokmusic:keepalive")
                .apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
        }
        return START_NOT_STICKY  // if Android does end the process, Wine dies with it: there is nothing to resume
    }

    override fun onDestroy() { runCatching { wake?.release() }; wake = null; super.onDestroy() }

    private fun notification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Windows running", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setContentTitle("ZedokMusic").setContentText(text).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true).setContentIntent(open).build()
    }

    companion object { const val CHANNEL = "keepalive"; const val NOTIF_ID = 41 }
}
