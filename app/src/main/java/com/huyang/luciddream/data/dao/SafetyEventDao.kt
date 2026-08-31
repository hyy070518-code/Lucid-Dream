package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.huyang.luciddream.data.entity.SafetyEventEntity

@Dao
interface SafetyEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: SafetyEventEntity): Long
}
