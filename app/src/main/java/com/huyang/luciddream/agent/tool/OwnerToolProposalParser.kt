package com.huyang.luciddream.agent.tool

import com.huyang.luciddream.network.DeepSeekToolCall
import com.huyang.luciddream.policy.AndroidAgentToolContract
import com.huyang.luciddream.policy.ToolRequest
import com.huyang.luciddream.policy.ToolRequestSource
import com.huyang.luciddream.policy.ToolRiskCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

data class PendingToolProposal(
    val toolCallId: String,
    val toolName: String,
    val arguments: Map<String, String>,
    val task: String,
    val source: ToolRequestSource = ToolRequestSource.OWNER_CHAT,
    val riskCategory: ToolRiskCategory = ToolRiskCategory.UNKNOWN,
) {
    fun toToolRequest(): ToolRequest = ToolRequest(
        toolName = toolName,
        source = source,
        action = AndroidAgentToolContract.ACTION_EXECUTE_TASK,
        arguments = arguments,
        riskCategory = riskCategory,
        reason = "Owner Chat DeepSeek Tool Proposal，尚未执行",
    )
}

sealed interface OwnerToolProposalParseResult {
    data class Accepted(val proposal: PendingToolProposal) : OwnerToolProposalParseResult
    data class Rejected(val message: String) : OwnerToolProposalParseResult
}

@Singleton
class OwnerToolProposalParser @Inject constructor() {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        isLenient = false
    }

    fun parse(calls: List<DeepSeekToolCall>): OwnerToolProposalParseResult {
        if (calls.size != 1) {
            return OwnerToolProposalParseResult.Rejected("一次响应必须只包含一个 Tool Call")
        }

        val call = calls.single()
        if (call.id.isBlank() || call.type != FUNCTION_TYPE) {
            return OwnerToolProposalParseResult.Rejected("DeepSeek Tool Call 结构无效")
        }
        if (call.function.name != AndroidAgentToolContract.TOOL_NAME) {
            return OwnerToolProposalParseResult.Rejected("未知 Tool：${call.function.name}")
        }

        val arguments = try {
            json.decodeFromString<AndroidAgentArguments>(call.function.arguments)
        } catch (_: SerializationException) {
            return OwnerToolProposalParseResult.Rejected("android_agent arguments 无法解析或包含未授权字段")
        } catch (_: IllegalArgumentException) {
            return OwnerToolProposalParseResult.Rejected("android_agent arguments 无效")
        }
        val task = arguments.task.trim()
        if (task.isEmpty()) {
            return OwnerToolProposalParseResult.Rejected("android_agent task 不能为空")
        }
        if (task.length > MAX_TASK_LENGTH) {
            return OwnerToolProposalParseResult.Rejected("android_agent task 过长")
        }

        return OwnerToolProposalParseResult.Accepted(
            PendingToolProposal(
                toolCallId = call.id,
                toolName = AndroidAgentToolContract.TOOL_NAME,
                arguments = mapOf(AndroidAgentToolContract.ARGUMENT_TASK to task),
                task = task,
            ),
        )
    }

    @Serializable
    private data class AndroidAgentArguments(val task: String)

    private companion object {
        const val FUNCTION_TYPE = "function"
        const val MAX_TASK_LENGTH = 4_000
    }
}
