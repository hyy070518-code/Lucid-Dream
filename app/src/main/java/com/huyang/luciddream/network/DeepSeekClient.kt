package com.huyang.luciddream.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface DeepSeekCompletionResult {
    data class Success(
        val content: String,
        val toolCalls: List<DeepSeekToolCall> = emptyList(),
    ) : DeepSeekCompletionResult
    data class Failure(val message: String) : DeepSeekCompletionResult
}

@Singleton
class DeepSeekClient @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun completeStructured(
        baseUrl: String,
        model: String,
        apiKey: String,
        messages: List<DeepSeekMessage>,
    ): DeepSeekCompletionResult = complete(
        baseUrl = baseUrl,
        apiKey = apiKey,
        chatRequest = structuredDeepSeekRequest(model, messages),
    )

    suspend fun completeOwnerWithTools(
        baseUrl: String,
        model: String,
        apiKey: String,
        messages: List<DeepSeekMessage>,
    ): DeepSeekCompletionResult = complete(
        baseUrl = baseUrl,
        apiKey = apiKey,
        chatRequest = ownerToolDeepSeekRequest(model, messages),
    )

    suspend fun completeOwnerAfterToolResult(
        baseUrl: String,
        model: String,
        apiKey: String,
        originalMessages: List<DeepSeekMessage>,
        assistantToolCall: DeepSeekToolCall,
        toolResultContent: String,
    ): DeepSeekCompletionResult = complete(
        baseUrl = baseUrl,
        apiKey = apiKey,
        chatRequest = ownerToolContinuationDeepSeekRequest(
            model = model,
            originalMessages = originalMessages,
            assistantToolCall = assistantToolCall,
            toolResultContent = toolResultContent,
        ),
    )

    private suspend fun complete(
        baseUrl: String,
        apiKey: String,
        chatRequest: DeepSeekChatRequest,
    ): DeepSeekCompletionResult = withContext(Dispatchers.IO) {
        val url = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegments("chat/completions")
            .build()
        val payload = json.encodeToString(chatRequest)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext httpFailure(response.code)
                val body = response.body.string()
                val decoded = try {
                    json.decodeFromString<DeepSeekChatResponse>(body)
                } catch (_: SerializationException) {
                    return@withContext DeepSeekCompletionResult.Failure("DeepSeek 响应格式无法解析")
                }
                decoded.toCompletionResult()
            }
        } catch (_: IOException) {
            DeepSeekCompletionResult.Failure("网络连接失败，请检查网络后重试")
        } catch (_: IllegalArgumentException) {
            DeepSeekCompletionResult.Failure("DeepSeek Base URL 无效")
        } catch (_: Exception) {
            DeepSeekCompletionResult.Failure("DeepSeek 请求失败，请稍后重试")
        }
    }

    private fun httpFailure(code: Int): DeepSeekCompletionResult.Failure = when (code) {
        400 -> DeepSeekCompletionResult.Failure("请求参数或 Model 不兼容（HTTP 400）")
        401, 403 -> DeepSeekCompletionResult.Failure("认证失败，请检查 API Key")
        402 -> DeepSeekCompletionResult.Failure("DeepSeek 账户余额不足（HTTP 402）")
        404 -> DeepSeekCompletionResult.Failure("DeepSeek 接口或 Model 不存在（HTTP 404）")
        429 -> DeepSeekCompletionResult.Failure("请求过于频繁，请稍后重试（HTTP 429）")
        in 500..599 -> DeepSeekCompletionResult.Failure("DeepSeek 服务暂时不可用（HTTP $code）")
        else -> DeepSeekCompletionResult.Failure("DeepSeek 返回 HTTP $code")
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

internal fun DeepSeekChatResponse.toCompletionResult(): DeepSeekCompletionResult {
    val choice = choices.firstOrNull()
        ?: return DeepSeekCompletionResult.Failure("DeepSeek 没有返回候选结果")
    return when (choice.finishReason) {
        "tool_calls" -> {
            val calls = choice.message.toolCalls
            if (calls.isEmpty()) {
                DeepSeekCompletionResult.Failure("DeepSeek 返回了空 Tool Call")
            } else {
                DeepSeekCompletionResult.Success(content = "", toolCalls = calls)
            }
        }
        "length" -> DeepSeekCompletionResult.Failure("DeepSeek 输出被截断，请缩短消息后重试")
        "content_filter" -> DeepSeekCompletionResult.Failure("DeepSeek 未返回内容：触发服务端内容过滤")
        "insufficient_system_resource" -> DeepSeekCompletionResult.Failure("DeepSeek 当前资源繁忙，请稍后重试")
        else -> {
            if (choice.message.toolCalls.isNotEmpty()) {
                return DeepSeekCompletionResult.Failure("DeepSeek Tool Call 结束状态异常")
            }
            val content = choice.message.content?.trim().orEmpty()
            if (content.isEmpty()) {
                DeepSeekCompletionResult.Failure("DeepSeek 返回了空内容，请重试")
            } else {
                DeepSeekCompletionResult.Success(content)
            }
        }
    }
}
