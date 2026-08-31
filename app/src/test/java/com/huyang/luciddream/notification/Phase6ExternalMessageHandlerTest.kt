package com.huyang.luciddream.notification

import com.huyang.luciddream.agent.AgentAction
import com.huyang.luciddream.agent.AgentDecision
import com.huyang.luciddream.agent.AgentIntent
import com.huyang.luciddream.agent.AgentUrgency
import com.huyang.luciddream.agent.DelegationAgentResult
import com.huyang.luciddream.agent.DelegationDecisionGenerator
import com.huyang.luciddream.data.dao.AppEventDao
import com.huyang.luciddream.data.dao.ContactPolicyDao
import com.huyang.luciddream.data.dao.ExternalMessageDao
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.dao.ObservedContact
import com.huyang.luciddream.data.dao.ReplyBudgetDao
import com.huyang.luciddream.data.entity.AppEventEntity
import com.huyang.luciddream.data.entity.ContactPolicyEntity
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.data.entity.ExternalMessageEntity
import com.huyang.luciddream.data.entity.ExternalAgentDecisionEntity
import com.huyang.luciddream.data.entity.ReplyBudgetEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.data.repository.DelegationConversationStore
import com.huyang.luciddream.network.DeepSeekMessage
import com.huyang.luciddream.policy.ContactIdentityResolver
import com.huyang.luciddream.policy.ReplyBudgetManager
import com.huyang.luciddream.policy.ReplyBudgetPermit
import com.huyang.luciddream.reply.AutoReplyState
import com.huyang.luciddream.reply.OutboundEchoGuard
import com.huyang.luciddream.reply.ReplyDeliveryPolicy
import com.huyang.luciddream.reply.ReplyTransport
import com.huyang.luciddream.reply.ReplyTransportResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6ExternalMessageHandlerTest {
    @Test
    fun `three replies exhaust one session and a new session resets usage`() = runTest {
        val messageDao = HandlerMessageDao()
        val budgetDao = HandlerBudgetDao()
        val budgetManager = ReplyBudgetManager(budgetDao, HandlerPolicyDao())
        val generator = ReplyingGenerator()
        val events = HandlerAppEventDao()
        val store = HandlerConversationStore(budgetManager, messageDao)
        val handler = Phase6ExternalMessageHandler(
            externalMessageDao = messageDao,
            contactIdentityResolver = ContactIdentityResolver(),
            replyBudgetManager = budgetManager,
            agentEngine = generator,
            conversationRepository = store,
            eventRepository = AppEventRepository(events),
            replyDeliveryPolicy = ReplyDeliveryPolicy(FakeAutoReplyState(false)),
            outboundEchoGuard = OutboundEchoGuard(),
            delegationSessionDao = HandlerSessionDao(null),
        )
        val firstSession = session(10L)

        (1..4).forEach { index -> handler.handle(message(index), firstSession, null) }

        assertEquals(3, generator.calls.size)
        assertEquals(listOf(1, 2, 3), generator.calls.map { it.replyNumber })
        assertEquals(ExternalMessageEntity.BUDGET_EXHAUSTED, messageDao.rows.last().processingStatus)
        assertTrue(events.rows.last().message.contains("3 / 3 EXHAUSTED"))

        handler.handle(message(5), session(11L), null)

        assertEquals(4, generator.calls.size)
        assertEquals(11L, generator.calls.last().sessionId)
        assertEquals(1, generator.calls.last().replyNumber)
        assertEquals(1, budgetDao.row(11L)!!.replyCount)
        assertEquals(3, budgetDao.row(10L)!!.replyCount)
    }

    @Test
    fun `enabled policy dispatches one reply and records delivery`() = runTest {
        val messageDao = HandlerMessageDao()
        val budgetManager = ReplyBudgetManager(HandlerBudgetDao(), HandlerPolicyDao())
        val store = HandlerConversationStore(budgetManager, messageDao)
        val transport = RecordingReplyTransport()
        val handler = Phase6ExternalMessageHandler(
            externalMessageDao = messageDao,
            contactIdentityResolver = ContactIdentityResolver(),
            replyBudgetManager = budgetManager,
            agentEngine = ReplyingGenerator(),
            conversationRepository = store,
            eventRepository = AppEventRepository(HandlerAppEventDao()),
            replyDeliveryPolicy = ReplyDeliveryPolicy(FakeAutoReplyState(true)),
            outboundEchoGuard = OutboundEchoGuard(),
            delegationSessionDao = HandlerSessionDao(20L),
        )

        handler.handle(message(20), session(20L), transport)

        assertEquals(listOf("准备回复 1"), transport.sent)
        assertEquals(ExternalAgentDecisionEntity.DELIVERY_DISPATCHED, store.deliveryUpdates.single())
    }

    @Test
    fun `ended session cancels reply immediately before dispatch`() = runTest {
        val messageDao = HandlerMessageDao()
        val budgetManager = ReplyBudgetManager(HandlerBudgetDao(), HandlerPolicyDao())
        val store = HandlerConversationStore(budgetManager, messageDao)
        val transport = RecordingReplyTransport()
        val handler = Phase6ExternalMessageHandler(
            externalMessageDao = messageDao,
            contactIdentityResolver = ContactIdentityResolver(),
            replyBudgetManager = budgetManager,
            agentEngine = ReplyingGenerator(),
            conversationRepository = store,
            eventRepository = AppEventRepository(HandlerAppEventDao()),
            replyDeliveryPolicy = ReplyDeliveryPolicy(FakeAutoReplyState(true)),
            outboundEchoGuard = OutboundEchoGuard(),
            delegationSessionDao = HandlerSessionDao(null),
        )

        handler.handle(message(21), session(21L), transport)

        assertTrue(transport.sent.isEmpty())
        assertEquals(ExternalAgentDecisionEntity.DELIVERY_SESSION_ENDED, store.deliveryUpdates.single())
    }

    private fun session(id: Long) = DelegationSessionEntity(
        id = id,
        status = DelegationSessionEntity.STATUS_ACTIVE,
        mode = DelegationSessionEntity.MODE_SLEEP,
        startedAt = id,
        defaultReplyLimit = 3,
    )

    private fun message(index: Int) = NormalizedExternalMessage(
        notificationKey = "key-$index",
        notificationId = index,
        sourcePackage = "com.tencent.mm",
        sourceApp = "微信",
        sender = "Alice",
        content = "测试消息 $index",
        sourceTimestamp = index.toLong(),
        trustLevel = TrustLevel.EXTERNAL_UNTRUSTED,
    )
}

private data class GeneratorCall(val sessionId: Long, val replyNumber: Int)

private class FakeAutoReplyState(override val isEnabled: Boolean) : AutoReplyState

private class RecordingReplyTransport : ReplyTransport {
    override val type = "REMOTE_INPUT"
    val sent = mutableListOf<String>()
    override suspend fun dispatch(text: String, sessionId: Long): ReplyTransportResult {
        sent += text
        return ReplyTransportResult.Dispatched
    }
}

private class ReplyingGenerator : DelegationDecisionGenerator {
    val calls = mutableListOf<GeneratorCall>()
    override suspend fun generate(
        sessionId: Long,
        contactKey: String,
        replyNumber: Int,
        replyLimit: Int,
    ): DelegationAgentResult {
        calls += GeneratorCall(sessionId, replyNumber)
        return DelegationAgentResult.Success(
            AgentDecision(
                action = AgentAction.REPLY,
                reply = "准备回复 $replyNumber",
                intent = AgentIntent.ACKNOWLEDGE,
                needsOwner = false,
                urgency = AgentUrgency.LOW,
                reason = "测试",
            ),
        )
    }
}

private class HandlerConversationStore(
    private val budgetManager: ReplyBudgetManager,
    private val messageDao: HandlerMessageDao,
) : DelegationConversationStore {
    val deliveryUpdates = mutableListOf<String>()
    override suspend fun isFirstContact(sessionId: Long, contactKey: String): Boolean = true
    override suspend fun context(sessionId: Long, contactKey: String): List<DeepSeekMessage> = emptyList()
    override suspend fun finalizeDecision(
        message: ExternalMessageEntity,
        decision: AgentDecision,
        permit: ReplyBudgetPermit,
    ): Long {
        check(budgetManager.commitPreparedReply(permit))
        return message.id
    }
    override suspend fun release(permit: ReplyBudgetPermit): Boolean = budgetManager.release(permit)
    override suspend fun updateMessage(
        messageId: Long,
        processingStatus: String,
        deepSeekStatus: String,
        budgetUsed: Int,
    ) {
        messageDao.updateProcessing(messageId, processingStatus, deepSeekStatus, 1L, budgetUsed)
    }
    override suspend fun updateDecisionDelivery(
        sourceMessageId: Long,
        status: String,
        transport: String?,
        deliveredAt: Long?,
        error: String?,
    ) {
        deliveryUpdates += status
    }
}

private class HandlerMessageDao : ExternalMessageDao {
    val rows = mutableListOf<ExternalMessageEntity>()
    override suspend fun insert(message: ExternalMessageEntity): Long {
        if (rows.any { it.fingerprint == message.fingerprint }) return -1L
        val id = rows.size + 1L
        rows += message.copy(id = id)
        return id
    }
    override fun observeRecent(limit: Int): Flow<List<ExternalMessageEntity>> = flowOf(rows)
    override fun observeContacts(): Flow<List<ObservedContact>> = flowOf(emptyList())
    override suspend fun recentForConversation(
        sessionId: Long,
        contactKey: String,
        limit: Int,
    ): List<ExternalMessageEntity> = rows.filter {
        it.sessionId == sessionId && it.contactKey == contactKey
    }.takeLast(limit).reversed()
    override suspend fun updateProcessing(
        messageId: Long,
        processingStatus: String,
        deepSeekStatus: String,
        processedAt: Long?,
        budgetUsed: Int,
    ): Int {
        val index = rows.indexOfFirst { it.id == messageId }
        if (index < 0) return 0
        rows[index] = rows[index].copy(
            processingStatus = processingStatus,
            deepSeekStatus = deepSeekStatus,
            processedAt = processedAt,
            budgetUsed = budgetUsed,
        )
        return 1
    }
    override suspend fun forSessionSummary(
        sessionId: Long,
        limit: Int,
    ): List<ExternalMessageEntity> = rows.filter { it.sessionId == sessionId }.take(limit)
}

private class HandlerSessionDao(activeId: Long?) : DelegationSessionDao {
    private val active = activeId?.let {
        DelegationSessionEntity(
            id = it,
            status = DelegationSessionEntity.STATUS_ACTIVE,
            mode = DelegationSessionEntity.MODE_SLEEP,
            startedAt = it,
            defaultReplyLimit = 3,
        )
    }

    override suspend fun insert(session: DelegationSessionEntity): Long = error("unused")
    override suspend fun getActive(): DelegationSessionEntity? = active
    override suspend fun getById(sessionId: Long): DelegationSessionEntity? = active?.takeIf { it.id == sessionId }
    override suspend fun getPendingSummaries(): List<DelegationSessionEntity> = emptyList()
    override fun observeActive(): Flow<DelegationSessionEntity?> = flowOf(active)
    override fun observeRecent(limit: Int): Flow<List<DelegationSessionEntity>> = flowOf(listOfNotNull(active))
    override suspend fun endActive(sessionId: Long, endedAt: Long): Int = error("unused")
    override suspend fun updateSummary(
        sessionId: Long,
        status: String,
        summary: String,
        notificationText: String,
        contactCount: Int,
        needsOwnerCount: Int,
        generatedAt: Long,
    ): Int = error("unused")
    override suspend fun idsBeyondMostRecent(keepCount: Int): List<Long> = emptyList()
    override suspend fun deleteDecisionsForSessions(sessionIds: List<Long>): Int = error("unused")
    override suspend fun deleteMessagesForSessions(sessionIds: List<Long>): Int = error("unused")
    override suspend fun deleteSafetyEventsForSessions(sessionIds: List<Long>): Int = error("unused")
    override suspend fun deleteBudgetsForSessions(sessionIds: List<Long>): Int = error("unused")
    override suspend fun deleteSessions(sessionIds: List<Long>): Int = error("unused")
}

private class HandlerBudgetDao : ReplyBudgetDao {
    private val rows = mutableMapOf<Pair<Long, String>, ReplyBudgetEntity>()
    private var nextId = 1L
    fun row(sessionId: Long): ReplyBudgetEntity? = rows.entries.firstOrNull { it.key.first == sessionId }?.value
    override suspend fun get(sessionId: Long, contactKey: String) = rows[sessionId to contactKey]
    override suspend fun insertIgnore(entity: ReplyBudgetEntity): Long {
        val key = entity.sessionId to entity.contactKey
        if (key in rows) return -1L
        val id = nextId++
        rows[key] = entity.copy(id = id)
        return id
    }
    override suspend fun updateLimit(sessionId: Long, contactKey: String, replyLimit: Int, updatedAt: Long) =
        update(sessionId, contactKey) { it.copy(replyLimit = replyLimit, updatedAt = updatedAt) }
    override suspend fun reserve(sessionId: Long, contactKey: String, updatedAt: Long) =
        update(sessionId, contactKey) {
            if (it.replyCount + it.reservedCount >= it.replyLimit) return@update null
            it.copy(reservedCount = it.reservedCount + 1, updatedAt = updatedAt)
        }
    override suspend fun commit(sessionId: Long, contactKey: String, updatedAt: Long) =
        update(sessionId, contactKey) {
            if (it.reservedCount == 0) return@update null
            it.copy(replyCount = it.replyCount + 1, reservedCount = it.reservedCount - 1, updatedAt = updatedAt)
        }
    override suspend fun release(sessionId: Long, contactKey: String, updatedAt: Long) =
        update(sessionId, contactKey) {
            if (it.reservedCount == 0) return@update null
            it.copy(reservedCount = it.reservedCount - 1, updatedAt = updatedAt)
        }
    private fun update(
        sessionId: Long,
        contactKey: String,
        transform: (ReplyBudgetEntity) -> ReplyBudgetEntity?,
    ): Int {
        val key = sessionId to contactKey
        val current = rows[key] ?: return 0
        rows[key] = transform(current) ?: return 0
        return 1
    }
}

private class HandlerPolicyDao : ContactPolicyDao {
    override suspend fun get(contactKey: String): ContactPolicyEntity? = null
    override fun observeAll(): Flow<List<ContactPolicyEntity>> = flowOf(emptyList())
    override suspend fun upsert(policy: ContactPolicyEntity) = Unit
}

private class HandlerAppEventDao : AppEventDao {
    val rows = mutableListOf<AppEventEntity>()
    override suspend fun insert(event: AppEventEntity) {
        rows += event
    }
    override fun observeRecent(limit: Int): Flow<List<AppEventEntity>> = flowOf(rows)
}
