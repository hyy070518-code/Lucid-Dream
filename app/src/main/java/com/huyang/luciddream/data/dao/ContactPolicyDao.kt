package com.huyang.luciddream.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.huyang.luciddream.data.entity.ContactPolicyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactPolicyDao {
    @Query("SELECT * FROM contact_policies WHERE contactKey = :contactKey LIMIT 1")
    suspend fun get(contactKey: String): ContactPolicyEntity?

    @Query("SELECT * FROM contact_policies ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactPolicyEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(policy: ContactPolicyEntity)
}
