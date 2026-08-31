package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Deliberately excludes the blocked message body. */
@Entity(
    tableName = "safety_events",
    indices = [
        Index(value = ["eventFingerprint"], unique = true),
        Index(value = ["sessionId"]),
    ],
)
data class SafetyEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val eventFingerprint: String,
    val timestamp: Long,
    val sourcePackage: String,
    val sourceApp: String,
    val sender: String,
    val sessionId: Long,
    val result: String,
    val reasonCode: String,
    val deepSeekStatus: String,
    val toolStatus: String,
    val taskStatus: String,
    val memoryStatus: String,
) {
    companion object {
        const val RESULT_BLOCK = "SAFETY_BLOCK"
        const val NOT_CALLED = "NOT_CALLED"
        const val NOT_CREATED = "NOT_CREATED"
        const val NOT_WRITTEN = "NOT_WRITTEN"
    }
}
