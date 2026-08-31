package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OwnerChatDao {
    @Insert
    suspend fun insert(message: OwnerChatMessageEntity): Long

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM owner_chat_messages ORDER BY id DESC LIMIT :limit
        ) ORDER BY id ASC
        """,
    )
    fun observeRecent(limit: Int = 100): Flow<List<OwnerChatMessageEntity>>

    @Query(
        """
        SELECT * FROM (
            SELECT * FROM owner_chat_messages ORDER BY id DESC LIMIT :limit
        ) ORDER BY id ASC
        """,
    )
    suspend fun recent(limit: Int): List<OwnerChatMessageEntity>
}
