package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DelegationSessionDao {
    @Insert
    suspend fun insert(session: DelegationSessionEntity): Long

    @Query("SELECT * FROM delegation_sessions WHERE activeSlot = 1 LIMIT 1")
    suspend fun getActive(): DelegationSessionEntity?

    @Query("SELECT * FROM delegation_sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getById(sessionId: Long): DelegationSessionEntity?

    @Query("SELECT * FROM delegation_sessions WHERE status = 'ENDED' AND summaryStatus = 'PENDING'")
    suspend fun getPendingSummaries(): List<DelegationSessionEntity>

    @Query("SELECT * FROM delegation_sessions WHERE activeSlot = 1 LIMIT 1")
    fun observeActive(): Flow<DelegationSessionEntity?>

    @Query("SELECT * FROM delegation_sessions ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 7): Flow<List<DelegationSessionEntity>>

    @Query(
        """
        UPDATE delegation_sessions
        SET status = 'ENDED', endedAt = :endedAt, activeSlot = NULL, summaryStatus = 'PENDING'
        WHERE id = :sessionId AND status = 'ACTIVE' AND activeSlot = 1
        """,
    )
    suspend fun endActive(sessionId: Long, endedAt: Long): Int

    @Query(
        "UPDATE delegation_sessions SET summaryStatus = :status, summary = :summary, " +
            "summaryNotificationText = :notificationText, summaryContactCount = :contactCount, " +
            "summaryNeedsOwnerCount = :needsOwnerCount, summaryGeneratedAt = :generatedAt " +
            "WHERE id = :sessionId",
    )
    suspend fun updateSummary(
        sessionId: Long,
        status: String,
        summary: String,
        notificationText: String,
        contactCount: Int,
        needsOwnerCount: Int,
        generatedAt: Long,
    ): Int

    @Query("SELECT id FROM delegation_sessions ORDER BY id DESC LIMIT -1 OFFSET :keepCount")
    suspend fun idsBeyondMostRecent(keepCount: Int): List<Long>

    @Query("DELETE FROM external_agent_decisions WHERE sessionId IN (:sessionIds)")
    suspend fun deleteDecisionsForSessions(sessionIds: List<Long>): Int

    @Query("DELETE FROM external_messages WHERE sessionId IN (:sessionIds)")
    suspend fun deleteMessagesForSessions(sessionIds: List<Long>): Int

    @Query("DELETE FROM safety_events WHERE sessionId IN (:sessionIds)")
    suspend fun deleteSafetyEventsForSessions(sessionIds: List<Long>): Int

    @Query("DELETE FROM reply_budgets WHERE sessionId IN (:sessionIds)")
    suspend fun deleteBudgetsForSessions(sessionIds: List<Long>): Int

    @Query("DELETE FROM delegation_sessions WHERE id IN (:sessionIds)")
    suspend fun deleteSessions(sessionIds: List<Long>): Int

    @Transaction
    suspend fun deleteSessionData(sessionIds: List<Long>) {
        if (sessionIds.isEmpty()) return
        deleteDecisionsForSessions(sessionIds)
        deleteMessagesForSessions(sessionIds)
        deleteSafetyEventsForSessions(sessionIds)
        deleteBudgetsForSessions(sessionIds)
        deleteSessions(sessionIds)
    }
}
