package com.huyang.luciddream.notification

import com.huyang.luciddream.agent.AgentAction
import com.huyang.luciddream.agent.DelegationDecisionGenerator
import com.huyang.luciddream.agent.DelegationAgentFailureStage
import com.huyang.luciddream.agent.DelegationAgentResult
import com.huyang.luciddream.data.dao.ExternalMessageDao
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.data.entity.ExternalMessageEntity
import com.huyang.luciddream.data.entity.ExternalAgentDecisionEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.data.repository.DelegationConversationStore
import com.huyang.luciddream.policy.BudgetStatus
import com.huyang.luciddream.policy.ContactIdentity
import com.huyang.luciddream.policy.ContactIdentityResolver
import com.huyang.luciddream.policy.PermitResult
import com.huyang.luciddream.policy.ReplyBudgetManager
import com.huyang.luciddream.policy.ReplyBudgetPermit
import com.huyang.luciddream.reply.OutboundEchoGuard
import com.huyang.luciddream.reply.ReplyDeliveryAuthorization
import com.huyang.luciddream.reply.ReplyDeliveryContext
import com.huyang.luciddream.reply.ReplyDeliveryPolicy
import com.huyang.luciddream.reply.ReplyTransport
import com.huyang.luciddream.reply.ReplyTransportResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class Phase6ExternalMessageHandler @Inject constructor(
    private val externalMessageDao: ExternalMessageDao,
    private val contactIdentityResolver: ContactIdentityResolver,
    private val replyBudgetManager: ReplyBudgetManager,
    private val agentEngine: DelegationDecisionGenerator,
    private val conversationRepository: DelegationConversationStore,
    private val eventRepository: AppEventRepository,
    private val replyDeliveryPolicy: ReplyDeliveryPolicy,
    private val outboundEchoGuard: OutboundEchoGuard,
    private val delegationSessionDao: DelegationSessionDao,
) : SafeExternalMessageHandler {
    /** Keeps conversation context, budget decisions, and reply dispatch deterministic. */
    private val processingMutex = Mutex()

    override suspend fun handle(
        message: NormalizedExternalMessage,
        activeSession: DelegationSessionEntity,
        replyTransport: ReplyTransport?,
    ) = processingMutex.withLock {
        val contact = contactIdentityResolver.resolve(message)
        val evaluatedBudget = replyBudgetManager.evaluate(
            sessionId = activeSession.id,
            contact = contact,
            sessionDefaultLimit = activeSession.defaultReplyLimit,
        )
        val initiallyExhausted = evaluatedBudget is BudgetStatus.Exhausted
        val initialEntity = ExternalMessageEntity(
            fingerprint = MessageFingerprint.of(message),
            notificationKeyHash = MessageFingerprint.notificationKeyHash(message.notificationKey),
            sourcePackage = message.sourcePackage,
            sourceApp = message.sourceApp,
            contactKey = contact.key,
            sender = message.sender,
            content = message.content,
            sourceTimestamp = message.sourceTimestamp,
            receivedAt = System.currentTimeMillis(),
            trustLevel = message.trustLevel.name,
            normalizationStatus = ExternalMessageEntity.NORMALIZED,
            safetyStatus = ExternalMessageEntity.SAFETY_PASS,
            budgetUsed = evaluatedBudget.used,
            budgetLimit = evaluatedBudget.limit,
            processingStatus = if (initiallyExhausted) {
                ExternalMessageEntity.BUDGET_EXHAUSTED
            } else {
                ExternalMessageEntity.PROCESSING
            },
            deepSeekStatus = if (initiallyExhausted) {
                ExternalMessageEntity.DEEPSEEK_NOT_CALLED
            } else {
                ExternalMessageEntity.DEEPSEEK_CALLING
            },
            processedAt = if (initiallyExhausted) System.currentTimeMillis() else null,
            sessionId = activeSession.id,
        )
        val messageId = externalMessageDao.insert(initialEntity)
        if (messageId == -1L) return@withLock
        val storedMessage = initialEntity.copy(id = messageId)

        if (initiallyExhausted) {
            recordBudgetExhausted(message, contact, evaluatedBudget)
            return@withLock
        }

        when (
            val permitResult = replyBudgetManager.acquirePermit(
                sessionId = activeSession.id,
                contact = contact,
                sessionDefaultLimit = activeSession.defaultReplyLimit,
            )
        ) {
            is PermitResult.Exhausted -> {
                conversationRepository.updateMessage(
                    messageId,
                    ExternalMessageEntity.BUDGET_EXHAUSTED,
                    ExternalMessageEntity.DEEPSEEK_NOT_CALLED,
                    permitResult.used,
                )
                recordBudgetExhausted(
                    message,
                    contact,
                    BudgetStatus.Exhausted(permitResult.used, permitResult.limit),
                )
            }
            is PermitResult.Acquired -> processWithPermit(
                message = message,
                storedMessage = storedMessage,
                contact = contact,
                permit = permitResult.permit,
                replyTransport = replyTransport,
            )
        }
    }

    private suspend fun processWithPermit(
        message: NormalizedExternalMessage,
        storedMessage: ExternalMessageEntity,
        contact: ContactIdentity,
        permit: ReplyBudgetPermit,
        replyTransport: ReplyTransport?,
    ) {
        val result = agentEngine.generate(
            sessionId = storedMessage.sessionId,
            contactKey = contact.key,
            replyNumber = permit.usedBefore + 1,
            replyLimit = permit.limit,
        )
        when (result) {
            is DelegationAgentResult.Failure -> {
                conversationRepository.release(permit)
                val decisionInvalid = result.stage == DelegationAgentFailureStage.DECISION
                val deepSeekStatus = if (result.stage == DelegationAgentFailureStage.CONFIGURATION) {
                    ExternalMessageEntity.DEEPSEEK_NOT_CALLED
                } else if (result.stage == DelegationAgentFailureStage.API) {
                    ExternalMessageEntity.DEEPSEEK_FAILED
                } else {
                    ExternalMessageEntity.DEEPSEEK_CALLED
                }
                conversationRepository.updateMessage(
                    storedMessage.id,
                    if (decisionInvalid) {
                        ExternalMessageEntity.DECISION_INVALID
                    } else {
                        ExternalMessageEntity.API_FAILED
                    },
                    deepSeekStatus,
                    permit.usedBefore,
                )
                eventRepository.record(
                    "AGENT_PIPELINE",
                    buildString {
                        append("来源：${message.sourceApp}\n联系人：${message.sender}\n")
                        append("Trust：${message.trustLevel.name}\nSafety：PASS\n")
                        append("Reply Budget：${permit.usedBefore} / ${permit.limit}（未扣减）\n")
                        append("DeepSeek：$deepSeekStatus\n")
                        append("Agent Decision：FAILED (${result.stage})\n")
                        append("错误：${result.message}\n微信发送：NOT_EXECUTED")
                    },
                )
            }
            is DelegationAgentResult.Success -> finalizeSuccess(
                message,
                storedMessage,
                permit,
                result,
                replyTransport,
            )
        }
    }

    private suspend fun finalizeSuccess(
        message: NormalizedExternalMessage,
        storedMessage: ExternalMessageEntity,
        permit: ReplyBudgetPermit,
        result: DelegationAgentResult.Success,
        replyTransport: ReplyTransport?,
    ) {
        val consumesBudget = result.decision.action == AgentAction.REPLY
        val usedAfter = permit.usedBefore + if (consumesBudget) 1 else 0
        val finalized = try {
            conversationRepository.finalizeDecision(storedMessage, result.decision, permit)
            true
        } catch (_: Exception) {
            conversationRepository.release(permit)
            runCatching {
                conversationRepository.updateMessage(
                    storedMessage.id,
                    ExternalMessageEntity.LOCAL_FAILURE,
                    ExternalMessageEntity.DEEPSEEK_CALLED,
                    permit.usedBefore,
                )
            }
            runCatching {
                eventRepository.record(
                    "AGENT_PIPELINE",
                    "DeepSeek 已返回，但本地保存 Decision 失败；预算未扣减；微信发送 NOT_ATTEMPTED",
                )
            }
            false
        }
        if (!finalized) return

        runCatching {
            conversationRepository.updateMessage(
                storedMessage.id,
                if (consumesBudget) {
                    ExternalMessageEntity.COMPLETED_REPLY
                } else {
                    ExternalMessageEntity.COMPLETED_NO_REPLY
                },
                ExternalMessageEntity.DEEPSEEK_CALLED,
                usedAfter,
            )
        }
        val delivery = deliverReply(
            message = message,
            sessionId = storedMessage.sessionId,
            action = result.decision.action,
            reply = result.decision.reply,
            transport = replyTransport,
        )
        runCatching {
            conversationRepository.updateDecisionDelivery(
                sourceMessageId = storedMessage.id,
                status = delivery.status,
                transport = delivery.transport,
                deliveredAt = delivery.deliveredAt,
                error = delivery.error,
            )
        }
        runCatching {
            eventRepository.record(
                "AGENT_PIPELINE",
                buildString {
                    append("来源：${message.sourceApp}\n联系人：${message.sender}\n")
                    append("消息：${message.content}\nTrust：${message.trustLevel.name}\n")
                    append("Safety：PASS\n")
                    append("Reply Budget：${permit.usedBefore} / ${permit.limit} → $usedAfter / ${permit.limit}\n")
                    append("DeepSeek：CALLED\n")
                    append("Decision：${result.decision.action}\n")
                    append("Intent：${result.decision.intent}\n")
                    result.decision.reply?.let { append("准备回复：$it\n") }
                    append("Needs Owner：${result.decision.needsOwner}\n")
                    append("Urgency：${result.decision.urgency}\n")
                    append("Reason：${result.decision.reason}\n")
                    append("Tool：NOT_CALLED\n微信发送：${delivery.inspectorText}")
                },
            )
        }
    }

    private suspend fun deliverReply(
        message: NormalizedExternalMessage,
        sessionId: Long,
        action: AgentAction,
        reply: String?,
        transport: ReplyTransport?,
    ): DeliveryOutcome {
        if (action != AgentAction.REPLY || reply.isNullOrBlank()) {
            return DeliveryOutcome(
                status = ExternalAgentDecisionEntity.DELIVERY_NOT_ATTEMPTED,
                transport = null,
                deliveredAt = null,
                error = null,
                inspectorText = "NOT_ATTEMPTED（Decision 不是 REPLY）",
            )
        }
        return when (
            val authorization = replyDeliveryPolicy.authorize(
                ReplyDeliveryContext(
                    sourcePackage = message.sourcePackage,
                    trustLevel = message.trustLevel.name,
                    reply = reply,
                    transport = transport,
                ),
            )
        ) {
            is ReplyDeliveryAuthorization.Deny -> DeliveryOutcome(
                status = when (authorization.status) {
                    "PREVIEW_ONLY" -> ExternalAgentDecisionEntity.DELIVERY_PREVIEW_ONLY
                    "UNSUPPORTED" -> ExternalAgentDecisionEntity.DELIVERY_UNSUPPORTED
                    else -> ExternalAgentDecisionEntity.DELIVERY_FAILED
                },
                transport = transport?.type,
                deliveredAt = null,
                error = authorization.reason,
                inspectorText = "${authorization.status}（${authorization.reason}）",
            )
            ReplyDeliveryAuthorization.Allow -> {
                checkNotNull(transport)
                if (delegationSessionDao.getActive()?.id != sessionId) {
                    return DeliveryOutcome(
                        status = ExternalAgentDecisionEntity.DELIVERY_SESSION_ENDED,
                        transport = transport.type,
                        deliveredAt = null,
                        error = "托管 Session 已结束或已被替换",
                        inspectorText = "CANCELLED_SESSION_ENDED（发送前托管已关闭）",
                    )
                }
                val echoToken = outboundEchoGuard.register(message.notificationKey, reply)
                when (val sent = transport.dispatch(reply, sessionId)) {
                    ReplyTransportResult.Dispatched -> DeliveryOutcome(
                        status = if (transport.type == "WECHAT_ACCESSIBILITY") {
                            ExternalAgentDecisionEntity.DELIVERY_DISPATCHED_ACCESSIBILITY
                        } else {
                            ExternalAgentDecisionEntity.DELIVERY_DISPATCHED
                        },
                        transport = transport.type,
                        deliveredAt = System.currentTimeMillis(),
                        error = null,
                        inspectorText = if (transport.type == "WECHAT_ACCESSIBILITY") {
                            "DISPATCHED_ACCESSIBILITY（已通过微信界面点击发送，未确认对方服务器送达）"
                        } else {
                            "DISPATCHED_REMOTE_INPUT（已提交给微信，未确认服务器送达）"
                        },
                    )
                    is ReplyTransportResult.Failed -> {
                        outboundEchoGuard.cancel(echoToken)
                        DeliveryOutcome(
                            status = ExternalAgentDecisionEntity.DELIVERY_FAILED,
                            transport = transport.type,
                            deliveredAt = null,
                            error = sent.reason,
                            inspectorText = "SEND_FAILED（${sent.reason}）",
                        )
                    }
                    is ReplyTransportResult.Rejected -> {
                        outboundEchoGuard.cancel(echoToken)
                        DeliveryOutcome(
                            status = ExternalAgentDecisionEntity.DELIVERY_FAILED,
                            transport = transport.type,
                            deliveredAt = null,
                            error = sent.reason,
                            inspectorText = "${sent.status}（${sent.reason}）",
                        )
                    }
                }
            }
        }
    }

    private data class DeliveryOutcome(
        val status: String,
        val transport: String?,
        val deliveredAt: Long?,
        val error: String?,
        val inspectorText: String,
    )

    private suspend fun recordBudgetExhausted(
        message: NormalizedExternalMessage,
        contact: ContactIdentity,
        budget: BudgetStatus,
    ) {
        eventRepository.record(
            "AGENT_PIPELINE",
            buildString {
                append("来源：${message.sourceApp}\n联系人：${contact.displayName}\n")
                append("Trust：${message.trustLevel.name}\nSafety：PASS\n")
                append("Reply Budget：${budget.used} / ${budget.limit} EXHAUSTED\n")
                append("DeepSeek：NOT_CALLED\nReply：NOT_GENERATED\n")
                append("消息已作为 Session 未处理消息保存在本机")
            },
        )
    }
}
