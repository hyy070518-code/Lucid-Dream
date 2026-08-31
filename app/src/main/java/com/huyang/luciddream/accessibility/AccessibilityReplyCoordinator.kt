package com.huyang.luciddream.accessibility

import android.app.PendingIntent
import com.huyang.luciddream.reply.ReplyTransport
import com.huyang.luciddream.reply.ReplyTransportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

data class WechatReplyTask(
    val pendingIntent: PendingIntent,
    val notificationKey: String,
    val expectedSender: String,
    val expectedContent: String,
    val reply: String,
    val sessionId: Long,
    val createdAt: Long = System.currentTimeMillis(),
)

@Singleton
class AccessibilityReplyCoordinator @Inject constructor() {
    private val mutex = Mutex()
    @Volatile private var service: LucidAccessibilityService? = null

    fun attach(service: LucidAccessibilityService) {
        this.service = service
    }

    @Synchronized
    fun detach(service: LucidAccessibilityService): Boolean {
        if (this.service !== service) return false
        this.service = null
        return true
    }

    suspend fun execute(task: WechatReplyTask): ReplyTransportResult = mutex.withLock {
        val activeService = service ?: return@withLock ReplyTransportResult.Rejected(
            status = "ACCESSIBILITY_NOT_ENABLED",
            reason = "Lucid Dream 无障碍服务未开启或不在线",
        )
        withTimeoutOrNull(EXECUTION_TIMEOUT_MS) { activeService.execute(task) }
            ?: ReplyTransportResult.Rejected(
                status = "UI_TIMEOUT",
                reason = "微信界面操作超时，已停止发送",
            )
    }

    private companion object {
        const val EXECUTION_TIMEOUT_MS = 30_000L
    }
}

class WechatAccessibilityReplyTransport(
    private val coordinator: AccessibilityReplyCoordinator,
    private val pendingIntent: PendingIntent,
    private val notificationKey: String,
    private val expectedSender: String,
    private val expectedContent: String,
) : ReplyTransport {
    override val type: String = "WECHAT_ACCESSIBILITY"

    override suspend fun dispatch(text: String, sessionId: Long): ReplyTransportResult =
        coordinator.execute(
            WechatReplyTask(
                pendingIntent = pendingIntent,
                notificationKey = notificationKey,
                expectedSender = expectedSender,
                expectedContent = expectedContent,
                reply = text,
                sessionId = sessionId,
            ),
        )
}
