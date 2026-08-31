package com.huyang.luciddream.notification

import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.dao.SafetyEventDao
import com.huyang.luciddream.data.entity.SafetyEventEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.safety.SafetyGateway
import com.huyang.luciddream.safety.SafetyResult
import com.huyang.luciddream.reply.OutboundEchoGuard
import com.huyang.luciddream.reply.ReplyTransport
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalNotificationProcessor @Inject constructor(
    private val normalizer: NotificationNormalizer,
    private val sessionDao: DelegationSessionDao,
    private val safetyEventDao: SafetyEventDao,
    private val eventRepository: AppEventRepository,
    private val safetyGateway: SafetyGateway,
    private val safeMessageHandler: SafeExternalMessageHandler,
    private val outboundEchoGuard: OutboundEchoGuard,
) {
    suspend fun process(snapshot: NotificationSnapshot, replyTransport: ReplyTransport? = null) {
        when (val result = normalizer.normalize(snapshot)) {
            is NormalizationResult.Rejected -> {
                if (result.isSupportedSource) {
                    eventRepository.record(
                        CATEGORY,
                        "微信通知未标准化：${result.reason}；未创建 Agent 任务",
                    )
                }
            }

            is NormalizationResult.Accepted -> {
                if (outboundEchoGuard.consumeIfEcho(
                        result.message.notificationKey,
                        result.message.content,
                    )
                ) {
                    eventRepository.record(
                        CATEGORY,
                        "已抑制一条 Agent 自身快捷回复产生的通知回声；未创建任务、未调用 DeepSeek",
                    )
                    return
                }
                processAccepted(result.message, replyTransport)
            }
        }
    }

    suspend fun recordListenerState(connected: Boolean) {
        eventRepository.record(
            CATEGORY,
            if (connected) "通知监听器已连接" else "通知监听器已断开",
        )
    }

    suspend fun recordFailure() {
        eventRepository.record(CATEGORY, "通知处理失败；正文未写入日志")
    }

    private suspend fun processAccepted(
        message: NormalizedExternalMessage,
        replyTransport: ReplyTransport?,
    ) {
        val activeSession = sessionDao.getActive()
        if (activeSession == null) {
            eventRepository.record(
                CATEGORY,
                "${message.sourceApp}通知已忽略：托管 OFF；正文未保存；未调用 DeepSeek",
            )
            return
        }

        when (val safety = safetyGateway.evaluate(message)) {
            is SafetyResult.Block -> {
                recordSafetyBlock(message, activeSession.id, safety.reasonCode)
                return
            }
            SafetyResult.Pass -> Unit
        }

        safeMessageHandler.handle(message, activeSession, replyTransport)
    }

    private suspend fun recordSafetyBlock(
        message: NormalizedExternalMessage,
        sessionId: Long,
        reasonCode: String,
    ) {
        val insertedId = safetyEventDao.insert(
            SafetyEventEntity(
                eventFingerprint = MessageFingerprint.safetyEvent(
                    message.notificationKey,
                    message.sourceTimestamp,
                ),
                timestamp = System.currentTimeMillis(),
                sourcePackage = message.sourcePackage,
                sourceApp = message.sourceApp,
                sender = message.sender,
                sessionId = sessionId,
                result = SafetyEventEntity.RESULT_BLOCK,
                reasonCode = reasonCode,
                deepSeekStatus = SafetyEventEntity.NOT_CALLED,
                toolStatus = SafetyEventEntity.NOT_CALLED,
                taskStatus = SafetyEventEntity.NOT_CREATED,
                memoryStatus = SafetyEventEntity.NOT_WRITTEN,
            ),
        )
        if (insertedId == -1L) return

        eventRepository.record(
            "SAFETY",
            buildString {
                append("来源：${message.sourceApp}\n")
                append("联系人：${message.sender}\n")
                append("Trust：${message.trustLevel.name}\n")
                append("Safety：BLOCK ($reasonCode)\n")
                append("DeepSeek：NOT_CALLED\n")
                append("Tool：NOT_CALLED\n")
                append("Task：NOT_CREATED\n")
                append("Agent Memory：NOT_WRITTEN\n")
                append("违规正文：NOT_LOGGED")
            },
        )
    }

    private companion object {
        const val CATEGORY = "NOTIFICATION_INGRESS"
    }
}
