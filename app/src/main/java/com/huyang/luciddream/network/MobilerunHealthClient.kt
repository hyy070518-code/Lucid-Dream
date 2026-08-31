package com.huyang.luciddream.network

import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface MobilerunHealthResult {
    data object Success : MobilerunHealthResult
    data class Failure(val detail: String) : MobilerunHealthResult
}

object MobilerunPortalConfig {
    const val PING_URL = "http://127.0.0.1:8080/ping"
}

@Singleton
class MobilerunHealthClient @Inject constructor(
    private val client: OkHttpClient,
) {
    suspend fun check(): MobilerunHealthResult = checkAt(MobilerunPortalConfig.PING_URL)

    internal suspend fun checkAt(url: String): MobilerunHealthResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    MobilerunHealthResult.Success
                } else {
                    MobilerunHealthResult.Failure("HTTP ${response.code}")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            MobilerunHealthResult.Failure(error.diagnosticMessage())
        } catch (error: Exception) {
            MobilerunHealthResult.Failure(error.diagnosticMessage())
        }
    }

    private fun Throwable.diagnosticMessage(): String =
        message?.trim()?.takeIf { it.isNotEmpty() } ?: javaClass.simpleName
}
