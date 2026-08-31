package com.huyang.luciddream.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPolicyEngineTest {
    private val engine = ToolPolicyEngine()

    @Test
    fun ownerLowRiskAndroidAgentRequestIsAllowed() {
        assertDecision(
            ToolPolicyDecisionType.ALLOW,
            request(source = ToolRequestSource.OWNER_CHAT, risk = ToolRiskCategory.LOW),
        )
    }

    @Test
    fun ownerMediumRiskAndroidAgentRequestRequiresConfirmation() {
        assertDecision(
            ToolPolicyDecisionType.REQUIRE_CONFIRMATION,
            request(source = ToolRequestSource.OWNER_CHAT, risk = ToolRiskCategory.MEDIUM),
        )
    }

    @Test
    fun ownerHighRiskAndroidAgentRequestRequiresConfirmation() {
        assertDecision(
            ToolPolicyDecisionType.REQUIRE_CONFIRMATION,
            request(source = ToolRequestSource.OWNER_CHAT, risk = ToolRiskCategory.HIGH),
        )
    }

    @Test
    fun ownerAuthenticationRequestIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(
                source = ToolRequestSource.OWNER_CHAT,
                risk = ToolRiskCategory.AUTHENTICATION,
            ),
        )
    }

    @Test
    fun externalUntrustedLowRiskRequestIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(source = ToolRequestSource.EXTERNAL_UNTRUSTED, risk = ToolRiskCategory.LOW),
        )
    }

    @Test
    fun externalUntrustedHighRiskRequestIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(source = ToolRequestSource.EXTERNAL_UNTRUSTED, risk = ToolRiskCategory.HIGH),
        )
    }

    @Test
    fun externalUntrustedAuthenticationRequestIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(
                source = ToolRequestSource.EXTERNAL_UNTRUSTED,
                risk = ToolRiskCategory.AUTHENTICATION,
            ),
        )
    }

    @Test
    fun unknownToolIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(
                source = ToolRequestSource.OWNER_CHAT,
                risk = ToolRiskCategory.LOW,
                toolName = "unknown_tool",
            ),
        )
    }

    @Test
    fun unknownSourceIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(source = ToolRequestSource.UNKNOWN, risk = ToolRiskCategory.LOW),
        )
    }

    @Test
    fun systemSourceIsDeniedByDefault() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(source = ToolRequestSource.SYSTEM, risk = ToolRiskCategory.LOW),
        )
    }

    @Test
    fun unknownRiskIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(source = ToolRequestSource.OWNER_CHAT, risk = ToolRiskCategory.UNKNOWN),
        )
    }

    @Test
    fun unknownActionIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(
                source = ToolRequestSource.OWNER_CHAT,
                risk = ToolRiskCategory.LOW,
                action = "unknown_action",
            ),
        )
    }

    @Test
    fun missingTaskArgumentIsDenied() {
        assertDecision(
            ToolPolicyDecisionType.DENY,
            request(
                source = ToolRequestSource.OWNER_CHAT,
                risk = ToolRiskCategory.LOW,
                task = " ",
            ),
        )
    }

    private fun assertDecision(expected: ToolPolicyDecisionType, request: ToolRequest) {
        val decision = engine.evaluate(request)

        assertEquals(expected, decision.decision)
        assertEquals(request.riskCategory, decision.riskCategory)
    }

    private fun request(
        source: ToolRequestSource,
        risk: ToolRiskCategory,
        toolName: String = AndroidAgentToolContract.TOOL_NAME,
        action: String = AndroidAgentToolContract.ACTION_EXECUTE_TASK,
        task: String = "打开系统设置",
    ): ToolRequest = ToolRequest(
        toolName = toolName,
        source = source,
        action = action,
        arguments = mapOf(AndroidAgentToolContract.ARGUMENT_TASK to task),
        riskCategory = risk,
        reason = "test request",
    )
}
