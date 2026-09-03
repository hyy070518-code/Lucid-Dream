package com.huyang.luciddream.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huyang.luciddream.agent.AgentEngine
import com.huyang.luciddream.agent.AgentEngineResult
import com.huyang.luciddream.agent.tool.OwnerToolExecutionResult
import com.huyang.luciddream.agent.tool.OwnerToolExecutor
import com.huyang.luciddream.agent.tool.OwnerToolContinuation
import com.huyang.luciddream.agent.tool.OwnerToolResultPayload
import com.huyang.luciddream.agent.tool.OwnerToolResultStatus
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import com.huyang.luciddream.data.repository.OwnerChatRepository
import com.huyang.luciddream.session.DelegationManager
import com.huyang.luciddream.network.AgentTaskClient
import com.huyang.luciddream.network.AgentTaskCancelResult
import com.huyang.luciddream.network.AgentServerTaskStatus
import com.huyang.luciddream.network.AgentTaskPoller
import com.huyang.luciddream.network.AgentTaskPollingOutcome
import com.huyang.luciddream.network.AgentTaskStatusSnapshot
import com.huyang.luciddream.network.AgentTaskSubmitResult
import com.huyang.luciddream.policy.EvaluatedToolProposal
import com.huyang.luciddream.policy.ToolPolicyDecisionType
import com.huyang.luciddream.policy.ToolRiskCategory
import com.huyang.luciddream.settings.AgentTokenRepository
import com.huyang.luciddream.settings.ApiSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AgentTaskUiStatus {
    val allowsSubmission: Boolean
    val allowsCancellation: Boolean
        get() = false
    val taskId: String?
        get() = null

    data object Idle : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data object Submitting : AgentTaskUiStatus {
        override val allowsSubmission = false
    }

    data class Queued(override val taskId: String) : AgentTaskUiStatus {
        override val allowsSubmission = false
        override val allowsCancellation = true
    }

    data class Running(override val taskId: String) : AgentTaskUiStatus {
        override val allowsSubmission = false
        override val allowsCancellation = true
    }

    data class Cancelling(override val taskId: String) : AgentTaskUiStatus {
        override val allowsSubmission = false
    }

    data class Completed(
        override val taskId: String,
        val reason: String,
    ) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class Blocked(
        override val taskId: String,
        val reason: String,
    ) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class Failed(
        val message: String,
        override val taskId: String? = null,
    ) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class MaxSteps(
        override val taskId: String,
        val reason: String,
    ) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class PollingTimeout(override val taskId: String) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class StatusUnknown(
        override val taskId: String,
        val message: String,
    ) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class PreflightFailed(val message: String) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }

    data class Cancelled(
        override val taskId: String,
        val reason: String,
    ) : AgentTaskUiStatus {
        override val allowsSubmission = true
    }
}

internal fun AgentTaskUiStatus.toCancellingOrNull(): AgentTaskUiStatus.Cancelling? =
    if (allowsCancellation) {
        taskId?.let(AgentTaskUiStatus::Cancelling)
    } else {
        null
    }

sealed interface OwnerToolExecutionUiStatus {
    data object Idle : OwnerToolExecutionUiStatus

    data class WaitingConfirmation(
        val evaluation: EvaluatedToolProposal,
    ) : OwnerToolExecutionUiStatus

    data class Executing(
        val toolCallId: String,
        val task: String,
        val riskCategory: ToolRiskCategory,
        val agentTaskId: String? = null,
    ) : OwnerToolExecutionUiStatus

    data class Finished(
        val toolCallId: String,
        val result: OwnerToolResultPayload,
    ) : OwnerToolExecutionUiStatus
}

data class HomeUiState(
    val messages: List<OwnerChatMessageEntity> = emptyList(),
    val draft: String = "",
    val hasApiKey: Boolean = false,
    val model: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isAgentPanelVisible: Boolean = false,
    val agentTaskDraft: String = "",
    val agentTaskStatus: AgentTaskUiStatus = AgentTaskUiStatus.Idle,
    val ownerToolExecutionStatus: OwnerToolExecutionUiStatus = OwnerToolExecutionUiStatus.Idle,
)

internal data class HomeInteractionState(
    val draft: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isAgentPanelVisible: Boolean = false,
    val agentTaskDraft: String = "",
    val agentTaskStatus: AgentTaskUiStatus = AgentTaskUiStatus.Idle,
    val ownerToolExecutionStatus: OwnerToolExecutionUiStatus = OwnerToolExecutionUiStatus.Idle,
)

internal fun HomeInteractionState.withAcceptedAgentTask(taskId: String): HomeInteractionState = copy(
    isAgentPanelVisible = false,
    agentTaskStatus = AgentTaskUiStatus.Queued(taskId),
)

internal fun HomeInteractionState.prepareNewAgentTask(): HomeInteractionState =
    if (agentTaskStatus.allowsSubmission) {
        copy(
            isAgentPanelVisible = true,
            agentTaskDraft = "",
            agentTaskStatus = AgentTaskUiStatus.Idle,
            ownerToolExecutionStatus = OwnerToolExecutionUiStatus.Idle,
        )
    } else {
        this
    }

internal fun HomeInteractionState.withAgentTaskDraft(value: String): HomeInteractionState =
    if (agentTaskStatus.allowsSubmission) {
        copy(agentTaskDraft = value)
    } else {
        this
    }

internal fun HomeInteractionState.withPolledAgentTaskStatus(
    taskId: String,
    status: AgentTaskUiStatus,
): HomeInteractionState {
    if (agentTaskStatus.taskId != taskId) return this
    if (
        agentTaskStatus is AgentTaskUiStatus.Cancelling &&
        (status is AgentTaskUiStatus.Queued || status is AgentTaskUiStatus.Running)
    ) {
        return this
    }
    return copy(agentTaskStatus = status)
}

internal fun HomeInteractionState.withAgentAndOwnerToolStatus(
    taskId: String,
    status: AgentTaskUiStatus,
): HomeInteractionState {
    val updated = withPolledAgentTaskStatus(taskId, status)
    return updated.copy(
        ownerToolExecutionStatus = updated.ownerToolExecutionStatus.withAgentTaskStatus(
            taskId,
            status,
        ),
    )
}

internal fun HomeInteractionState.withOwnerToolContinuationSuccess(): HomeInteractionState = copy(
    isLoading = false,
    agentTaskStatus = AgentTaskUiStatus.Idle,
    ownerToolExecutionStatus = OwnerToolExecutionUiStatus.Idle,
)

internal fun HomeInteractionState.withOwnerToolContinuationFailure(
    message: String,
): HomeInteractionState = copy(
    isLoading = false,
    error = "手机真实状态已保留；DeepSeek 最终回复生成失败：$message",
)

internal fun OwnerToolExecutionUiStatus.confirmationFor(
    toolCallId: String,
): EvaluatedToolProposal? = (this as? OwnerToolExecutionUiStatus.WaitingConfirmation)
    ?.evaluation
    ?.takeIf { it.proposal.toolCallId == toolCallId }

internal fun OwnerToolExecutionUiStatus.cancelConfirmation(
    toolCallId: String,
): OwnerToolExecutionUiStatus {
    val evaluation = confirmationFor(toolCallId) ?: return this
    return OwnerToolExecutionUiStatus.Finished(
        toolCallId = evaluation.proposal.toolCallId,
        result = OwnerToolResultPayload(
            status = OwnerToolResultStatus.CANCELLED_BY_USER,
            task = evaluation.proposal.task,
            reason = "用户取消了本次手机操作",
        ),
    )
}

internal fun OwnerToolExecutionUiStatus.withAgentTaskStatus(
    taskId: String,
    status: AgentTaskUiStatus,
): OwnerToolExecutionUiStatus {
    val executing = this as? OwnerToolExecutionUiStatus.Executing ?: return this
    if (executing.agentTaskId != taskId) return this
    return when (status) {
        is AgentTaskUiStatus.Completed -> executing.finished(
            OwnerToolResultStatus.COMPLETED,
            status.reason,
            taskId,
        )
        is AgentTaskUiStatus.Blocked -> executing.finished(
            OwnerToolResultStatus.BLOCKED,
            status.reason,
            taskId,
        )
        is AgentTaskUiStatus.Failed -> executing.finished(
            OwnerToolResultStatus.FAILED,
            status.message,
            taskId,
        )
        is AgentTaskUiStatus.MaxSteps -> executing.finished(
            OwnerToolResultStatus.MAX_STEPS,
            status.reason,
            taskId,
        )
        is AgentTaskUiStatus.PollingTimeout -> executing.finished(
            OwnerToolResultStatus.POLLING_TIMEOUT,
            "暂时无法确认任务最终状态，Agent 可能仍在后台执行",
            taskId,
        )
        is AgentTaskUiStatus.StatusUnknown -> executing.finished(
            OwnerToolResultStatus.STATUS_UNKNOWN,
            status.message,
            taskId,
        )
        is AgentTaskUiStatus.Cancelled -> executing.finished(
            OwnerToolResultStatus.CANCELLED_BY_USER,
            status.reason,
            taskId,
        )
        else -> this
    }
}

private fun OwnerToolExecutionUiStatus.Executing.finished(
    status: OwnerToolResultStatus,
    reason: String,
    taskId: String?,
): OwnerToolExecutionUiStatus.Finished = OwnerToolExecutionUiStatus.Finished(
    toolCallId = toolCallId,
    result = OwnerToolResultPayload(
        status = status,
        task = task,
        reason = reason,
        taskId = taskId,
    ),
)

internal fun AgentTaskStatusSnapshot.toUiStatus(): AgentTaskUiStatus = when (status) {
    AgentServerTaskStatus.QUEUED -> AgentTaskUiStatus.Queued(taskId)
    AgentServerTaskStatus.RUNNING -> AgentTaskUiStatus.Running(taskId)
    AgentServerTaskStatus.COMPLETED -> AgentTaskUiStatus.Completed(
        taskId = taskId,
        reason = reason ?: "任务已完成",
    )
    AgentServerTaskStatus.BLOCKED -> AgentTaskUiStatus.Blocked(
        taskId = taskId,
        reason = reason ?: "需要用户处理",
    )
    AgentServerTaskStatus.FAILED -> AgentTaskUiStatus.Failed(
        taskId = taskId,
        message = reason ?: "Agent 执行失败",
    )
    AgentServerTaskStatus.MAX_STEPS -> AgentTaskUiStatus.MaxSteps(
        taskId = taskId,
        reason = reason ?: "Agent 达到最大执行步数",
    )
    AgentServerTaskStatus.CANCELLED -> AgentTaskUiStatus.Cancelled(
        taskId = taskId,
        reason = reason ?: "用户已停止正在运行的 Android Agent 任务",
    )
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    settingsRepository: ApiSettingsRepository,
    chatRepository: OwnerChatRepository,
    private val delegationManager: DelegationManager,
    private val agentEngine: AgentEngine,
    private val agentTokenRepository: AgentTokenRepository,
    private val agentTaskClient: AgentTaskClient,
    private val agentTaskPoller: AgentTaskPoller,
    private val ownerToolExecutor: OwnerToolExecutor,
    private val agentTaskToastNotifier: AgentTaskToastNotifier,
) : ViewModel() {
    private val interaction = MutableStateFlow(HomeInteractionState())
    private var pollingJob: Job? = null
    private var activePollingTaskId: String? = null
    private var pendingToolContinuation: OwnerToolContinuation? = null

    init {
        viewModelScope.launch { delegationManager.recoverPendingSummaries() }
        viewModelScope.launch {
            interaction.collect { state ->
                agentTaskToastNotifier.onStatusChanged(state.agentTaskStatus)
            }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        chatRepository.observeMessages(),
        interaction,
    ) { settings, messages, local ->
        HomeUiState(
            messages = messages,
            draft = local.draft,
            hasApiKey = settings.hasApiKey,
            model = settings.model,
            isLoading = local.isLoading,
            error = local.error,
            isAgentPanelVisible = local.isAgentPanelVisible,
            agentTaskDraft = local.agentTaskDraft,
            agentTaskStatus = local.agentTaskStatus,
            ownerToolExecutionStatus = local.ownerToolExecutionStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun onDraftChange(value: String) {
        if (value.length <= MAX_DRAFT_LENGTH) {
            interaction.update { it.copy(draft = value, error = null) }
        }
    }

    fun dismissError() = interaction.update { it.copy(error = null) }

    fun openAgentPanel() = interaction.update(HomeInteractionState::prepareNewAgentTask)

    fun closeAgentPanel() = interaction.update { it.copy(isAgentPanelVisible = false) }

    fun onAgentTaskDraftChange(value: String) {
        if (value.length > MAX_AGENT_TASK_LENGTH) return
        interaction.update { it.withAgentTaskDraft(value) }
    }

    fun submitAgentTask() {
        val state = interaction.value
        if (!state.agentTaskStatus.allowsSubmission) return

        val task = state.agentTaskDraft.trim()
        if (task.isEmpty()) {
            interaction.update {
                it.copy(agentTaskStatus = AgentTaskUiStatus.Failed("请输入手机 Agent 任务"))
            }
            return
        }

        val token = agentTokenRepository.tokenForRequest()
        if (token == null) {
            interaction.update {
                it.copy(
                    agentTaskStatus = AgentTaskUiStatus.Failed(
                        "请先在设置中配置 Android Agent Token",
                    ),
                )
            }
            return
        }

        cancelAgentTaskPolling()
        interaction.update {
            it.copy(
                agentTaskStatus = AgentTaskUiStatus.Submitting,
                ownerToolExecutionStatus = OwnerToolExecutionUiStatus.Idle,
            )
        }
        viewModelScope.launch {
            when (val result = agentTaskClient.submit(task, token)) {
                is AgentTaskSubmitResult.Submitted -> {
                    interaction.update { it.withAcceptedAgentTask(result.taskId) }
                    startAgentTaskPolling(result.taskId, token)
                }
                is AgentTaskSubmitResult.Failure -> interaction.update {
                    it.copy(agentTaskStatus = AgentTaskUiStatus.Failed(result.message))
                }
            }
        }
    }

    fun cancelActiveAgentTask() {
        val previousStatus = interaction.value.agentTaskStatus
        val cancelling = previousStatus.toCancellingOrNull() ?: return
        val token = agentTokenRepository.tokenForRequest()
        if (token == null) {
            interaction.update { it.copy(error = "缺少 Android Agent Token，无法停止任务") }
            return
        }

        interaction.update { current ->
            if (current.agentTaskStatus == previousStatus) {
                current.copy(agentTaskStatus = cancelling, error = null)
            } else {
                current
            }
        }
        if (interaction.value.agentTaskStatus != cancelling) return

        viewModelScope.launch {
            when (val result = agentTaskClient.cancelTask(cancelling.taskId, token)) {
                is AgentTaskCancelResult.Accepted -> {
                    if (result.snapshot.status.isTerminal) {
                        val terminal = result.snapshot.toUiStatus()
                        interaction.update {
                            it.withAgentAndOwnerToolStatus(cancelling.taskId, terminal)
                        }
                        completeOwnerToolForTask(cancelling.taskId)
                    }
                }
                is AgentTaskCancelResult.Failure -> interaction.update { current ->
                    if (current.agentTaskStatus == cancelling) {
                        current.copy(
                            agentTaskStatus = previousStatus,
                            error = "停止任务失败：${result.message}",
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun startAgentTaskPolling(taskId: String, token: String) {
        cancelAgentTaskPolling()
        activePollingTaskId = taskId
        pollingJob = viewModelScope.launch {
            try {
                when (
                    val outcome = agentTaskPoller.poll(
                        taskId = taskId,
                        token = token,
                        onStatus = { snapshot ->
                            if (activePollingTaskId == taskId) {
                                interaction.update {
                                    it.withAgentAndOwnerToolStatus(taskId, snapshot.toUiStatus())
                                }
                            }
                        },
                    )
                ) {
                    is AgentTaskPollingOutcome.Terminal -> {
                        completeOwnerToolForTask(taskId)
                    }
                    is AgentTaskPollingOutcome.Failure -> {
                        if (activePollingTaskId == taskId) {
                            val status = AgentTaskUiStatus.StatusUnknown(taskId, outcome.message)
                            interaction.update {
                                it.withAgentAndOwnerToolStatus(
                                    taskId,
                                    status,
                                )
                            }
                            completeOwnerToolForTask(taskId)
                        }
                    }
                    AgentTaskPollingOutcome.Timeout -> {
                        if (activePollingTaskId == taskId) {
                            val status = AgentTaskUiStatus.PollingTimeout(taskId)
                            interaction.update {
                                it.withAgentAndOwnerToolStatus(
                                    taskId,
                                    status,
                                )
                            }
                            completeOwnerToolForTask(taskId)
                        }
                    }
                }
            } finally {
                if (activePollingTaskId == taskId) {
                    activePollingTaskId = null
                    pollingJob = null
                }
            }
        }
    }

    private fun cancelAgentTaskPolling() {
        activePollingTaskId = null
        pollingJob?.cancel()
        pollingJob = null
    }

    fun send() {
        val state = interaction.value
        val message = state.draft.trim()
        if (message.isEmpty() || state.isLoading) return
        if (
            state.ownerToolExecutionStatus is OwnerToolExecutionUiStatus.WaitingConfirmation ||
            state.ownerToolExecutionStatus is OwnerToolExecutionUiStatus.Executing
        ) {
            interaction.update {
                it.copy(error = "请先等待当前手机操作完成，或确认/取消待处理操作")
            }
            return
        }

        interaction.update {
            it.copy(
                draft = "",
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            when (val result = agentEngine.sendOwnerMessage(message)) {
                is AgentEngineResult.Success -> {
                    interaction.update { it.copy(isLoading = false) }
                    val evaluation = result.toolEvaluation
                    if (evaluation != null) {
                        val continuation = result.toolContinuation
                        if (
                            continuation == null ||
                            continuation.toolCallId != evaluation.proposal.toolCallId
                        ) {
                            interaction.update {
                                it.copy(error = "Tool Proposal continuation 无效，已安全停止")
                            }
                        } else {
                            pendingToolContinuation = continuation
                            handleOwnerToolEvaluation(evaluation)
                        }
                    }
                }
                is AgentEngineResult.Failure -> interaction.update {
                    it.copy(isLoading = false, error = result.message)
                }
            }
        }
    }

    fun cancelOwnerTool(toolCallId: String) {
        val waiting = interaction.value.ownerToolExecutionStatus
        val updated = waiting.cancelConfirmation(toolCallId)
        val finished = updated as? OwnerToolExecutionUiStatus.Finished ?: return
        val continuation = pendingToolContinuation
            ?.takeIf { it.toolCallId == toolCallId }
            ?: return
        interaction.update { it.copy(ownerToolExecutionStatus = finished) }
        viewModelScope.launch {
            completeOwnerToolResult(continuation, finished.result)
        }
    }

    fun confirmOwnerTool(toolCallId: String) {
        val evaluation = interaction.value.ownerToolExecutionStatus.confirmationFor(toolCallId)
            ?: return
        if (pendingToolContinuation?.toolCallId != toolCallId) return
        if (!interaction.value.agentTaskStatus.allowsSubmission) return
        viewModelScope.launch {
            executeOwnerTool(evaluation, confirmedToolCallId = toolCallId)
        }
    }

    private suspend fun handleOwnerToolEvaluation(evaluation: EvaluatedToolProposal) {
        val proposal = evaluation.proposal
        when (evaluation.policyDecision.decision) {
            ToolPolicyDecisionType.DENY -> finishOwnerToolWithoutExecution(
                proposal = proposal,
                status = OwnerToolResultStatus.DENIED,
                reason = evaluation.policyDecision.reason,
            )
            ToolPolicyDecisionType.REQUIRE_CONFIRMATION -> interaction.update {
                it.copy(
                    ownerToolExecutionStatus =
                        OwnerToolExecutionUiStatus.WaitingConfirmation(evaluation),
                )
            }
            ToolPolicyDecisionType.ALLOW -> executeOwnerTool(
                evaluation = evaluation,
                confirmedToolCallId = null,
            )
        }
    }

    private suspend fun executeOwnerTool(
        evaluation: EvaluatedToolProposal,
        confirmedToolCallId: String?,
    ) {
        val proposal = evaluation.proposal
        if (!interaction.value.agentTaskStatus.allowsSubmission) {
            finishOwnerToolWithoutExecution(
                proposal = proposal,
                status = OwnerToolResultStatus.FAILED,
                reason = "已有手机 Agent 任务正在执行",
            )
            return
        }

        interaction.update {
            it.copy(
                agentTaskStatus = AgentTaskUiStatus.Submitting,
                ownerToolExecutionStatus = OwnerToolExecutionUiStatus.Executing(
                    toolCallId = proposal.toolCallId,
                    task = proposal.task,
                    riskCategory = proposal.riskCategory,
                ),
            )
        }

        when (
            val result = ownerToolExecutor.execute(
                evaluation = evaluation,
                confirmedToolCallId = confirmedToolCallId,
            )
        ) {
            OwnerToolExecutionResult.ConfirmationRequired -> interaction.update {
                it.copy(
                    agentTaskStatus = AgentTaskUiStatus.Idle,
                    ownerToolExecutionStatus =
                        OwnerToolExecutionUiStatus.WaitingConfirmation(evaluation),
                )
            }
            OwnerToolExecutionResult.StaleConfirmation -> finishOwnerToolWithoutExecution(
                proposal = proposal,
                status = OwnerToolResultStatus.FAILED,
                reason = "确认已失效，请重新发起任务",
            )
            is OwnerToolExecutionResult.Denied -> finishOwnerToolWithoutExecution(
                proposal = proposal,
                status = OwnerToolResultStatus.DENIED,
                reason = result.message,
            )
            is OwnerToolExecutionResult.PreflightFailed -> finishOwnerToolWithoutExecution(
                proposal = proposal,
                status = OwnerToolResultStatus.PREFLIGHT_FAILED,
                reason = result.message,
                agentFailureStatus = AgentTaskUiStatus.PreflightFailed(result.message),
            )
            is OwnerToolExecutionResult.Failed -> finishOwnerToolWithoutExecution(
                proposal = proposal,
                status = OwnerToolResultStatus.FAILED,
                reason = result.message,
                agentFailureStatus = AgentTaskUiStatus.Failed(result.message),
            )
            is OwnerToolExecutionResult.Submitted -> {
                interaction.update { state ->
                    state.withAcceptedAgentTask(result.taskId).copy(
                        ownerToolExecutionStatus = OwnerToolExecutionUiStatus.Executing(
                            toolCallId = proposal.toolCallId,
                            task = proposal.task,
                            riskCategory = proposal.riskCategory,
                            agentTaskId = result.taskId,
                        ),
                    )
                }
                startAgentTaskPolling(result.taskId, result.token)
            }
        }
    }

    private suspend fun finishOwnerToolWithoutExecution(
        proposal: com.huyang.luciddream.agent.tool.PendingToolProposal,
        status: OwnerToolResultStatus,
        reason: String,
        agentFailureStatus: AgentTaskUiStatus? = null,
    ) {
        val continuation = pendingToolContinuation
            ?.takeIf { it.toolCallId == proposal.toolCallId }
            ?: return
        val result = OwnerToolResultPayload(
            status = status,
            task = proposal.task,
            reason = reason,
        )
        interaction.update {
            it.copy(
                agentTaskStatus = agentFailureStatus ?: AgentTaskUiStatus.Idle,
                ownerToolExecutionStatus = OwnerToolExecutionUiStatus.Finished(
                    proposal.toolCallId,
                    result,
                ),
            )
        }
        completeOwnerToolResult(continuation, result)
    }

    private suspend fun completeOwnerToolForTask(taskId: String) {
        val finished = interaction.value.ownerToolExecutionStatus
            as? OwnerToolExecutionUiStatus.Finished
            ?: return
        if (finished.result.taskId != taskId) return
        val continuation = pendingToolContinuation
            ?.takeIf { it.toolCallId == finished.toolCallId }
            ?: return
        completeOwnerToolResult(continuation, finished.result)
    }

    private suspend fun completeOwnerToolResult(
        continuation: OwnerToolContinuation,
        result: OwnerToolResultPayload,
    ) {
        if (pendingToolContinuation?.toolCallId != continuation.toolCallId) return
        pendingToolContinuation = null
        interaction.update { it.copy(isLoading = true, error = null) }

        when (val completion = agentEngine.completeOwnerAfterToolResult(continuation, result)) {
            is AgentEngineResult.Success ->
                interaction.update(HomeInteractionState::withOwnerToolContinuationSuccess)
            is AgentEngineResult.Failure -> interaction.update {
                it.withOwnerToolContinuationFailure(completion.message)
            }
        }
    }

    private companion object {
        const val MAX_DRAFT_LENGTH = 4_000
        const val MAX_AGENT_TASK_LENGTH = 4_000
    }
}
