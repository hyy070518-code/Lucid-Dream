package com.huyang.luciddream.ui.home

import com.huyang.luciddream.agent.tool.PendingToolProposal
import com.huyang.luciddream.agent.tool.OwnerToolResultPayload
import com.huyang.luciddream.agent.tool.OwnerToolResultStatus
import com.huyang.luciddream.policy.AndroidAgentRiskClassifier
import com.huyang.luciddream.policy.AndroidAgentToolContract
import com.huyang.luciddream.policy.OwnerToolProposalEvaluator
import com.huyang.luciddream.policy.ToolPolicyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerToolExecutionUiStatusTest {
    private val evaluation = OwnerToolProposalEvaluator(
        AndroidAgentRiskClassifier(),
        ToolPolicyEngine(),
    ).evaluate(
        PendingToolProposal(
            toolCallId = "call-current",
            toolName = AndroidAgentToolContract.TOOL_NAME,
            arguments = mapOf(AndroidAgentToolContract.ARGUMENT_TASK to "给 DemoContact 发送消息"),
            task = "给 DemoContact 发送消息",
        ),
    )

    @Test
    fun userCancellationClearsPendingConfirmationWithoutExecutionState() {
        val waiting = OwnerToolExecutionUiStatus.WaitingConfirmation(evaluation)

        assertEquals(
            OwnerToolExecutionUiStatus.Finished(
                "call-current",
                OwnerToolResultPayload(
                    OwnerToolResultStatus.CANCELLED_BY_USER,
                    "给 DemoContact 发送消息",
                    "用户取消了本次手机操作",
                ),
            ),
            waiting.cancelConfirmation("call-current"),
        )
    }

    @Test
    fun staleConfirmationIdCannotResolveCurrentProposal() {
        val waiting = OwnerToolExecutionUiStatus.WaitingConfirmation(evaluation)

        assertNull(waiting.confirmationFor("call-old"))
        assertEquals(waiting, waiting.cancelConfirmation("call-old"))
    }

    @Test
    fun runningAgentStateBlocksOwnerAndIndependentSecondSubmission() {
        val state = HomeInteractionState(
            agentTaskStatus = AgentTaskUiStatus.Running("task-1"),
            ownerToolExecutionStatus = OwnerToolExecutionUiStatus.WaitingConfirmation(evaluation),
        )

        assertFalse(state.agentTaskStatus.allowsSubmission)
        assertEquals(state, state.prepareNewAgentTask())
    }

    @Test
    fun onlyMatchingOwnerTaskCanUpdateOwnerLifecycle() {
        val executing = OwnerToolExecutionUiStatus.Executing(
            toolCallId = "call-current",
            task = "打开微信",
            riskCategory = com.huyang.luciddream.policy.ToolRiskCategory.LOW,
            agentTaskId = "task-current",
        )

        assertEquals(
            executing,
            executing.withAgentTaskStatus(
                "task-old",
                AgentTaskUiStatus.Completed("task-old", "old result"),
            ),
        )
        assertEquals(
            OwnerToolExecutionUiStatus.Finished(
                "call-current",
                OwnerToolResultPayload(
                    status = OwnerToolResultStatus.COMPLETED,
                    task = "打开微信",
                    reason = "done",
                    taskId = "task-current",
                ),
            ),
            executing.withAgentTaskStatus(
                "task-current",
                AgentTaskUiStatus.Completed("task-current", "done"),
            ),
        )
    }

    @Test
    fun followUpFailurePreservesRealAndroidTerminalResult() {
        val terminal = OwnerToolExecutionUiStatus.Finished(
            "call-current",
            OwnerToolResultPayload(
                status = OwnerToolResultStatus.COMPLETED,
                task = "打开微信",
                reason = "微信已成功打开",
                taskId = "task-current",
            ),
        )
        val state = HomeInteractionState(
            isLoading = true,
            agentTaskStatus = AgentTaskUiStatus.Completed("task-current", "微信已成功打开"),
            ownerToolExecutionStatus = terminal,
        )

        val updated = state.withOwnerToolContinuationFailure("网络连接失败")

        assertEquals(terminal, updated.ownerToolExecutionStatus)
        assertEquals(state.agentTaskStatus, updated.agentTaskStatus)
        assertFalse(updated.isLoading)
    }

    @Test
    fun everyAgentTerminalStateMapsToAnExplicitToolResultStatus() {
        val executing = OwnerToolExecutionUiStatus.Executing(
            toolCallId = "call-current",
            task = "执行手机任务",
            riskCategory = com.huyang.luciddream.policy.ToolRiskCategory.LOW,
            agentTaskId = "task-current",
        )
        val cases = listOf(
            AgentTaskUiStatus.Failed("failed", "task-current") to OwnerToolResultStatus.FAILED,
            AgentTaskUiStatus.Blocked("task-current", "blocked") to OwnerToolResultStatus.BLOCKED,
            AgentTaskUiStatus.MaxSteps("task-current", "max") to OwnerToolResultStatus.MAX_STEPS,
            AgentTaskUiStatus.PollingTimeout("task-current") to
                OwnerToolResultStatus.POLLING_TIMEOUT,
            AgentTaskUiStatus.StatusUnknown("task-current", "unknown") to
                OwnerToolResultStatus.STATUS_UNKNOWN,
        )

        cases.forEach { (agentStatus, expectedToolStatus) ->
            val finished = executing.withAgentTaskStatus(
                "task-current",
                agentStatus,
            ) as OwnerToolExecutionUiStatus.Finished
            assertEquals(expectedToolStatus, finished.result.status)
            assertEquals("task-current", finished.result.taskId)
        }
    }
}
