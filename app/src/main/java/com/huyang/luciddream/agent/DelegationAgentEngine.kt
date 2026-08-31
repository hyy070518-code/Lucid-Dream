package com.huyang.luciddream.agent

import com.huyang.luciddream.data.repository.DelegationConversationStore
import com.huyang.luciddream.network.DeepSeekClient
import com.huyang.luciddream.network.DeepSeekCompletionResult
import com.huyang.luciddream.network.DeepSeekMessage
import com.huyang.luciddream.settings.ApiSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

enum class DelegationAgentFailureStage {
    CONFIGURATION,
    API,
    DECISION,
}

sealed interface DelegationAgentResult {
    data class Success(val decision: AgentDecision) : DelegationAgentResult
    data class Failure(
        val stage: DelegationAgentFailureStage,
        val message: String,
    ) : DelegationAgentResult
}

interface DelegationDecisionGenerator {
    suspend fun generate(
        sessionId: Long,
        contactKey: String,
        replyNumber: Int,
        replyLimit: Int,
    ): DelegationAgentResult
}

@Singleton
class DelegationAgentEngine @Inject constructor(
    private val settingsRepository: ApiSettingsRepository,
    private val conversationRepository: DelegationConversationStore,
    private val promptBuilder: PromptBuilder,
    private val decisionParser: AgentDecisionParser,
    private val deepSeekClient: DeepSeekClient,
) : DelegationDecisionGenerator {
    override suspend fun generate(
        sessionId: Long,
        contactKey: String,
        replyNumber: Int,
        replyLimit: Int,
    ): DelegationAgentResult {
        val settings = settingsRepository.settings.value
        val apiKey = settingsRepository.apiKeyForRequest()
            ?: return DelegationAgentResult.Failure(
                DelegationAgentFailureStage.CONFIGURATION,
                "未配置 DeepSeek API Key",
            )
        val firstContact = conversationRepository.isFirstContact(sessionId, contactKey)
        val context = conversationRepository.context(sessionId, contactKey)
        val requestMessages = buildList {
            add(
                DeepSeekMessage(
                    role = "system",
                    content = promptBuilder.buildDelegationPrompt(
                        firstContact = firstContact,
                        replyNumber = replyNumber,
                        replyLimit = replyLimit,
                    ),
                ),
            )
            addAll(context)
        }
        return when (
            val completion = deepSeekClient.completeStructured(
                baseUrl = settings.baseUrl,
                model = settings.model,
                apiKey = apiKey,
                messages = requestMessages,
            )
        ) {
            is DeepSeekCompletionResult.Failure -> DelegationAgentResult.Failure(
                DelegationAgentFailureStage.API,
                completion.message,
            )
            is DeepSeekCompletionResult.Success -> try {
                DelegationAgentResult.Success(decisionParser.parse(completion.content))
            } catch (error: AgentDecisionParseException) {
                DelegationAgentResult.Failure(
                    DelegationAgentFailureStage.DECISION,
                    error.message ?: "Agent Decision 解析失败",
                )
            }
        }
    }
}
