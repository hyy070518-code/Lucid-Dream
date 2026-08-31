package com.huyang.luciddream.agent.tool

/**
 * Extension boundary for a future Codex-style agent loop.
 * Phase 2 deliberately provides no concrete tools and never executes a tool call.
 */
interface ToolRegistry {
    fun definitions(): List<AgentToolDefinition>
    suspend fun execute(call: AgentToolCall): AgentToolResult
}

data class AgentToolDefinition(
    val name: String,
    val description: String,
    val inputJsonSchema: String,
)

data class AgentToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

data class AgentToolResult(
    val callId: String,
    val success: Boolean,
    val output: String,
)
