package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.huyang.luciddream.data.entity.AppEventEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppEventDao {
    @Insert
    suspend fun insert(event: AppEventEntity)

    @Query("SELECT * FROM app_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AppEventEntity>>
}
