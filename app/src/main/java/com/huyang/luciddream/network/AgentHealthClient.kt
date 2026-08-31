package com.huyang.luciddream.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AgentHealthResult {
    data object Success : AgentHealthResult
    data class Failure(val detail: String) : AgentHealthResult
}

@Singleton
class AgentHealthClient @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun check(): AgentHealthResult = checkAt(AgentServerConfig.HEALTH_URL)

    internal suspend fun checkAt(url: String): AgentHealthResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext AgentHealthResult.Failure(
                        "Agent 服务返回 HTTP ${response.code}",
                    )
                }

                val status = try {
                    Json.parseToJsonElement(response.body.string())
                        .jsonObject["status"]
                        ?.jsonPrimitive
                        ?.contentOrNull
                } catch (_: Exception) {
                    return@withContext AgentHealthResult.Failure("响应不是有效的 health JSON")
                }

                if (status == "ok") {
                    AgentHealthResult.Success
                } else {
                    AgentHealthResult.Failure(
                        if (status == null) {
                            "health 响应缺少 status"
                        } else {
                            "health 状态异常：$status"
                        },
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            AgentHealthResult.Failure(error.diagnosticMessage())
        } catch (error: Exception) {
            AgentHealthResult.Failure(error.diagnosticMessage())
        }
    }

    private fun Throwable.diagnosticMessage(): String =
        message?.trim()?.takeIf { it.isNotEmpty() } ?: javaClass.simpleName
}
