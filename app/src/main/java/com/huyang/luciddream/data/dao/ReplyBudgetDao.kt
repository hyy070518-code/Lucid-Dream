package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.huyang.luciddream.data.entity.ReplyBudgetEntity

@Dao
interface ReplyBudgetDao {
    @Query("SELECT * FROM reply_budgets WHERE sessionId = :sessionId AND contactKey = :contactKey LIMIT 1")
    suspend fun get(sessionId: Long, contactKey: String): ReplyBudgetEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entity: ReplyBudgetEntity): Long

    @Query(
        "UPDATE reply_budgets SET replyLimit = :replyLimit, updatedAt = :updatedAt " +
            "WHERE sessionId = :sessionId AND contactKey = :contactKey",
    )
    suspend fun updateLimit(sessionId: Long, contactKey: String, replyLimit: Int, updatedAt: Long): Int

    @Query(
        "UPDATE reply_budgets SET reservedCount = reservedCount + 1, updatedAt = :updatedAt " +
            "WHERE sessionId = :sessionId AND contactKey = :contactKey " +
            "AND replyCount + reservedCount < replyLimit",
    )
    suspend fun reserve(sessionId: Long, contactKey: String, updatedAt: Long): Int

    @Query(
        "UPDATE reply_budgets SET reservedCount = reservedCount - 1, replyCount = replyCount + 1, " +
            "updatedAt = :updatedAt WHERE sessionId = :sessionId AND contactKey = :contactKey " +
            "AND reservedCount > 0",
    )
    suspend fun commit(sessionId: Long, contactKey: String, updatedAt: Long): Int

    @Query(
        "UPDATE reply_budgets SET reservedCount = reservedCount - 1, updatedAt = :updatedAt " +
            "WHERE sessionId = :sessionId AND contactKey = :contactKey AND reservedCount > 0",
    )
    suspend fun release(sessionId: Long, contactKey: String, updatedAt: Long): Int
}
