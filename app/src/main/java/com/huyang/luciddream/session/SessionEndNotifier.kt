package com.huyang.luciddream.session

import android.annotation.SuppressLint
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.huyang.luciddream.MainActivity
import com.huyang.luciddream.R
import com.huyang.luciddream.ui.navigation.Destination
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

sealed interface NotificationDeliveryResult {
    data object Shown : NotificationDeliveryResult
    data object PermissionDenied : NotificationDeliveryResult
    data object Failed : NotificationDeliveryResult
}

object SummaryNotificationPermission {
    fun isGranted(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return runtimeGranted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}

@Singleton
class SessionEndNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    @SuppressLint("MissingPermission") // Runtime permission is checked immediately above; notify is also caught.
    fun show(sessionId: Long, text: String): NotificationDeliveryResult {
        if (!SummaryNotificationPermission.isGranted(context)) {
            return NotificationDeliveryResult.PermissionDenied
        }
        return runCatching {
            createChannel()
            val openHistory = Intent(context, MainActivity::class.java).apply {
                action = ACTION_OPEN_SESSION_SUMMARY
                data = "luciddream://session/$sessionId".toUri()
                putExtra(MainActivity.EXTRA_DESTINATION, Destination.History.route)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                sessionId.hashCode(),
                openHistory,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_lucid_dream)
                .setContentTitle("托管已结束")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .build()
            NotificationManagerCompat.from(context).notify(notificationId(sessionId), notification)
            NotificationDeliveryResult.Shown
        }.getOrElse { NotificationDeliveryResult.Failed }
    }

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "托管结束总结",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "托管 Session 结束后的本地总结通知"
            },
        )
    }

    private fun notificationId(sessionId: Long): Int =
        (sessionId xor (sessionId ushr 32)).toInt() and Int.MAX_VALUE

    private companion object {
        const val CHANNEL_ID = "delegation_summary"
        const val ACTION_OPEN_SESSION_SUMMARY = "com.huyang.luciddream.OPEN_SESSION_SUMMARY"
    }
}
