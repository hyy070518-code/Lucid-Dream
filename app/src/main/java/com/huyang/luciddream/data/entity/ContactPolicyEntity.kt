package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_policies")
data class ContactPolicyEntity(
    @PrimaryKey val contactKey: String,
    val sourcePackage: String,
    val displayName: String,
    val isAllowlisted: Boolean,
    val replyLimit: Int,
    val updatedAt: Long,
)
