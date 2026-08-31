package com.huyang.luciddream.agent.tool

import com.huyang.luciddream.network.AgentExecutionPreflightResult
import com.huyang.luciddream.network.AgentTaskSubmitResult
import com.huyang.luciddream.policy.AndroidAgentRiskClassifier
import com.huyang.luciddream.policy.AndroidAgentToolContract
import com.huyang.luciddream.policy.EvaluatedToolProposal
import com.huyang.luciddream.policy.OwnerToolProposalEvaluator
import com.huyang.luciddream.policy.ToolPolicyDecisionType
import com.huyang.luciddream.policy.ToolPolicyEngine
import com.huyang.luciddream.policy.ToolRequestSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerToolExecutorTest {
    private val evaluator = OwnerToolProposalEvaluator(
        AndroidAgentRiskClassifier(),
        ToolPolicyEngine(),
    )

    @Test
    fun ownerLowAllowRunsPreflightAndSubmitsExactlyOnce() = runTest {
        val probe = ExecutionProbe()
        val result = probe.execute(evaluation("打开微信"))

        assertTrue(result is OwnerToolExecutionResult.Submitted)
        assertEquals(1, probe.preflightCount)
        assertEquals(1, probe.submitCount)
    }

    @Test
    fun highRiskWaitsForConfirmationThenSubmitsExactlyOnce() = runTest {
        val evaluated = evaluation("给 DemoContact 发送消息")
        assertEquals(
            ToolPolicyDecisionType.REQUIRE_CONFIRMATION,
            evaluated.policyDecision.decision,
        )
        val beforeConfirmation = ExecutionProbe()
        val waiting = beforeConfirmation.execute(evaluated)
        assertEquals(OwnerToolExecutionResult.ConfirmationRequired, waiting)
        assertEquals(0, beforeConfirmation.preflightCount)
        assertEquals(0, beforeConfirmation.submitCount)

        val afterConfirmation = ExecutionProbe()
        val submitted = afterConfirmation.execute(
            evaluated,
            confirmedToolCallId = evaluated.proposal.toolCallId,
        )
        assertTrue(submitted is OwnerToolExecutionResult.Submitted)
        assertEquals(1, afterConfirmation.preflightCount)
        assertEquals(1, afterConfirmation.submitCount)
    }

    @Test
    fun authenticationDenyNeverRunsPreflightOrSubmit() = runTest {
        val probe = ExecutionProbe()
        val result = probe.execute(evaluation("输入验证码123456"), confirmedToolCallId = "call-123")

        assertTrue(result is OwnerToolExecutionResult.Denied)
        assertEquals(0, probe.preflightCount)
        assertEquals(0, probe.submitCount)
    }

    @Test
    fun unknownRiskNeverSubmits() = runTest {
        val probe = ExecutionProbe()
        val result = probe.execute(evaluation("帮我弄一下"))

        assertTrue(result is OwnerToolExecutionResult.Denied)
        assertEquals(0, probe.submitCount)
    }

    @Test
    fun externalUntrustedLowRiskNeverSubmits() = runTest {
        val external = evaluator.evaluate(
            proposal("打开微信").copy(source = ToolRequestSource.EXTERNAL_UNTRUSTED),
        )
        val probe = ExecutionProbe()
        val result = probe.execute(external)

        assertTrue(result is OwnerToolExecutionResult.Denied)
        assertEquals(0, probe.preflightCount)
        assertEquals(0, probe.submitCount)
    }

    @Test
    fun agentServerOfflinePreflightNeverSubmits() = runTest {
        val probe = ExecutionProbe(
            preflightResult = AgentExecutionPreflightResult.Failure(
                "Android Agent 服务未运行：Connection refused",
            ),
        )
        val result = probe.execute(evaluation("打开微信"))

        assertTrue(result is OwnerToolExecutionResult.PreflightFailed)
        assertEquals(1, probe.preflightCount)
        assertEquals(0, probe.submitCount)
    }

    @Test
    fun mobilerunOfflinePreflightNeverSubmits() = runTest {
        val probe = ExecutionProbe(
            preflightResult = AgentExecutionPreflightResult.Failure(
                "Mobilerun Portal REST Server 未运行：Connection refused",
            ),
        )
        val result = probe.execute(evaluation("打开微信"))

        assertTrue(result is OwnerToolExecutionResult.PreflightFailed)
        assertEquals(1, probe.preflightCount)
        assertEquals(0, probe.submitCount)
    }

    @Test
    fun staleConfirmationCannotSubmitAnyTask() = runTest {
        val probe = ExecutionProbe()
        val result = probe.execute(
            evaluation("给 DemoContact 发送消息"),
            confirmedToolCallId = "old-call",
        )

        assertEquals(OwnerToolExecutionResult.StaleConfirmation, result)
        assertEquals(0, probe.preflightCount)
        assertEquals(0, probe.submitCount)
    }

    private fun evaluation(task: String): EvaluatedToolProposal = evaluator.evaluate(proposal(task))

    private fun proposal(task: String) = PendingToolProposal(
        toolCallId = "call-123",
        toolName = AndroidAgentToolContract.TOOL_NAME,
        arguments = mapOf(AndroidAgentToolContract.ARGUMENT_TASK to task),
        task = task,
    )

    private class ExecutionProbe(
        private val preflightResult: AgentExecutionPreflightResult =
            AgentExecutionPreflightResult.Ready,
    ) {
        var preflightCount = 0
        var submitCount = 0

        suspend fun execute(
            evaluation: EvaluatedToolProposal,
            confirmedToolCallId: String? = null,
        ): OwnerToolExecutionResult = executeOwnerToolWith(
            evaluation = evaluation,
            confirmedToolCallId = confirmedToolCallId,
            preflightCheck = {
                preflightCount += 1
                preflightResult
            },
            tokenProvider = { "token" },
            submit = { _, _ ->
                submitCount += 1
                AgentTaskSubmitResult.Submitted("task-1", "queued")
            },
        )
    }
}
