package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.huyang.luciddream.data.entity.ExternalAgentDecisionEntity

@Dao
interface ExternalAgentDecisionDao {
    @Insert
    suspend fun insert(decision: ExternalAgentDecisionEntity): Long

    @Query(
        "SELECT * FROM external_agent_decisions WHERE sessionId = :sessionId AND contactKey = :contactKey " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit",
    )
    suspend fun recentForConversation(
        sessionId: Long,
        contactKey: String,
        limit: Int = 20,
    ): List<ExternalAgentDecisionEntity>

    @Query(
        "SELECT COUNT(*) FROM external_agent_decisions " +
            "WHERE sessionId = :sessionId AND contactKey = :contactKey AND action = 'REPLY'",
    )
    suspend fun countForConversation(sessionId: Long, contactKey: String): Int

    @Query(
        "SELECT * FROM external_agent_decisions WHERE sessionId = :sessionId " +
            "ORDER BY createdAt ASC, id ASC LIMIT :limit",
    )
    suspend fun forSessionSummary(
        sessionId: Long,
        limit: Int = 200,
    ): List<ExternalAgentDecisionEntity>

    @Query(
        "UPDATE external_agent_decisions SET deliveryStatus = :status, " +
            "deliveryTransport = :transport, deliveredAt = :deliveredAt, deliveryError = :error " +
            "WHERE sourceMessageId = :sourceMessageId",
    )
    suspend fun updateDelivery(
        sourceMessageId: Long,
        status: String,
        transport: String?,
        deliveredAt: Long?,
        error: String?,
    ): Int
}
