package com.huyang.luciddream.notification

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.content.Context
import com.huyang.luciddream.reply.NotificationRemoteInputTransport
import com.huyang.luciddream.reply.ReplyTransport

data class CapturedNotification(
    val snapshot: NotificationSnapshot,
    val replyTransport: ReplyTransport?,
    val contentIntent: android.app.PendingIntent?,
)

object NotificationSnapshotFactory {
    fun capture(context: Context, sbn: StatusBarNotification): CapturedNotification =
        CapturedNotification(
            snapshot = from(sbn),
            replyTransport = findReplyTransport(context, sbn.notification),
            contentIntent = sbn.notification.contentIntent,
        )

    fun from(sbn: StatusBarNotification): NotificationSnapshot {
        val notification = sbn.notification
        val extras = notification.extras
        val messageBundles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }
        val messageParts = messageBundles
            .orEmpty()
            .mapNotNull { it as? Bundle }
            .map { bundle ->
                NotificationMessagePart(
                    sender = senderName(bundle),
                    text = bundle.getCharSequence(KEY_TEXT)?.toString(),
                    timestamp = bundle.getLong(KEY_TIME).takeIf { it > 0L },
                )
            }

        return NotificationSnapshot(
            key = sbn.key,
            notificationId = sbn.id,
            packageName = sbn.packageName,
            postedAt = sbn.postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            messages = messageParts,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        )
    }

    private fun senderName(bundle: Bundle): String? {
        val personName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bundle.getParcelable(KEY_SENDER_PERSON, Person::class.java)
            } else {
                @Suppress("DEPRECATION")
                bundle.getParcelable(KEY_SENDER_PERSON) as? Person
            }
            person?.name?.toString()
        } else {
            null
        }
        return personName ?: bundle.getCharSequence(KEY_SENDER)?.toString()
    }

    private fun findReplyTransport(context: Context, notification: Notification): ReplyTransport? {
        notification.actions.orEmpty().forEach { action ->
            val textInput = action.remoteInputs
                .orEmpty()
                .firstOrNull { it.allowFreeFormInput && !it.isDataOnly }
            val actionIntent = action.actionIntent
            if (textInput != null && actionIntent != null) {
                return NotificationRemoteInputTransport(
                    context = context.applicationContext,
                    pendingIntent = actionIntent,
                    remoteInput = textInput,
                )
            }
        }
        return null
    }

    private const val KEY_TEXT = "text"
    private const val KEY_TIME = "time"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"
}
