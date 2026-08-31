package com.huyang.luciddream.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "external_agent_decisions",
    indices = [
        Index(value = ["sessionId", "contactKey"]),
        Index(value = ["sourceMessageId"], unique = true),
    ],
)
data class ExternalAgentDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val contactKey: String,
    val sourceMessageId: Long,
    val action: String,
    val reply: String?,
    val intent: String,
    val needsOwner: Boolean,
    val urgency: String,
    val reason: String,
    val createdAt: Long,
    val deliveryStatus: String = DELIVERY_NOT_ATTEMPTED,
    val deliveryTransport: String? = null,
    val deliveredAt: Long? = null,
    val deliveryError: String? = null,
) {
    companion object {
        const val DELIVERY_NOT_ATTEMPTED = "NOT_ATTEMPTED"
        const val DELIVERY_PREVIEW_ONLY = "PREVIEW_ONLY"
        const val DELIVERY_UNSUPPORTED = "UNSUPPORTED"
        const val DELIVERY_DISPATCHED = "DISPATCHED_REMOTE_INPUT"
        const val DELIVERY_DISPATCHED_ACCESSIBILITY = "DISPATCHED_ACCESSIBILITY"
        const val DELIVERY_FAILED = "SEND_FAILED"
        const val DELIVERY_SESSION_ENDED = "CANCELLED_SESSION_ENDED"
    }
}
