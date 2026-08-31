package com.huyang.luciddream.agent.tool

import com.huyang.luciddream.network.DeepSeekMessage
import com.huyang.luciddream.network.DeepSeekToolCall
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class OwnerToolResultStatus {
    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("blocked")
    BLOCKED,

    @SerialName("max_steps")
    MAX_STEPS,

    @SerialName("polling_timeout")
    POLLING_TIMEOUT,

    @SerialName("status_unknown")
    STATUS_UNKNOWN,

    @SerialName("cancelled_by_user")
    CANCELLED_BY_USER,

    @SerialName("denied")
    DENIED,

    @SerialName("preflight_failed")
    PREFLIGHT_FAILED,
}

@Serializable
data class OwnerToolResultPayload(
    val status: OwnerToolResultStatus,
    val task: String,
    val reason: String,
    @SerialName("task_id") val taskId: String? = null,
)

data class OwnerToolContinuation(
    val toolCallId: String,
    val task: String,
    val originalMessages: List<DeepSeekMessage>,
    val assistantToolCall: DeepSeekToolCall,
) {
    init {
        require(toolCallId.isNotBlank()) { "toolCallId must not be blank" }
        require(toolCallId == assistantToolCall.id) {
            "Continuation toolCallId must match the original assistant Tool Call"
        }
    }
}

internal val ownerToolResultJson = Json { explicitNulls = false }

internal fun OwnerToolResultPayload.toToolResultContent(): String =
    ownerToolResultJson.encodeToString(this)
