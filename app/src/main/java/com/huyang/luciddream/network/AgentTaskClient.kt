package com.huyang.luciddream.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface AgentTaskSubmitResult {
    data class Submitted(
        val taskId: String,
        val status: String,
    ) : AgentTaskSubmitResult

    data class Failure(val message: String) : AgentTaskSubmitResult
}

enum class AgentServerTaskStatus(
    val wireValue: String,
    val isTerminal: Boolean,
) {
    QUEUED("queued", false),
    RUNNING("running", false),
    COMPLETED("completed", true),
    BLOCKED("blocked", true),
    FAILED("failed", true),
    MAX_STEPS("max_steps", true),
    ;

    companion object {
        fun fromWireValue(value: String): AgentServerTaskStatus? = entries.firstOrNull {
            it.wireValue == value
        }
    }
}

data class AgentTaskStatusSnapshot(
    val taskId: String,
    val status: AgentServerTaskStatus,
    val reason: String?,
)

sealed interface AgentTaskStatusResult {
    data class Success(val snapshot: AgentTaskStatusSnapshot) : AgentTaskStatusResult
    data class Failure(val message: String) : AgentTaskStatusResult
}

@Serializable
private data class AgentTaskRequest(
    val task: String,
)

@Serializable
private data class AgentTaskAcceptedResponse(
    @SerialName("task_id") val taskId: String,
    val status: String,
)

@Serializable
private data class AgentTaskStatusResponse(
    @SerialName("task_id") val taskId: String,
    val status: String,
    val reason: String? = null,
)

@Singleton
class AgentTaskClient @Inject constructor(
    private val client: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun submit(task: String, token: String): AgentTaskSubmitResult =
        submitAt(AgentServerConfig.TASKS_URL, task, token)

    suspend fun getStatus(taskId: String, token: String): AgentTaskStatusResult {
        val url = AgentServerConfig.TASKS_URL.toHttpUrl().newBuilder()
            .addPathSegment(taskId)
            .build()
            .toString()
        return getStatusAt(url, taskId, token)
    }

    internal suspend fun getStatusAt(
        url: String,
        taskId: String,
        token: String,
    ): AgentTaskStatusResult = withContext(Dispatchers.IO) {
        if (taskId.isBlank()) {
            return@withContext AgentTaskStatusResult.Failure("task_id 不能为空")
        }
        if (token.isBlank()) {
            return@withContext AgentTaskStatusResult.Failure("缺少 Android Agent Token")
        }

        try {
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AgentTaskStatusResult.Failure(
                        httpFailure(response.code),
                    )
                }

                val decoded = try {
                    json.decodeFromString<AgentTaskStatusResponse>(response.body.string())
                } catch (_: SerializationException) {
                    return@withContext AgentTaskStatusResult.Failure(
                        "Agent 任务状态 JSON 无法解析",
                    )
                }

                if (decoded.taskId != taskId) {
                    return@withContext AgentTaskStatusResult.Failure(
                        "Agent 服务返回的 task_id 不匹配",
                    )
                }

                val status = AgentServerTaskStatus.fromWireValue(decoded.status)
                    ?: return@withContext AgentTaskStatusResult.Failure(
                        "Agent 服务返回未知任务状态：${decoded.status}",
                    )

                AgentTaskStatusResult.Success(
                    AgentTaskStatusSnapshot(
                        taskId = decoded.taskId,
                        status = status,
                        reason = decoded.reason,
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            AgentTaskStatusResult.Failure("网络请求失败：${error.diagnosticMessage()}")
        } catch (error: Exception) {
            AgentTaskStatusResult.Failure("任务状态查询失败：${error.diagnosticMessage()}")
        }
    }

    internal suspend fun submitAt(
        url: String,
        task: String,
        token: String,
    ): AgentTaskSubmitResult = withContext(Dispatchers.IO) {
        val trimmedTask = task.trim()
        if (trimmedTask.isEmpty()) {
            return@withContext AgentTaskSubmitResult.Failure("任务不能为空")
        }
        if (token.isBlank()) {
            return@withContext AgentTaskSubmitResult.Failure("缺少 Android Agent Token")
        }

        try {
            val payload = json.encodeToString(AgentTaskRequest(task = trimmedTask))
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Accept", "application/json")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code != HTTP_ACCEPTED) {
                    return@withContext AgentTaskSubmitResult.Failure(
                        httpFailure(response.code),
                    )
                }

                val accepted = try {
                    json.decodeFromString<AgentTaskAcceptedResponse>(response.body.string())
                } catch (_: SerializationException) {
                    return@withContext AgentTaskSubmitResult.Failure(
                        "Agent 服务响应 JSON 无法解析",
                    )
                }

                when {
                    accepted.taskId.isBlank() -> AgentTaskSubmitResult.Failure(
                        "Agent 服务响应缺少 task_id",
                    )
                    accepted.status != EXPECTED_STATUS -> AgentTaskSubmitResult.Failure(
                        "Agent 服务返回未知任务状态：${accepted.status}",
                    )
                    else -> AgentTaskSubmitResult.Submitted(
                        taskId = accepted.taskId,
                        status = accepted.status,
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            AgentTaskSubmitResult.Failure("网络请求失败：${error.diagnosticMessage()}")
        } catch (error: Exception) {
            AgentTaskSubmitResult.Failure("任务提交失败：${error.diagnosticMessage()}")
        }
    }

    private fun httpFailure(code: Int): String = when (code) {
        401, 403 -> "Agent 服务鉴权失败（HTTP $code）"
        404 -> "Agent 任务不存在（HTTP 404）"
        409 -> "Agent 服务正忙（HTTP 409）"
        in 500..599 -> "Agent 服务内部错误（HTTP $code）"
        else -> "Agent 服务返回 HTTP $code"
    }

    private fun Throwable.diagnosticMessage(): String =
        message?.trim()?.takeIf { it.isNotEmpty() } ?: javaClass.simpleName

    private companion object {
        const val HTTP_ACCEPTED = 202
        const val EXPECTED_STATUS = "queued"
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
