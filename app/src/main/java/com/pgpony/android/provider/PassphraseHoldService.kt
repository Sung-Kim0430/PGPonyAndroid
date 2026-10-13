// PassphraseHoldService.kt
// PGPony Android 4.7.0 (item 36): keep the provider process alive while it
// holds a passphrase for mail apps.
//
// ProviderPassphraseCache is memory only and lives in :remote_api. Nothing
// keeps that process running once the mail app unbinds, so a ROM that kills
// idle processes early (reported on a Xiaomi phone, after about ten minutes,
// with battery set to unrestricted) drops a passphrase the user asked to keep
// "until I clear it". Like OpenKeychain, PGPony runs a foreground service with
// an ongoing notification for as long as the cache holds anything; Clear in
// the notification wipes the provider's caches and stops it.
//
// Started from ProviderPassphraseActivity right after a passphrase is stored,
// while that activity is in the foreground (so the Android 12+ background
// start limits do not apply), and only for a duration longer than five
// minutes or one held until cleared or until the phone locks. It checks the
// cache every 30 seconds and stops itself once nothing is held, which covers
// a timer running out, a wrong passphrase, a lock and a clear from Settings.

package com.pgpony.android.provider

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.pgpony.android.R
import com.pgpony.android.session.SessionPolicy

class PassphraseHoldService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!ProviderPassphraseCache.isHolding()) {
                stopSelf()
            } else {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CLEAR) {
            ProviderPassphraseCache.clearAll()
            com.pgpony.android.crypto.card.CardPinCache.clear()
            com.pgpony.android.session.InAppPassphraseCache.clearAll()
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel(this)
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Refused by the system (a background start, say): nothing to keep alive.
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(check)
        handler.postDelayed(check, CHECK_INTERVAL_MS)
        // Not sticky: if the system kills the process anyway, the cache died
        // with it and there is nothing to hold.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(check)
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val clear = PendingIntent.getService(
            this, 0,
            Intent(this, PassphraseHoldService::class.java).setAction(ACTION_CLEAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.passphrase_hold_title))
            .setContentText(getString(R.string.passphrase_hold_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, getString(R.string.passphrase_hold_clear), clear)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "passphrase_hold"
        private const val NOTIFICATION_ID = 0x5047 // "PG"
        private const val CHECK_INTERVAL_MS = 30_000L
        private const val ACTION_CLEAR = "com.pgpony.android.provider.action.HOLD_CLEAR"
        /** Durations at or under this are short enough not to need the service. */
        private const val MIN_DURATION_SEC = 300

        /** Whether the current duration setting calls for the service. */
        fun wanted(): Boolean =
            SessionPolicy.isLifecycleHeld() || SessionPolicy.durationSec() > MIN_DURATION_SEC

        /** Start holding, from a foreground provider activity, when [wanted]. */
        fun startIfWanted(context: Context) {
            if (!wanted()) return
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, PassphraseHoldService::class.java))
            }
        }

        /** Stop holding (a clear from Settings reached this process). */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PassphraseHoldService::class.java)) }
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.passphrase_hold_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            context.getSystemService<NotificationManager>()?.createNotificationChannel(channel)
        }
    }
}
