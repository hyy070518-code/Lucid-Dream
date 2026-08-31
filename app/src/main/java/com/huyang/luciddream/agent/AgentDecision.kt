package com.huyang.luciddream.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AgentDecision(
    val action: AgentAction,
    val reply: String? = null,
    val intent: AgentIntent,
    @SerialName("needs_owner") val needsOwner: Boolean,
    val urgency: AgentUrgency,
    val reason: String,
)

@Serializable
enum class AgentAction {
    REPLY,
    RECORD_ONLY,
    WAIT,
    END_CONVERSATION,
    ESCALATE,
}

@Serializable
enum class AgentIntent {
    ASK_PURPOSE,
    COLLECT_DETAILS,
    ACKNOWLEDGE,
    DECLINE_DECISION,
    IDENTITY_EXPLANATION,
    FINISH,
    OTHER,
}

@Serializable
enum class AgentUrgency {
    LOW,
    MEDIUM,
    HIGH,
}
