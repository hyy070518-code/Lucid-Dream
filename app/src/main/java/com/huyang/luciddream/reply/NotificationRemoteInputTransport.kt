package com.huyang.luciddream.reply

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

class NotificationRemoteInputTransport(
    private val context: Context,
    private val pendingIntent: PendingIntent,
    private val remoteInput: RemoteInput,
) : ReplyTransport {
    override val type: String = "ANDROID_REMOTE_INPUT"

    override suspend fun dispatch(text: String, sessionId: Long): ReplyTransportResult = runCatching {
        val fillInIntent = Intent()
        val results = Bundle().apply {
            putCharSequence(remoteInput.resultKey, text)
        }
        RemoteInput.addResultsToIntent(arrayOf(remoteInput), fillInIntent, results)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            RemoteInput.setResultsSource(fillInIntent, RemoteInput.SOURCE_FREE_FORM_INPUT)
        }
        pendingIntent.send(context, 0, fillInIntent)
        ReplyTransportResult.Dispatched
    }.getOrElse { error ->
        ReplyTransportResult.Failed(
            when (error) {
                is PendingIntent.CanceledException -> "微信快捷回复入口已失效"
                is SecurityException -> "系统拒绝发送快捷回复"
                else -> "快捷回复分发失败"
            },
        )
    }
}
