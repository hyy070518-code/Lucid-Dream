package com.huyang.luciddream.data.repository

import com.huyang.luciddream.agent.AgentAction
import com.huyang.luciddream.agent.AgentDecision
import com.huyang.luciddream.agent.AgentIntent
import com.huyang.luciddream.agent.AgentUrgency
import com.huyang.luciddream.data.dao.OwnerChatDao
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class OwnerChatRepositoryTest {
    @Test
    fun regularOwnerConversationHistoryStillStoresBothSidesInOrder() = runTest {
        val dao = FakeOwnerChatDao()
        val repository = OwnerChatRepository(dao)

        repository.addOwnerMessage("你好")
        repository.addAgentDecision(
            AgentDecision(
                action = AgentAction.REPLY,
                reply = "你好，有什么可以帮你？",
                intent = AgentIntent.ACKNOWLEDGE,
                needsOwner = false,
                urgency = AgentUrgency.LOW,
                reason = "普通聊天回复",
            ),
        )

        val context = repository.recentForContext()
        assertEquals(listOf(OwnerChatRepository.ROLE_OWNER, OwnerChatRepository.ROLE_AGENT), context.map { it.role })
        assertEquals(listOf("你好", "你好，有什么可以帮你？"), context.map { it.content })
    }

    private class FakeOwnerChatDao : OwnerChatDao {
        private val rows = mutableListOf<OwnerChatMessageEntity>()
        private val observed = MutableStateFlow<List<OwnerChatMessageEntity>>(emptyList())
        private var nextId = 1L

        override suspend fun insert(message: OwnerChatMessageEntity): Long {
            val inserted = message.copy(id = nextId++)
            rows += inserted
            observed.value = rows.toList()
            return inserted.id
        }

        override fun observeRecent(limit: Int): Flow<List<OwnerChatMessageEntity>> = observed

        override suspend fun recent(limit: Int): List<OwnerChatMessageEntity> = rows.takeLast(limit)
    }
}
