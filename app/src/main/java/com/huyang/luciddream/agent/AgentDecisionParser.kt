package com.huyang.luciddream.agent

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class AgentDecisionParseException(message: String) : Exception(message)

@Singleton
class AgentDecisionParser @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = false
    }

    @Throws(AgentDecisionParseException::class)
    fun parse(rawContent: String): AgentDecision {
        val normalized = removeOptionalCodeFence(rawContent.trim())
        if (normalized.isBlank()) throw AgentDecisionParseException("DeepSeek 返回了空内容")

        val decision = try {
            json.decodeFromString<AgentDecision>(normalized)
        } catch (_: SerializationException) {
            throw AgentDecisionParseException("DeepSeek 返回的 Agent Decision JSON 无法解析")
        } catch (_: IllegalArgumentException) {
            throw AgentDecisionParseException("DeepSeek 返回的 Agent Decision 字段无效")
        }

        if (decision.reason.isBlank()) {
            throw AgentDecisionParseException("Agent Decision 缺少 reason")
        }
        if (decision.reason.length > MAX_REASON_LENGTH) {
            throw AgentDecisionParseException("Agent Decision 的 reason 过长")
        }
        if (decision.action == AgentAction.REPLY && decision.reply.isNullOrBlank()) {
            throw AgentDecisionParseException("REPLY 决策缺少 reply")
        }
        if ((decision.reply?.length ?: 0) > MAX_REPLY_LENGTH) {
            throw AgentDecisionParseException("Agent Decision 的 reply 过长")
        }
        return decision.copy(reply = decision.reply?.trim(), reason = decision.reason.trim())
    }

    fun parseOwnerChat(rawContent: String): AgentDecision {
        val decision = parse(rawContent)
        if (decision.action != AgentAction.REPLY || decision.reply.isNullOrBlank()) {
            throw AgentDecisionParseException("Owner Chat 需要有效的 REPLY 决策")
        }
        return decision
    }

    private fun removeOptionalCodeFence(value: String): String {
        if (!value.startsWith("```")) return value
        val firstLineEnd = value.indexOf('\n')
        if (firstLineEnd < 0 || !value.endsWith("```")) return value
        return value.substring(firstLineEnd + 1, value.length - 3).trim()
    }

    private companion object {
        const val MAX_REPLY_LENGTH = 4_000
        const val MAX_REASON_LENGTH = 1_000
    }
}
