package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "reply_budgets",
    indices = [Index(value = ["sessionId", "contactKey"], unique = true)],
)
data class ReplyBudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val contactKey: String,
    val contactDisplayName: String,
    val replyCount: Int,
    val reservedCount: Int,
    val replyLimit: Int,
    val updatedAt: Long,
)
