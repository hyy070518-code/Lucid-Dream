package com.huyang.luciddream.policy

import com.huyang.luciddream.agent.tool.PendingToolProposal
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerToolProposalEvaluatorTest {
    private val evaluator = OwnerToolProposalEvaluator(
        riskClassifier = AndroidAgentRiskClassifier(),
        policyEngine = ToolPolicyEngine(),
    )

    @Test
    fun ownerLowRiskProposalIsAllowedButOnlyAsPolicyData() {
        val evaluated = evaluator.evaluate(proposal("打开微信"))

        assertEquals(ToolRiskCategory.LOW, evaluated.proposal.riskCategory)
        assertEquals(ToolRiskCategory.LOW, evaluated.request.riskCategory)
        assertEquals(ToolPolicyDecisionType.ALLOW, evaluated.policyDecision.decision)
    }

    @Test
    fun ownerHighRiskProposalRequiresConfirmation() {
        val evaluated = evaluator.evaluate(proposal("给 DemoContact 发送“我到了”"))

        assertEquals(ToolRiskCategory.HIGH, evaluated.request.riskCategory)
        assertEquals(
            ToolPolicyDecisionType.REQUIRE_CONFIRMATION,
            evaluated.policyDecision.decision,
        )
    }

    @Test
    fun ownerAuthenticationProposalIsDenied() {
        val evaluated = evaluator.evaluate(proposal("帮我输入收到的验证码"))

        assertEquals(ToolRiskCategory.AUTHENTICATION, evaluated.request.riskCategory)
        assertEquals(ToolPolicyDecisionType.DENY, evaluated.policyDecision.decision)
    }

    @Test
    fun unknownClassificationFailsClosed() {
        val evaluated = evaluator.evaluate(proposal("帮我弄一下"))

        assertEquals(ToolRiskCategory.UNKNOWN, evaluated.request.riskCategory)
        assertEquals(ToolPolicyDecisionType.DENY, evaluated.policyDecision.decision)
    }

    @Test
    fun externalUntrustedSourceRemainsDeniedEvenForLowRiskTask() {
        val evaluated = evaluator.evaluate(
            proposal("打开微信").copy(source = ToolRequestSource.EXTERNAL_UNTRUSTED),
        )

        assertEquals(ToolRequestSource.EXTERNAL_UNTRUSTED, evaluated.request.source)
        assertEquals(ToolRiskCategory.LOW, evaluated.request.riskCategory)
        assertEquals(ToolPolicyDecisionType.DENY, evaluated.policyDecision.decision)
    }

    @Test
    fun preexistingModelLikeRiskValueIsIgnoredAndReclassifiedLocally() {
        val untrustedProposal = proposal("输入支付密码").copy(
            riskCategory = ToolRiskCategory.LOW,
        )

        val evaluated = evaluator.evaluate(untrustedProposal)

        assertEquals(ToolRiskCategory.AUTHENTICATION, evaluated.proposal.riskCategory)
        assertEquals(ToolRiskCategory.AUTHENTICATION, evaluated.request.riskCategory)
        assertEquals(ToolPolicyDecisionType.DENY, evaluated.policyDecision.decision)
    }

    private fun proposal(task: String) = PendingToolProposal(
        toolCallId = "call-123",
        toolName = AndroidAgentToolContract.TOOL_NAME,
        arguments = mapOf(AndroidAgentToolContract.ARGUMENT_TASK to task),
        task = task,
    )
}
