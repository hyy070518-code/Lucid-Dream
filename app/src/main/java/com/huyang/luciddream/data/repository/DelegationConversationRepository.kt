package com.huyang.luciddream.data.repository

import androidx.room.withTransaction
import com.huyang.luciddream.agent.AgentAction
import com.huyang.luciddream.agent.AgentDecision
import com.huyang.luciddream.data.dao.ExternalAgentDecisionDao
import com.huyang.luciddream.data.dao.ExternalMessageDao
import com.huyang.luciddream.data.database.LucidDreamDatabase
import com.huyang.luciddream.data.entity.ExternalAgentDecisionEntity
import com.huyang.luciddream.data.entity.ExternalMessageEntity
import com.huyang.luciddream.network.DeepSeekMessage
import com.huyang.luciddream.policy.ReplyBudgetManager
import com.huyang.luciddream.policy.ReplyBudgetPermit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface DelegationConversationStore {
    suspend fun isFirstContact(sessionId: Long, contactKey: String): Boolean
    suspend fun context(sessionId: Long, contactKey: String): List<DeepSeekMessage>
    suspend fun finalizeDecision(
        message: ExternalMessageEntity,
        decision: AgentDecision,
        permit: ReplyBudgetPermit,
    ): Long
    suspend fun release(permit: ReplyBudgetPermit): Boolean
    suspend fun updateMessage(
        messageId: Long,
        processingStatus: String,
        deepSeekStatus: String,
        budgetUsed: Int,
    )
    suspend fun updateDecisionDelivery(
        sourceMessageId: Long,
        status: String,
        transport: String?,
        deliveredAt: Long?,
        error: String?,
    )
}

@Singleton
class DelegationConversationRepository @Inject constructor(
    private val database: LucidDreamDatabase,
    private val messageDao: ExternalMessageDao,
    private val decisionDao: ExternalAgentDecisionDao,
    private val budgetManager: ReplyBudgetManager,
) : DelegationConversationStore {
    private val json = Json { explicitNulls = false }

    override suspend fun isFirstContact(sessionId: Long, contactKey: String): Boolean =
        decisionDao.countForConversation(sessionId, contactKey) == 0

    override suspend fun context(sessionId: Long, contactKey: String): List<DeepSeekMessage> {
        val messages = messageDao.recentForConversation(sessionId, contactKey, CONTEXT_LIMIT)
            .map { ContextItem(it.receivedAt, 0, it.id, "user", it.content) }
        val decisions = decisionDao.recentForConversation(sessionId, contactKey, CONTEXT_LIMIT)
            .map { entity ->
                ContextItem(
                    timestamp = entity.createdAt,
                    roleOrder = 1,
                    tieBreaker = entity.id,
                    role = "assistant",
                    content = json.encodeToString(entity.toDecision()),
                )
            }
        return (messages + decisions)
            .sortedWith(
                compareBy(ContextItem::timestamp, ContextItem::roleOrder, ContextItem::tieBreaker),
            )
            .takeLast(CONTEXT_LIMIT)
            .map { DeepSeekMessage(role = it.role, content = it.content) }
    }

    override suspend fun finalizeDecision(
        message: ExternalMessageEntity,
        decision: AgentDecision,
        permit: ReplyBudgetPermit,
    ): Long = database.withTransaction {
        val decisionId = decisionDao.insert(
            ExternalAgentDecisionEntity(
                sessionId = message.sessionId,
                contactKey = message.contactKey,
                sourceMessageId = message.id,
                action = decision.action.name,
                reply = decision.reply,
                intent = decision.intent.name,
                needsOwner = decision.needsOwner,
                urgency = decision.urgency.name,
                reason = decision.reason,
                createdAt = System.currentTimeMillis(),
            ),
        )
        val budgetUpdated = if (decision.action == AgentAction.REPLY) {
            budgetManager.commitPreparedReply(permit)
        } else {
            budgetManager.release(permit)
        }
        check(budgetUpdated) { "Reply budget reservation could not be finalized" }
        decisionId
    }

    override suspend fun release(permit: ReplyBudgetPermit): Boolean = budgetManager.release(permit)

    override suspend fun updateMessage(
        messageId: Long,
        processingStatus: String,
        deepSeekStatus: String,
        budgetUsed: Int,
    ) {
        messageDao.updateProcessing(
            messageId = messageId,
            processingStatus = processingStatus,
            deepSeekStatus = deepSeekStatus,
            processedAt = System.currentTimeMillis(),
            budgetUsed = budgetUsed,
        )
    }

    override suspend fun updateDecisionDelivery(
        sourceMessageId: Long,
        status: String,
        transport: String?,
        deliveredAt: Long?,
        error: String?,
    ) {
        decisionDao.updateDelivery(
            sourceMessageId = sourceMessageId,
            status = status,
            transport = transport,
            deliveredAt = deliveredAt,
            error = error,
        )
    }

    private fun ExternalAgentDecisionEntity.toDecision() = AgentDecision(
        action = com.huyang.luciddream.agent.AgentAction.valueOf(action),
        reply = reply,
        intent = com.huyang.luciddream.agent.AgentIntent.valueOf(intent),
        needsOwner = needsOwner,
        urgency = com.huyang.luciddream.agent.AgentUrgency.valueOf(urgency),
        reason = reason,
    )

    private data class ContextItem(
        val timestamp: Long,
        val roleOrder: Int,
        val tieBreaker: Long,
        val role: String,
        val content: String,
    )

    private companion object {
        const val CONTEXT_LIMIT = 20
    }
}
