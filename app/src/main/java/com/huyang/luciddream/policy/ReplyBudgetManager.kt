package com.huyang.luciddream.policy

import com.huyang.luciddream.data.dao.ContactPolicyDao
import com.huyang.luciddream.data.dao.ReplyBudgetDao
import com.huyang.luciddream.data.entity.ReplyBudgetEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface BudgetStatus {
    val used: Int
    val limit: Int

    data class Available(
        override val used: Int,
        override val limit: Int,
        val reserved: Int,
    ) : BudgetStatus

    data class Exhausted(
        override val used: Int,
        override val limit: Int,
    ) : BudgetStatus
}

data class ReplyBudgetPermit(
    val sessionId: Long,
    val contactKey: String,
    val usedBefore: Int,
    val limit: Int,
)

sealed interface PermitResult {
    data class Acquired(val permit: ReplyBudgetPermit) : PermitResult
    data class Exhausted(val used: Int, val limit: Int) : PermitResult
}

@Singleton
class ReplyBudgetManager @Inject constructor(
    private val budgetDao: ReplyBudgetDao,
    private val policyDao: ContactPolicyDao,
) {
    private val initializationMutex = Mutex()

    suspend fun evaluate(
        sessionId: Long,
        contact: ContactIdentity,
        sessionDefaultLimit: Int,
    ): BudgetStatus = toStatus(ensureBudget(sessionId, contact, sessionDefaultLimit))

    /** Reserve before an API call; only commit after a REPLY decision was generated. */
    suspend fun acquirePermit(
        sessionId: Long,
        contact: ContactIdentity,
        sessionDefaultLimit: Int,
    ): PermitResult {
        val budget = ensureBudget(sessionId, contact, sessionDefaultLimit)
        val changed = budgetDao.reserve(sessionId, contact.key, System.currentTimeMillis())
        if (changed == 0) {
            val current = budgetDao.get(sessionId, contact.key) ?: budget
            return PermitResult.Exhausted(current.replyCount, current.replyLimit)
        }
        return PermitResult.Acquired(
            ReplyBudgetPermit(
                sessionId = sessionId,
                contactKey = contact.key,
                usedBefore = budget.replyCount,
                limit = budget.replyLimit,
            ),
        )
    }

    suspend fun commitPreparedReply(permit: ReplyBudgetPermit): Boolean =
        budgetDao.commit(permit.sessionId, permit.contactKey, System.currentTimeMillis()) == 1

    suspend fun release(permit: ReplyBudgetPermit): Boolean =
        budgetDao.release(permit.sessionId, permit.contactKey, System.currentTimeMillis()) == 1

    private suspend fun ensureBudget(
        sessionId: Long,
        contact: ContactIdentity,
        sessionDefaultLimit: Int,
    ): ReplyBudgetEntity = initializationMutex.withLock {
        val defaultLimit = sessionDefaultLimit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        val policy = policyDao.get(contact.key)
        val effectiveLimit = if (policy?.isAllowlisted == true) {
            policy.replyLimit.coerceIn(MIN_LIMIT, MAX_LIMIT)
        } else {
            defaultLimit
        }
        val now = System.currentTimeMillis()
        var current = budgetDao.get(sessionId, contact.key)
        if (current == null) {
            budgetDao.insertIgnore(
                ReplyBudgetEntity(
                    sessionId = sessionId,
                    contactKey = contact.key,
                    contactDisplayName = contact.displayName,
                    replyCount = 0,
                    reservedCount = 0,
                    replyLimit = effectiveLimit,
                    updatedAt = now,
                ),
            )
            current = budgetDao.get(sessionId, contact.key)
        }
        checkNotNull(current) { "Reply budget could not be initialized" }
        if (current.replyLimit != effectiveLimit) {
            budgetDao.updateLimit(sessionId, contact.key, effectiveLimit, now)
            current = current.copy(replyLimit = effectiveLimit, updatedAt = now)
        }
        current
    }

    private fun toStatus(entity: ReplyBudgetEntity): BudgetStatus =
        if (entity.replyCount + entity.reservedCount >= entity.replyLimit) {
            BudgetStatus.Exhausted(entity.replyCount, entity.replyLimit)
        } else {
            BudgetStatus.Available(entity.replyCount, entity.replyLimit, entity.reservedCount)
        }

    private companion object {
        const val MIN_LIMIT = 1
        const val MAX_LIMIT = 10
    }
}
