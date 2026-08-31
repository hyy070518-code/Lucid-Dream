package com.huyang.luciddream.policy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provenance of a proposed tool request. This is deliberately separate from notification trust:
 * a tool request may eventually originate outside the notification pipeline.
 */
enum class ToolRequestSource {
    OWNER_CHAT,
    EXTERNAL_UNTRUSTED,
    SYSTEM,
    UNKNOWN,
}

enum class ToolRiskCategory {
    LOW,
    MEDIUM,
    HIGH,
    AUTHENTICATION,
    UNKNOWN,
}

data class ToolRequest(
    val toolName: String,
    val source: ToolRequestSource,
    val action: String,
    val arguments: Map<String, String> = emptyMap(),
    val riskCategory: ToolRiskCategory,
    val reason: String? = null,
)

enum class ToolPolicyDecisionType {
    ALLOW,
    REQUIRE_CONFIRMATION,
    DENY,
}

data class ToolPolicyDecision(
    val decision: ToolPolicyDecisionType,
    val reason: String,
    val riskCategory: ToolRiskCategory,
)

object AndroidAgentToolContract {
    const val TOOL_NAME = "android_agent"
    const val ACTION_EXECUTE_TASK = "execute_task"
    const val ARGUMENT_TASK = "task"
}

/**
 * App-owned, fail-closed authorization boundary for future tool execution.
 *
 * This class only produces a policy decision. It never invokes ToolRegistry, AgentTaskClient,
 * or any Android Agent endpoint, and model output cannot override these local rules.
 */
@Singleton
class ToolPolicyEngine @Inject constructor() {
    fun evaluate(request: ToolRequest): ToolPolicyDecision {
        if (request.toolName != AndroidAgentToolContract.TOOL_NAME) {
            return request.deny("未知 Tool，默认拒绝")
        }

        if (request.source == ToolRequestSource.EXTERNAL_UNTRUSTED) {
            return request.deny("外部不可信来源不得调用 Android Agent")
        }

        if (request.source != ToolRequestSource.OWNER_CHAT) {
            return request.deny("该请求来源未获得 Android Agent 权限")
        }

        if (request.action != AndroidAgentToolContract.ACTION_EXECUTE_TASK) {
            return request.deny("未知 Android Agent action，默认拒绝")
        }

        if (request.arguments[AndroidAgentToolContract.ARGUMENT_TASK].isNullOrBlank()) {
            return request.deny("Android Agent 请求缺少有效 task")
        }

        return when (request.riskCategory) {
            ToolRiskCategory.LOW -> request.decide(
                ToolPolicyDecisionType.ALLOW,
                "Owner 发起的低风险 Android Agent 请求允许通过 Policy",
            )
            ToolRiskCategory.MEDIUM -> request.decide(
                ToolPolicyDecisionType.REQUIRE_CONFIRMATION,
                "中风险 Android Agent 请求需要 Owner 明确确认",
            )
            ToolRiskCategory.HIGH -> request.decide(
                ToolPolicyDecisionType.REQUIRE_CONFIRMATION,
                "高风险 Android Agent 请求需要 Owner 明确确认",
            )
            ToolRiskCategory.AUTHENTICATION -> request.deny(
                "登录凭据、验证码或生物识别必须由用户本人完成",
            )
            ToolRiskCategory.UNKNOWN -> request.deny("无法确定风险等级，默认拒绝")
        }
    }

    private fun ToolRequest.deny(reason: String): ToolPolicyDecision = decide(
        decision = ToolPolicyDecisionType.DENY,
        reason = reason,
    )

    private fun ToolRequest.decide(
        decision: ToolPolicyDecisionType,
        reason: String,
    ): ToolPolicyDecision = ToolPolicyDecision(
        decision = decision,
        reason = reason,
        riskCategory = riskCategory,
    )
}
