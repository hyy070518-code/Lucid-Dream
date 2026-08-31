package com.huyang.luciddream.network

import com.huyang.luciddream.settings.ApiSettingsValidator
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class DeepSeekConnectionTester @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun test(baseUrl: String, apiKey: String): ConnectionTestResult = withContext(Dispatchers.IO) {
        ApiSettingsValidator.validateBaseUrl(baseUrl)?.let {
            return@withContext ConnectionTestResult.Failure(it)
        }
        if (apiKey.isBlank()) {
            return@withContext ConnectionTestResult.Failure("请先保存 DeepSeek API Key")
        }

        val modelsUrl = baseUrl.trim().trimEnd('/').toHttpUrl().newBuilder()
            .addPathSegment("models")
            .build()
        val request = Request.Builder()
            .url(modelsUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ConnectionTestResult.Success
                    response.code == 401 || response.code == 403 ->
                        ConnectionTestResult.Failure("认证失败，请检查 API Key")
                    response.code == 429 ->
                        ConnectionTestResult.Failure("请求过于频繁或账户额度不足（HTTP 429）")
                    else -> ConnectionTestResult.Failure("DeepSeek 返回 HTTP ${response.code}")
                }
            }
        } catch (_: IOException) {
            ConnectionTestResult.Failure("网络连接失败，请检查网络、Base URL 或稍后重试")
        } catch (_: Exception) {
            ConnectionTestResult.Failure("连接测试失败，请检查配置")
        }
    }
}
