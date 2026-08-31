package com.huyang.luciddream.agent.tool

import com.huyang.luciddream.network.AgentExecutionPreflight
import com.huyang.luciddream.network.AgentExecutionPreflightResult
import com.huyang.luciddream.network.AgentTaskClient
import com.huyang.luciddream.network.AgentTaskSubmitResult
import com.huyang.luciddream.policy.AndroidAgentToolContract
import com.huyang.luciddream.policy.EvaluatedToolProposal
import com.huyang.luciddream.policy.ToolPolicyDecisionType
import com.huyang.luciddream.policy.ToolRequestSource
import com.huyang.luciddream.settings.AgentTokenRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OwnerToolExecutionResult {
    data object ConfirmationRequired : OwnerToolExecutionResult
    data object StaleConfirmation : OwnerToolExecutionResult
    data class Denied(val message: String) : OwnerToolExecutionResult
    data class PreflightFailed(val message: String) : OwnerToolExecutionResult
    data class Failed(val message: String) : OwnerToolExecutionResult
    data class Submitted(val taskId: String, val token: String) : OwnerToolExecutionResult
}

/** Executes one already-evaluated Owner proposal. It does not poll or call DeepSeek. */
@Singleton
class OwnerToolExecutor @Inject constructor(
    private val preflight: AgentExecutionPreflight,
    private val tokenRepository: AgentTokenRepository,
    private val taskClient: AgentTaskClient,
) {
    suspend fun execute(
        evaluation: EvaluatedToolProposal,
        confirmedToolCallId: String? = null,
    ): OwnerToolExecutionResult = executeOwnerToolWith(
        evaluation = evaluation,
        confirmedToolCallId = confirmedToolCallId,
        preflightCheck = preflight::check,
        tokenProvider = tokenRepository::tokenForRequest,
        submit = taskClient::submit,
    )
}

internal suspend fun executeOwnerToolWith(
    evaluation: EvaluatedToolProposal,
    confirmedToolCallId: String?,
    preflightCheck: suspend () -> AgentExecutionPreflightResult,
    tokenProvider: () -> String?,
    submit: suspend (task: String, token: String) -> AgentTaskSubmitResult,
): OwnerToolExecutionResult {
    val proposal = evaluation.proposal
    val request = evaluation.request
    val policy = evaluation.policyDecision

    if (
        proposal.source != ToolRequestSource.OWNER_CHAT ||
        request.source != ToolRequestSource.OWNER_CHAT
    ) {
        return OwnerToolExecutionResult.Denied("只有 Owner Chat 可以执行 Android Agent Tool")
    }
    if (
        request.toolName != AndroidAgentToolContract.TOOL_NAME ||
        request.action != AndroidAgentToolContract.ACTION_EXECUTE_TASK ||
        request.arguments[AndroidAgentToolContract.ARGUMENT_TASK] != proposal.task ||
        request.riskCategory != policy.riskCategory
    ) {
        return OwnerToolExecutionResult.Denied("Tool Proposal 与 Policy 数据不一致")
    }

    when (policy.decision) {
        ToolPolicyDecisionType.DENY -> {
            return OwnerToolExecutionResult.Denied(policy.reason)
        }
        ToolPolicyDecisionType.REQUIRE_CONFIRMATION -> when {
            confirmedToolCallId == null -> return OwnerToolExecutionResult.ConfirmationRequired
            confirmedToolCallId != proposal.toolCallId -> {
                return OwnerToolExecutionResult.StaleConfirmation
            }
        }
        ToolPolicyDecisionType.ALLOW -> Unit
    }

    when (val result = preflightCheck()) {
        is AgentExecutionPreflightResult.Failure -> {
            return OwnerToolExecutionResult.PreflightFailed(result.message)
        }
        AgentExecutionPreflightResult.Ready -> Unit
    }

    val token = tokenProvider()
        ?: return OwnerToolExecutionResult.Failed("请先在设置中配置 Android Agent Token")
    return when (val result = submit(proposal.task, token)) {
        is AgentTaskSubmitResult.Failure -> OwnerToolExecutionResult.Failed(result.message)
        is AgentTaskSubmitResult.Submitted -> OwnerToolExecutionResult.Submitted(
            taskId = result.taskId,
            token = token,
        )
    }
}
