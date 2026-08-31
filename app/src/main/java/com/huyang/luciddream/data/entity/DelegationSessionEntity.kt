package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "delegation_sessions",
    indices = [Index(value = ["activeSlot"], unique = true)],
)
data class DelegationSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: String,
    val mode: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val defaultReplyLimit: Int,
    val summaryStatus: String = SUMMARY_NOT_STARTED,
    val summary: String? = null,
    val summaryNotificationText: String? = null,
    val summaryContactCount: Int = 0,
    val summaryNeedsOwnerCount: Int = 0,
    val summaryGeneratedAt: Long? = null,
    /** Active rows use 1; ended rows use null. SQLite unique indexes allow multiple nulls. */
    val activeSlot: Int? = ACTIVE_SLOT,
) {
    companion object {
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_ENDED = "ENDED"
        const val MODE_SLEEP = "SLEEP"
        const val DEFAULT_REPLY_LIMIT = 3
        const val ACTIVE_SLOT = 1
        const val SUMMARY_NOT_STARTED = "NOT_STARTED"
        const val SUMMARY_PENDING = "PENDING"
        const val SUMMARY_COMPLETED = "COMPLETED"
        const val SUMMARY_EMPTY = "EMPTY"
        const val SUMMARY_FAILED = "FAILED"
    }
}
