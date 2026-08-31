package com.huyang.luciddream.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class SessionSummaryParseException(message: String) : Exception(message)

@Serializable
private data class SummaryResponse(val summary: String)

@Singleton
class SessionSummaryParser @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = false }

    @Throws(SessionSummaryParseException::class)
    fun parse(content: String): String {
        val response = try {
            json.decodeFromString<SummaryResponse>(removeOptionalCodeFence(content))
        } catch (_: SerializationException) {
            throw SessionSummaryParseException("Session Summary JSON 无法解析")
        } catch (_: IllegalArgumentException) {
            throw SessionSummaryParseException("Session Summary 字段无效")
        }
        val summary = response.summary.trim()
        if (summary.isEmpty() || summary.length > MAX_SUMMARY_LENGTH) {
            throw SessionSummaryParseException("Session Summary 内容无效")
        }
        return summary
    }

    private fun removeOptionalCodeFence(value: String): String {
        val trimmed = value.trim()
        if (!trimmed.startsWith("```")) return trimmed
        val firstLineEnd = trimmed.indexOf('\n')
        if (firstLineEnd < 0 || !trimmed.endsWith("```")) return trimmed
        return trimmed.substring(firstLineEnd + 1, trimmed.length - 3).trim()
    }

    private companion object {
        const val MAX_SUMMARY_LENGTH = 6_000
    }
}
