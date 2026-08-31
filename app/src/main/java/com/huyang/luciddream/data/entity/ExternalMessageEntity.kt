package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "external_messages",
    indices = [
        Index(value = ["fingerprint"], unique = true),
        Index(value = ["sessionId"]),
    ],
)
data class ExternalMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fingerprint: String,
    val notificationKeyHash: String,
    val sourcePackage: String,
    val sourceApp: String,
    val contactKey: String,
    val sender: String,
    val content: String,
    val sourceTimestamp: Long,
    val receivedAt: Long,
    val trustLevel: String,
    val normalizationStatus: String,
    val safetyStatus: String,
    val budgetUsed: Int,
    val budgetLimit: Int,
    val processingStatus: String,
    val deepSeekStatus: String,
    val processedAt: Long? = null,
    val sessionId: Long,
) {
    companion object {
        const val TRUST_EXTERNAL_UNTRUSTED = "EXTERNAL_UNTRUSTED"
        const val NORMALIZED = "NORMALIZED"
        const val SAFETY_PASS = "PASS"
        const val PROCESSING = "PROCESSING"
        const val BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
        const val COMPLETED_REPLY = "COMPLETED_REPLY"
        const val COMPLETED_NO_REPLY = "COMPLETED_NO_REPLY"
        const val API_FAILED = "API_FAILED"
        const val DECISION_INVALID = "DECISION_INVALID"
        const val LOCAL_FAILURE = "LOCAL_FAILURE"

        const val DEEPSEEK_NOT_CALLED = "NOT_CALLED"
        const val DEEPSEEK_CALLING = "CALLING"
        const val DEEPSEEK_CALLED = "CALLED"
        const val DEEPSEEK_FAILED = "FAILED"
    }
}
