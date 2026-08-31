package com.huyang.luciddream.data.repository

import com.huyang.luciddream.agent.AgentDecision
import com.huyang.luciddream.data.dao.OwnerChatDao
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class OwnerChatRepository @Inject constructor(
    private val dao: OwnerChatDao,
) {
    fun observeMessages(): Flow<List<OwnerChatMessageEntity>> = dao.observeRecent()

    suspend fun recentForContext(limit: Int = 20): List<OwnerChatMessageEntity> = dao.recent(limit)

    suspend fun addOwnerMessage(content: String) {
        dao.insert(
            OwnerChatMessageEntity(
                role = ROLE_OWNER,
                content = content,
                timestamp = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun addAgentDecision(decision: AgentDecision) {
        dao.insert(
            OwnerChatMessageEntity(
                role = ROLE_AGENT,
                content = decision.reply.orEmpty(),
                timestamp = System.currentTimeMillis(),
                action = decision.action.name,
                intent = decision.intent.name,
                needsOwner = decision.needsOwner,
                urgency = decision.urgency.name,
                reason = decision.reason,
            ),
        )
    }

    companion object {
        const val ROLE_OWNER = "OWNER"
        const val ROLE_AGENT = "AGENT"
    }
}
