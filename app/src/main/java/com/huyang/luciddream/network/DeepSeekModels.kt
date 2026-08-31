package com.huyang.luciddream.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DeepSeekChatRequest(
    val model: String,
    val messages: List<DeepSeekMessage>,
    @SerialName("response_format") val responseFormat: DeepSeekResponseFormat = DeepSeekResponseFormat(),
    @SerialName("max_tokens") val maxTokens: Int = 800,
    val temperature: Double = 0.2,
    val stream: Boolean = false,
    val thinking: DeepSeekThinking = DeepSeekThinking(),
    val tools: List<DeepSeekToolDefinition>? = null,
    @SerialName("tool_choice") val toolChoice: String? = null,
)

@Serializable
data class DeepSeekMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
data class DeepSeekResponseFormat(
    val type: String = "json_object",
)

@Serializable
data class DeepSeekThinking(
    val type: String = "disabled",
)

@Serializable
data class DeepSeekToolDefinition(
    val type: String,
    val function: DeepSeekFunctionDefinition,
)

@Serializable
data class DeepSeekFunctionDefinition(
    val name: String,
    val description: String,
    val parameters: DeepSeekFunctionParameters,
)

@Serializable
data class DeepSeekFunctionParameters(
    val type: String,
    val properties: Map<String, DeepSeekFunctionProperty>,
    val required: List<String>,
    @SerialName("additionalProperties") val additionalProperties: Boolean,
)

@Serializable
data class DeepSeekFunctionProperty(
    val type: String,
    val description: String,
)

@Serializable
data class DeepSeekChatResponse(
    val choices: List<DeepSeekChoice> = emptyList(),
)

@Serializable
data class DeepSeekChoice(
    val message: DeepSeekResponseMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class DeepSeekResponseMessage(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<DeepSeekToolCall> = emptyList(),
)

@Serializable
data class DeepSeekToolCall(
    val id: String,
    val type: String,
    val function: DeepSeekToolCallFunction,
)

@Serializable
data class DeepSeekToolCallFunction(
    val name: String,
    val arguments: String,
)

object DeepSeekOwnerToolCatalog {
    val androidAgent = DeepSeekToolDefinition(
        type = "function",
        function = DeepSeekFunctionDefinition(
            name = "android_agent",
            description = "当用户明确要求在其 Android 手机上执行操作时使用，例如打开 App、导航到页面、查看手机中的界面或执行手机 UI 任务。",
            parameters = DeepSeekFunctionParameters(
                type = "object",
                properties = mapOf(
                    "task" to DeepSeekFunctionProperty(
                        type = "string",
                        description = "需要 Android Agent 在用户手机上完成的自然语言任务",
                    ),
                ),
                required = listOf("task"),
                additionalProperties = false,
            ),
        ),
    )
}

internal fun structuredDeepSeekRequest(
    model: String,
    messages: List<DeepSeekMessage>,
): DeepSeekChatRequest = DeepSeekChatRequest(
    model = model,
    messages = messages,
)

internal fun ownerToolDeepSeekRequest(
    model: String,
    messages: List<DeepSeekMessage>,
): DeepSeekChatRequest = DeepSeekChatRequest(
    model = model,
    messages = messages,
    tools = listOf(DeepSeekOwnerToolCatalog.androidAgent),
    toolChoice = "auto",
)

internal fun ownerToolContinuationDeepSeekRequest(
    model: String,
    originalMessages: List<DeepSeekMessage>,
    assistantToolCall: DeepSeekToolCall,
    toolResultContent: String,
): DeepSeekChatRequest = ownerToolDeepSeekRequest(
    model = model,
    messages = buildList {
        addAll(originalMessages)
        add(
            DeepSeekMessage(
                role = "assistant",
                toolCalls = listOf(assistantToolCall),
            ),
        )
        add(
            DeepSeekMessage(
                role = "tool",
                toolCallId = assistantToolCall.id,
                content = toolResultContent,
            ),
        )
    },
)
