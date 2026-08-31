package com.huyang.luciddream.policy

import com.huyang.luciddream.data.dao.ContactPolicyDao
import com.huyang.luciddream.data.dao.ReplyBudgetDao
import com.huyang.luciddream.data.entity.ContactPolicyEntity
import com.huyang.luciddream.data.entity.ReplyBudgetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyBudgetManagerTest {
    private val budgetDao = FakeReplyBudgetDao()
    private val policyDao = FakeContactPolicyDao()
    private val manager = ReplyBudgetManager(budgetDao, policyDao)
    private val contact = ContactIdentity("wechat|alice", "Alice", "com.tencent.mm")

    @Test
    fun `evaluating incoming messages does not consume reply budget`() = runTest {
        repeat(5) {
            val result = manager.evaluate(1L, contact, 3)
            assertEquals(0, result.used)
            assertEquals(3, result.limit)
        }
    }

    @Test
    fun `only three prepared replies can be committed for default contact`() = runTest {
        repeat(3) {
            val acquired = manager.acquirePermit(1L, contact, 3) as PermitResult.Acquired
            assertTrue(manager.commitPreparedReply(acquired.permit))
        }

        val exhausted = manager.acquirePermit(1L, contact, 3)
        assertTrue(exhausted is PermitResult.Exhausted)
        exhausted as PermitResult.Exhausted
        assertEquals(3, exhausted.used)
        assertEquals(3, exhausted.limit)
    }

    @Test
    fun `reservations prevent concurrent calls from exceeding limit and can be released`() = runTest {
        val permits = (1..3).map {
            (manager.acquirePermit(1L, contact, 3) as PermitResult.Acquired).permit
        }
        assertTrue(manager.acquirePermit(1L, contact, 3) is PermitResult.Exhausted)

        assertTrue(manager.release(permits.first()))
        assertTrue(manager.acquirePermit(1L, contact, 3) is PermitResult.Acquired)
    }

    @Test
    fun `allowlisted contact uses configured limit`() = runTest {
        policyDao.upsert(
            ContactPolicyEntity(
                contactKey = contact.key,
                sourcePackage = contact.sourcePackage,
                displayName = contact.displayName,
                isAllowlisted = true,
                replyLimit = 10,
                updatedAt = 1L,
            ),
        )

        val result = manager.evaluate(1L, contact, 3)
        assertEquals(10, result.limit)
    }

    @Test
    fun `new delegation session resets contact usage but keeps configured limit`() = runTest {
        repeat(3) {
            val acquired = manager.acquirePermit(1L, contact, 3) as PermitResult.Acquired
            manager.commitPreparedReply(acquired.permit)
        }
        assertTrue(manager.evaluate(1L, contact, 3) is BudgetStatus.Exhausted)

        val newSession = manager.evaluate(2L, contact, 3)
        assertTrue(newSession is BudgetStatus.Available)
        assertEquals(0, newSession.used)
        assertEquals(3, newSession.limit)
    }
}

private class FakeReplyBudgetDao : ReplyBudgetDao {
    private val rows = mutableMapOf<Pair<Long, String>, ReplyBudgetEntity>()
    private var nextId = 1L

    override suspend fun get(sessionId: Long, contactKey: String): ReplyBudgetEntity? =
        rows[sessionId to contactKey]

    override suspend fun insertIgnore(entity: ReplyBudgetEntity): Long {
        val key = entity.sessionId to entity.contactKey
        if (key in rows) return -1L
        val id = nextId++
        rows[key] = entity.copy(id = id)
        return id
    }

    override suspend fun updateLimit(
        sessionId: Long,
        contactKey: String,
        replyLimit: Int,
        updatedAt: Long,
    ): Int = update(sessionId, contactKey) { it.copy(replyLimit = replyLimit, updatedAt = updatedAt) }

    override suspend fun reserve(sessionId: Long, contactKey: String, updatedAt: Long): Int =
        update(sessionId, contactKey) {
            if (it.replyCount + it.reservedCount >= it.replyLimit) return@update null
            it.copy(reservedCount = it.reservedCount + 1, updatedAt = updatedAt)
        }

    override suspend fun commit(sessionId: Long, contactKey: String, updatedAt: Long): Int =
        update(sessionId, contactKey) {
            if (it.reservedCount <= 0) return@update null
            it.copy(
                reservedCount = it.reservedCount - 1,
                replyCount = it.replyCount + 1,
                updatedAt = updatedAt,
            )
        }

    override suspend fun release(sessionId: Long, contactKey: String, updatedAt: Long): Int =
        update(sessionId, contactKey) {
            if (it.reservedCount <= 0) return@update null
            it.copy(reservedCount = it.reservedCount - 1, updatedAt = updatedAt)
        }

    private fun update(
        sessionId: Long,
        contactKey: String,
        transform: (ReplyBudgetEntity) -> ReplyBudgetEntity?,
    ): Int {
        val key = sessionId to contactKey
        val current = rows[key] ?: return 0
        val changed = transform(current) ?: return 0
        rows[key] = changed
        return 1
    }
}

private class FakeContactPolicyDao : ContactPolicyDao {
    private val rows = MutableStateFlow<List<ContactPolicyEntity>>(emptyList())

    override suspend fun get(contactKey: String): ContactPolicyEntity? =
        rows.value.firstOrNull { it.contactKey == contactKey }

    override fun observeAll(): Flow<List<ContactPolicyEntity>> = rows

    override suspend fun upsert(policy: ContactPolicyEntity) {
        rows.value = rows.value.filterNot { it.contactKey == policy.contactKey } + policy
    }
}
