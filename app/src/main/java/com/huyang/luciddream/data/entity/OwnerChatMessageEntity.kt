package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "owner_chat_messages")
data class OwnerChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String,
    val content: String,
    val timestamp: Long,
    val action: String? = null,
    val intent: String? = null,
    val needsOwner: Boolean? = null,
    val urgency: String? = null,
    val reason: String? = null,
)
