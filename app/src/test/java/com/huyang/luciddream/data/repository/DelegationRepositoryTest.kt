package com.huyang.luciddream.data.repository

import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationRepositoryTest {
    @Test
    fun secondStartReturnsExistingActiveSession() = runTest {
        val dao = FakeDelegationSessionDao()
        val repository = DelegationRepository(dao)

        val first = repository.startSleepSession(now = 100L)
        val second = repository.startSleepSession(now = 200L)

        assertTrue(first is StartSessionResult.Created)
        assertTrue(second is StartSessionResult.AlreadyActive)
        assertEquals(1, dao.rows.size)
        assertEquals(100L, dao.rows.single().startedAt)
    }

    @Test
    fun endingActiveSessionAllowsANewSession() = runTest {
        val dao = FakeDelegationSessionDao()
        val repository = DelegationRepository(dao)

        repository.startSleepSession(now = 100L)
        val ended = repository.endActiveSession(now = 160L)
        val next = repository.startSleepSession(now = 200L)

        assertTrue(ended is EndSessionResult.Ended)
        assertTrue(next is StartSessionResult.Created)
        assertEquals(2, dao.rows.size)
        assertNull(dao.rows.first().activeSlot)
        assertEquals(DelegationSessionEntity.STATUS_ENDED, dao.rows.first().status)
        assertEquals(DelegationSessionEntity.STATUS_ACTIVE, dao.rows.last().status)
    }

    @Test
    fun eighthCompletedSessionDeletesOldestAndKeepsSeven() = runTest {
        val dao = FakeDelegationSessionDao()
        val repository = DelegationRepository(dao)
        repeat(8) { index ->
            repository.startSleepSession(now = index.toLong())
            repository.endActiveSession(now = index + 100L)
        }

        val deleted = repository.trimToMostRecent(7)

        assertEquals(1, deleted)
        assertEquals(7, dao.rows.size)
        assertEquals((2L..8L).toList(), dao.rows.map { it.id })
    }

    @Test
    fun generatedSummaryIsPersistedOnEndedSession() = runTest {
        val dao = FakeDelegationSessionDao()
        val repository = DelegationRepository(dao)
        val created = repository.startSleepSession(now = 1L) as StartSessionResult.Created
        repository.endActiveSession(now = 2L)

        val saved = repository.saveSummary(
            sessionId = created.session.id,
            status = DelegationSessionEntity.SUMMARY_COMPLETED,
            summary = "交接总结",
            notificationText = "1 人联系过你",
            contactCount = 1,
            needsOwnerCount = 1,
            generatedAt = 3L,
        )

        assertTrue(saved)
        assertEquals("交接总结", dao.rows.single().summary)
        assertEquals(1, dao.rows.single().summaryContactCount)
    }

    private class FakeDelegationSessionDao : DelegationSessionDao {
        val rows = mutableListOf<DelegationSessionEntity>()
        private val active = MutableStateFlow<DelegationSessionEntity?>(null)
        private val recent = MutableStateFlow<List<DelegationSessionEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(session: DelegationSessionEntity): Long {
            val inserted = session.copy(id = nextId++)
            rows += inserted
            publish()
            return inserted.id
        }

        override suspend fun getActive(): DelegationSessionEntity? =
            rows.firstOrNull { it.activeSlot == DelegationSessionEntity.ACTIVE_SLOT }

        override suspend fun getById(sessionId: Long): DelegationSessionEntity? =
            rows.firstOrNull { it.id == sessionId }

        override suspend fun getPendingSummaries(): List<DelegationSessionEntity> =
            rows.filter { it.summaryStatus == DelegationSessionEntity.SUMMARY_PENDING }

        override fun observeActive(): Flow<DelegationSessionEntity?> = active

        override fun observeRecent(limit: Int): Flow<List<DelegationSessionEntity>> = recent

        override suspend fun endActive(sessionId: Long, endedAt: Long): Int {
            val index = rows.indexOfFirst {
                it.id == sessionId && it.status == DelegationSessionEntity.STATUS_ACTIVE
            }
            if (index < 0) return 0
            rows[index] = rows[index].copy(
                status = DelegationSessionEntity.STATUS_ENDED,
                endedAt = endedAt,
                summaryStatus = DelegationSessionEntity.SUMMARY_PENDING,
                activeSlot = null,
            )
            publish()
            return 1
        }

        override suspend fun updateSummary(
            sessionId: Long,
            status: String,
            summary: String,
            notificationText: String,
            contactCount: Int,
            needsOwnerCount: Int,
            generatedAt: Long,
        ): Int {
            val index = rows.indexOfFirst { it.id == sessionId }
            if (index < 0) return 0
            rows[index] = rows[index].copy(
                summaryStatus = status,
                summary = summary,
                summaryNotificationText = notificationText,
                summaryContactCount = contactCount,
                summaryNeedsOwnerCount = needsOwnerCount,
                summaryGeneratedAt = generatedAt,
            )
            publish()
            return 1
        }

        override suspend fun idsBeyondMostRecent(keepCount: Int): List<Long> =
            rows.sortedByDescending { it.id }.drop(keepCount).map { it.id }

        override suspend fun deleteDecisionsForSessions(sessionIds: List<Long>): Int = 0
        override suspend fun deleteMessagesForSessions(sessionIds: List<Long>): Int = 0
        override suspend fun deleteSafetyEventsForSessions(sessionIds: List<Long>): Int = 0
        override suspend fun deleteBudgetsForSessions(sessionIds: List<Long>): Int = 0
        override suspend fun deleteSessions(sessionIds: List<Long>): Int {
            val before = rows.size
            rows.removeAll { it.id in sessionIds }
            publish()
            return before - rows.size
        }

        private fun publish() {
            active.value = rows.firstOrNull {
                it.activeSlot == DelegationSessionEntity.ACTIVE_SLOT
            }
            recent.value = rows.sortedByDescending { it.id }.take(7)
        }
    }
}
