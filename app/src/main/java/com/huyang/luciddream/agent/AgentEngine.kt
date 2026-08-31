package com.huyang.luciddream.agent

import com.huyang.luciddream.agent.tool.OwnerToolProposalParseResult
import com.huyang.luciddream.agent.tool.OwnerToolProposalParser
import com.huyang.luciddream.agent.tool.OwnerToolContinuation
import com.huyang.luciddream.agent.tool.OwnerToolResultPayload
import com.huyang.luciddream.agent.tool.PendingToolProposal
import com.huyang.luciddream.agent.tool.toToolResultContent
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.data.repository.OwnerChatRepository
import com.huyang.luciddream.network.DeepSeekClient
import com.huyang.luciddream.network.DeepSeekCompletionResult
import com.huyang.luciddream.network.DeepSeekMessage
import com.huyang.luciddream.policy.EvaluatedToolProposal
import com.huyang.luciddream.policy.OwnerToolProposalEvaluator
import com.huyang.luciddream.policy.ToolPolicyDecision
import com.huyang.luciddream.settings.ApiSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

sealed interface AgentEngineResult {
    data class Success(
        val decision: AgentDecision,
        val toolProposal: PendingToolProposal? = null,
        val toolPolicyDecision: ToolPolicyDecision? = null,
        val toolEvaluation: EvaluatedToolProposal? = null,
        val toolContinuation: OwnerToolContinuation? = null,
    ) : AgentEngineResult
    data class Failure(val message: String) : AgentEngineResult
}

@Singleton
class AgentEngine @Inject constructor(
    private val settingsRepository: ApiSettingsRepository,
    private val chatRepository: OwnerChatRepository,
    private val eventRepository: AppEventRepository,
    private val promptBuilder: PromptBuilder,
    private val decisionParser: AgentDecisionParser,
    private val deepSeekClient: DeepSeekClient,
    private val toolProposalParser: OwnerToolProposalParser,
    private val toolProposalEvaluator: OwnerToolProposalEvaluator,
) {
    private val json = Json { explicitNulls = false }

    suspend fun sendOwnerMessage(rawContent: String): AgentEngineResult {
        val content = rawContent.trim()
        if (content.isEmpty()) return AgentEngineResult.Failure("消息不能为空")
        if (content.length > MAX_OWNER_MESSAGE_LENGTH) {
            return AgentEngineResult.Failure("消息过长，请控制在 $MAX_OWNER_MESSAGE_LENGTH 字以内")
        }

        val settings = settingsRepository.settings.value
        val apiKey = settingsRepository.apiKeyForRequest()
            ?: return AgentEngineResult.Failure("请先在设置中保存 DeepSeek API Key")

        return try {
            chatRepository.addOwnerMessage(content)
            val context = chatRepository.recentForContext().map(::toDeepSeekMessage)
            val requestMessages = buildList {
                add(DeepSeekMessage(role = "system", content = promptBuilder.buildOwnerChatPrompt()))
                addAll(context)
            }
            when (
                val completion = deepSeekClient.completeOwnerWithTools(
                    baseUrl = settings.baseUrl,
                    model = settings.model,
                    apiKey = apiKey,
                    messages = requestMessages,
                )
            ) {
                is DeepSeekCompletionResult.Failure -> {
                    eventRepository.record("OWNER_CHAT", "DeepSeek NOT_COMPLETED：${completion.message}")
                    AgentEngineResult.Failure(completion.message)
                }
                is DeepSeekCompletionResult.Success -> {
                    if (completion.toolCalls.isNotEmpty()) {
                        return handleToolCalls(
                            calls = completion.toolCalls,
                            originalMessages = requestMessages,
                        )
                    }
                    val decision = try {
                        decisionParser.parseOwnerChat(completion.content)
                    } catch (error: AgentDecisionParseException) {
                        eventRepository.record("OWNER_CHAT", "Agent Decision INVALID：${error.message}")
                        return AgentEngineResult.Failure(error.message ?: "Agent Decision 解析失败")
                    }
                    chatRepository.addAgentDecision(decision)
                    eventRepository.record(
                        "OWNER_CHAT",
                        "Decision=${decision.action}, Intent=${decision.intent}, " +
                            "Urgency=${decision.urgency}, Reason=${decision.reason}",
                    )
                    AgentEngineResult.Success(decision)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            eventRepository.record("OWNER_CHAT", "本地 Agent 流程异常")
            AgentEngineResult.Failure("本地 Agent 流程异常，请重试")
        }
    }

    suspend fun completeOwnerAfterToolResult(
        continuation: OwnerToolContinuation,
        toolResult: OwnerToolResultPayload,
    ): AgentEngineResult {
        val settings = settingsRepository.settings.value
        val apiKey = settingsRepository.apiKeyForRequest()
            ?: return AgentEngineResult.Failure("请先在设置中保存 DeepSeek API Key")
        val content = toolResult.toToolResultContent()

        return try {
            eventRepository.record(
                "OWNER_CHAT_TOOL_RESULT",
                "id=${continuation.toolCallId}, status=${toolResult.status}, " +
                    "taskId=${toolResult.taskId ?: "none"}, " +
                    "reason=${toolResult.reason.replace('\n', ' ').take(MAX_TOOL_RESULT_LOG_REASON_LENGTH)}",
            )
            val completion = deepSeekClient.completeOwnerAfterToolResult(
                baseUrl = settings.baseUrl,
                model = settings.model,
                apiKey = apiKey,
                originalMessages = continuation.originalMessages,
                assistantToolCall = continuation.assistantToolCall,
                toolResultContent = content,
            )
            when (val accepted = acceptOwnerToolContinuationCompletion(completion)) {
                is OwnerToolContinuationCompletion.Failure -> {
                    eventRepository.record(
                        "OWNER_CHAT_TOOL_CONTINUATION",
                        "NOT_COMPLETED：${accepted.message}",
                    )
                    AgentEngineResult.Failure(accepted.message)
                }
                is OwnerToolContinuationCompletion.FinalContent -> {
                    val decision = try {
                        decisionParser.parseOwnerChat(accepted.content)
                    } catch (error: AgentDecisionParseException) {
                        eventRepository.record(
                            "OWNER_CHAT_TOOL_CONTINUATION",
                            "Agent Decision INVALID：${error.message}",
                        )
                        return AgentEngineResult.Failure(
                            error.message ?: "Tool Result 最终回复解析失败",
                        )
                    }
                    chatRepository.addAgentDecision(decision)
                    eventRepository.record(
                        "OWNER_CHAT_TOOL_CONTINUATION",
                        "COMPLETED：id=${continuation.toolCallId}, " +
                            "Decision=${decision.action}, Reason=${decision.reason}",
                    )
                    AgentEngineResult.Success(decision)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            eventRepository.record("OWNER_CHAT_TOOL_CONTINUATION", "本地 continuation 流程异常")
            AgentEngineResult.Failure("DeepSeek 最终回复生成失败，请查看手机任务真实结果")
        }
    }

    private suspend fun handleToolCalls(
        calls: List<com.huyang.luciddream.network.DeepSeekToolCall>,
        originalMessages: List<DeepSeekMessage>,
    ): AgentEngineResult = when (val parsed = toolProposalParser.parse(calls)) {
        is OwnerToolProposalParseResult.Rejected -> {
            eventRepository.record("OWNER_CHAT", "Tool Proposal REJECTED：${parsed.message}")
            AgentEngineResult.Failure(parsed.message)
        }
        is OwnerToolProposalParseResult.Accepted -> {
            val evaluation = toolProposalEvaluator.evaluate(parsed.proposal)
            val proposal = evaluation.proposal
            val policyDecision = evaluation.policyDecision
            val notice = AgentDecision(
                action = AgentAction.REPLY,
                reply = "已识别手机操作请求：\n${proposal.task}\n\n" +
                    "风险：${proposal.riskCategory}\n" +
                    "Policy：${policyDecision.decision}\n\n" +
                    "等待 App 按本地 Policy 处理。",
                intent = AgentIntent.ACKNOWLEDGE,
                needsOwner = true,
                urgency = AgentUrgency.LOW,
                reason = "Android Agent Tool Proposal 已完成本地风险分类和 Policy 判断",
            )
            eventRepository.record(
                "OWNER_CHAT_TOOL_PROPOSAL",
                "id=${proposal.toolCallId}, tool=${proposal.toolName}, " +
                    "source=${proposal.source}, risk=${proposal.riskCategory}, " +
                    "policy=${policyDecision.decision}, policyReason=${policyDecision.reason}, " +
                    "task=${proposal.task.replace('\n', ' ').take(MAX_PROPOSAL_LOG_TASK_LENGTH)}",
            )
            AgentEngineResult.Success(
                decision = notice,
                toolProposal = proposal,
                toolPolicyDecision = policyDecision,
                toolEvaluation = evaluation,
                toolContinuation = OwnerToolContinuation(
                    toolCallId = proposal.toolCallId,
                    task = proposal.task,
                    originalMessages = originalMessages,
                    assistantToolCall = calls.single(),
                ),
            )
        }
    }

    private fun toDeepSeekMessage(message: OwnerChatMessageEntity): DeepSeekMessage {
        if (message.role == OwnerChatRepository.ROLE_OWNER) {
            return DeepSeekMessage(role = "user", content = message.content)
        }
        val structuredContent = runCatching {
            json.encodeToString(
                AgentDecision(
                    action = AgentAction.valueOf(message.action ?: AgentAction.REPLY.name),
                    reply = message.content,
                    intent = AgentIntent.valueOf(message.intent ?: AgentIntent.OTHER.name),
                    needsOwner = message.needsOwner ?: false,
                    urgency = AgentUrgency.valueOf(message.urgency ?: AgentUrgency.LOW.name),
                    reason = message.reason ?: "历史 Owner Chat 回复",
                ),
            )
        }.getOrElse { message.content }
        return DeepSeekMessage(role = "assistant", content = structuredContent)
    }

    private companion object {
        const val MAX_OWNER_MESSAGE_LENGTH = 4_000
        const val MAX_PROPOSAL_LOG_TASK_LENGTH = 500
        const val MAX_TOOL_RESULT_LOG_REASON_LENGTH = 500
    }
}

internal sealed interface OwnerToolContinuationCompletion {
    data class FinalContent(val content: String) : OwnerToolContinuationCompletion
    data class Failure(val message: String) : OwnerToolContinuationCompletion
}

internal fun acceptOwnerToolContinuationCompletion(
    completion: DeepSeekCompletionResult,
): OwnerToolContinuationCompletion = when (completion) {
    is DeepSeekCompletionResult.Failure -> {
        OwnerToolContinuationCompletion.Failure(completion.message)
    }
    is DeepSeekCompletionResult.Success -> {
        if (completion.toolCalls.isNotEmpty()) {
            OwnerToolContinuationCompletion.Failure(
                "当前请求需要进一步手机操作，请重新确认或重新发起",
            )
        } else {
            OwnerToolContinuationCompletion.FinalContent(completion.content)
        }
    }
}
