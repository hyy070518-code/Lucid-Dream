package com.huyang.luciddream.policy

import com.huyang.luciddream.agent.tool.PendingToolProposal
import javax.inject.Inject
import javax.inject.Singleton

data class EvaluatedToolProposal(
    val proposal: PendingToolProposal,
    val request: ToolRequest,
    val policyDecision: ToolPolicyDecision,
)

/** Produces Risk and Policy data only; it has no Tool or Android Agent execution dependency. */
@Singleton
class OwnerToolProposalEvaluator @Inject constructor(
    private val riskClassifier: AndroidAgentRiskClassifier,
    private val policyEngine: ToolPolicyEngine,
) {
    fun evaluate(proposal: PendingToolProposal): EvaluatedToolProposal {
        // Ignore any pre-existing risk on the proposal. Only this local classifier is authoritative.
        val locallyClassified = proposal.copy(
            riskCategory = riskClassifier.classify(proposal.task),
        )
        val request = locallyClassified.toToolRequest()
        return EvaluatedToolProposal(
            proposal = locallyClassified,
            request = request,
            policyDecision = policyEngine.evaluate(request),
        )
    }
}
