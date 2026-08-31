package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.huyang.luciddream.data.entity.ExternalMessageEntity
import kotlinx.coroutines.flow.Flow

data class ObservedContact(
    val contactKey: String,
    val sourcePackage: String,
    val displayName: String,
    val lastSeenAt: Long,
)

@Dao
interface ExternalMessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ExternalMessageEntity): Long

    @Query("SELECT * FROM external_messages ORDER BY receivedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<ExternalMessageEntity>>

    @Query(
        "SELECT contactKey, sourcePackage, sender AS displayName, MAX(receivedAt) AS lastSeenAt " +
            "FROM external_messages WHERE contactKey != '' GROUP BY contactKey, sourcePackage, sender " +
            "ORDER BY lastSeenAt DESC",
    )
    fun observeContacts(): Flow<List<ObservedContact>>

    @Query(
        "SELECT * FROM external_messages WHERE sessionId = :sessionId AND contactKey = :contactKey " +
            "AND safetyStatus = 'PASS' ORDER BY receivedAt DESC, id DESC LIMIT :limit",
    )
    suspend fun recentForConversation(
        sessionId: Long,
        contactKey: String,
        limit: Int = 20,
    ): List<ExternalMessageEntity>

    @Query(
        "UPDATE external_messages SET processingStatus = :processingStatus, " +
            "deepSeekStatus = :deepSeekStatus, processedAt = :processedAt, budgetUsed = :budgetUsed " +
            "WHERE id = :messageId",
    )
    suspend fun updateProcessing(
        messageId: Long,
        processingStatus: String,
        deepSeekStatus: String,
        processedAt: Long?,
        budgetUsed: Int,
    ): Int

    @Query(
        "SELECT * FROM external_messages WHERE sessionId = :sessionId AND safetyStatus = 'PASS' " +
            "ORDER BY receivedAt ASC, id ASC LIMIT :limit",
    )
    suspend fun forSessionSummary(
        sessionId: Long,
        limit: Int = 200,
    ): List<ExternalMessageEntity>
}
