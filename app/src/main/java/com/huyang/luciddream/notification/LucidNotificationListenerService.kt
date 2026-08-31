package com.huyang.luciddream.notification

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import com.huyang.luciddream.accessibility.AccessibilityReplyCoordinator
import com.huyang.luciddream.accessibility.WechatAccessibilityReplyTransport
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LucidNotificationListenerService : NotificationListenerService() {
    @Inject lateinit var processor: ExternalNotificationProcessor
    @Inject lateinit var connectionState: NotificationListenerConnectionState
    @Inject lateinit var accessibilityReplyCoordinator: AccessibilityReplyCoordinator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connectionState.update(true)
        serviceScope.launch { processor.recordListenerState(connected = true) }
    }

    override fun onListenerDisconnected() {
        connectionState.update(false)
        serviceScope.launch { processor.recordListenerState(connected = false) }
        super.onListenerDisconnected()
        requestRebind(ComponentName(this, LucidNotificationListenerService::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val captured = runCatching { NotificationSnapshotFactory.capture(this, sbn) }
            .getOrElse {
                serviceScope.launch { processor.recordFailure() }
                return
            }
        serviceScope.launch {
            val transport = captured.replyTransport ?: captured.contentIntent?.let { intent ->
                if (sbn.packageName == WECHAT_PACKAGE) {
                    WechatAccessibilityReplyTransport(
                        coordinator = accessibilityReplyCoordinator,
                        pendingIntent = intent,
                        notificationKey = captured.snapshot.key,
                        expectedSender = WechatNotificationText
                            .latestSender(captured.snapshot)
                            .orEmpty(),
                        expectedContent = WechatNotificationText
                            .latestContent(captured.snapshot)
                            .orEmpty(),
                    )
                } else {
                    null
                }
            }
            runCatching { processor.process(captured.snapshot, transport) }
                .onFailure { processor.recordFailure() }
        }
    }

    override fun onDestroy() {
        connectionState.update(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
