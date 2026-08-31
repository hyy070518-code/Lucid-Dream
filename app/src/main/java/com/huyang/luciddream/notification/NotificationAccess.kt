package com.huyang.luciddream.notification

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

object NotificationAccess {
    fun isGranted(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    fun requestRebind(context: Context) {
        NotificationListenerService.requestRebind(
            ComponentName(context, LucidNotificationListenerService::class.java),
        )
    }
}
