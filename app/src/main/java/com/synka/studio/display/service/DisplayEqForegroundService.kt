package com.synka.studio.display.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.synka.studio.display.engine.DisplayEqEngine
import com.synka.studio.display.model.DisplayEqTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DisplayEqForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "synka_display_eq_channel"
        private const val NOTIFICATION_ID = 4207
        private const val ACTION_DISABLE = "com.synka.studio.display.ACTION_DISABLE"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) {
                DisplayEqEngine.getInstance(applicationContext).reapplyNow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val nm = getSystemService(NotificationManager::class.java)
        serviceScope.launch {
            DisplayEqEngine.getInstance(applicationContext).activeTier.collect { tier ->
                if (tier != DisplayEqTier.INACTIVE) {
                    nm?.notify(NOTIFICATION_ID, buildNotification(tier))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISABLE) {
            DisplayEqEngine.getInstance(applicationContext).updateState { it.copy(enabled = false) }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

        val currentTier = DisplayEqEngine.getInstance(applicationContext).activeTier.value
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(currentTier), fgsType)
        DisplayEqEngine.getInstance(applicationContext).reapplyNow()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Synka Display EQ",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps Synka Studio system-wide display filter active"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(tier: DisplayEqTier): Notification {
        val disableIntent = Intent(this, DisplayEqForegroundService::class.java).apply {
            action = ACTION_DISABLE
        }
        val disablePi = PendingIntent.getService(
            this,
            0,
            disableIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (tier == DisplayEqTier.INACTIVE) {
            "Active"
        } else {
            "Mode: ${tier.label}"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Synka Display EQ Active")
            .setContentText(subtitle)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Turn Off", disablePi)
            .build()
    }
}
