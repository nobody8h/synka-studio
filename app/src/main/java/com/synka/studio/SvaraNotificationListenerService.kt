package com.synka.studio

import android.app.Activity
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class SvaraNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"

        var isConnected: Boolean by mutableStateOf(false)
            private set

        var activeMediaPackages: Set<String> by mutableStateOf(emptySet())
            private set

        fun isNotificationAccessGranted(context: Context): Boolean {
            val expected = ComponentName(
                context.applicationContext,
                SvaraNotificationListenerService::class.java
            )
            val flat = Settings.Secure.getString(
                context.contentResolver,
                ENABLED_NOTIFICATION_LISTENERS
            ) ?: return false

            return flat.split(':').any { entry ->
                ComponentName.unflattenFromString(entry) == expected
            }
        }

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            runCatching { context.startActivity(intent) }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        refreshActiveMediaNotifications()
        SvaraGlobalService.onNotificationSessionHint(applicationContext)
    }

    override fun onListenerDisconnected() {
        isConnected = false
        activeMediaPackages = emptySet()
        runCatching {
            requestRebind(ComponentName(this, SvaraNotificationListenerService::class.java))
        }
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (isMediaNotification(sbn)) {
            refreshActiveMediaNotifications()
            SvaraGlobalService.onNotificationSessionHint(applicationContext)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (activeMediaPackages.contains(sbn.packageName)) {
            refreshActiveMediaNotifications()
            SvaraGlobalService.onNotificationSessionHint(applicationContext)
        }
    }

    private fun refreshActiveMediaNotifications() {
        val pkgs = runCatching {
            activeNotifications
                ?.filter { isMediaNotification(it) && it.packageName != packageName }
                ?.map { it.packageName }
                ?.toSet()
                ?: emptySet()
        }.getOrDefault(emptySet())
        activeMediaPackages = pkgs
    }

    private fun isMediaNotification(sbn: StatusBarNotification): Boolean {
        val extras = sbn.notification?.extras ?: return false
        return extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            sbn.notification.category == Notification.CATEGORY_TRANSPORT
    }
}
