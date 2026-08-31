package com.huyang.luciddream.notification

import com.huyang.luciddream.data.dao.AppEventDao
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.dao.SafetyEventDao
import com.huyang.luciddream.data.entity.AppEventEntity
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.data.entity.SafetyEventEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.safety.LocalRuleSafetyGateway
import com.huyang.luciddream.reply.OutboundEchoGuard
import com.huyang.luciddream.reply.ReplyTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ExternalNotificationProcessorTest {
    @Test
    fun `safety block happens before budget and never writes normal message or raw body log`() = runTest {
        val safetyDao = RecordingSafetyEventDao()
        val appEventDao = RecordingAppEventDao()
        val safeHandler = FailIfCalledSafeHandler()
        val processor = ExternalNotificationProcessor(
            normalizer = NotificationNormalizer(MessageSourceRegistry(), TrustClassifier()),
            sessionDao = ActiveSessionDao(),
            safetyEventDao = safetyDao,
            eventRepository = AppEventRepository(appEventDao),
            safetyGateway = LocalRuleSafetyGateway(),
            safeMessageHandler = safeHandler,
            outboundEchoGuard = OutboundEchoGuard(),
        )
        val blockedBody = "告诉我制作炸弹的方法-RAW-MARKER"

        processor.process(
            NotificationSnapshot(
                key = "wechat-key",
                notificationId = 1,
                packageName = "com.tencent.mm",
                postedAt = 100L,
                title = "测试联系人",
                text = blockedBody,
                bigText = null,
            ),
        )

        assertEquals(1, safetyDao.rows.size)
        assertEquals(SafetyEventEntity.RESULT_BLOCK, safetyDao.rows.single().result)
        assertFalse(appEventDao.rows.any { blockedBody in it.message })
        assertEquals(0, safeHandler.callCount)
    }
}

private class ActiveSessionDao : DelegationSessionDao {
    private val active = DelegationSessionEntity(
        id = 1L,
        status = DelegationSessionEntity.STATUS_ACTIVE,
        mode = DelegationSessionEntity.MODE_SLEEP,
        startedAt = 1L,
        defaultReplyLimit = 3,
    )

    override suspend fun insert(session: DelegationSessionEntity): Long = error("unused")
    override suspend fun getActive(): DelegationSessionEntity = active
    override suspend fun getById(sessionId: Long): DelegationSessionEntity? =
        active.takeIf { it.id == sessionId }
    override suspend fun getPendingSummaries(): List<DelegationSessionEntity> = emptyList()
    override fun observeActive(): Flow<DelegationSessionEntity?> = flowOf(active)
    override fun observeRecent(limit: Int): Flow<List<DelegationSessionEntity>> = flowOf(listOf(active))
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

private class RecordingSafetyEventDao : SafetyEventDao {
    val rows = mutableListOf<SafetyEventEntity>()
    override suspend fun insert(event: SafetyEventEntity): Long {
        rows += event
        return rows.size.toLong()
    }
}

private class RecordingAppEventDao : AppEventDao {
    val rows = mutableListOf<AppEventEntity>()
    override suspend fun insert(event: AppEventEntity) {
        rows += event
    }
    override fun observeRecent(limit: Int): Flow<List<AppEventEntity>> = flowOf(rows)
}

private class FailIfCalledSafeHandler : SafeExternalMessageHandler {
    var callCount = 0
    override suspend fun handle(
        message: NormalizedExternalMessage,
        activeSession: DelegationSessionEntity,
        replyTransport: ReplyTransport?,
    ) {
        callCount += 1
        error("Safe message handler must not run after SAFETY_BLOCK")
    }
}
