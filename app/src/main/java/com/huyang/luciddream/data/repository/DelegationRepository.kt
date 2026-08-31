package com.huyang.luciddream.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.huyang.luciddream.data.dao.DelegationSessionDao
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface StartSessionResult {
    data class Created(val session: DelegationSessionEntity) : StartSessionResult
    data class AlreadyActive(val session: DelegationSessionEntity) : StartSessionResult
    data class Failure(val message: String) : StartSessionResult
}

sealed interface EndSessionResult {
    data class Ended(val session: DelegationSessionEntity) : EndSessionResult
    data object NoActiveSession : EndSessionResult
    data class Failure(val message: String) : EndSessionResult
}

@Singleton
class DelegationRepository @Inject constructor(
    private val dao: DelegationSessionDao,
) {
    private val mutationMutex = Mutex()

    fun observeActive(): Flow<DelegationSessionEntity?> = dao.observeActive()
    fun observeRecent(): Flow<List<DelegationSessionEntity>> = dao.observeRecent()
    suspend fun getSession(sessionId: Long): DelegationSessionEntity? = dao.getById(sessionId)
    suspend fun pendingSummaries(): List<DelegationSessionEntity> = dao.getPendingSummaries()

    suspend fun startSleepSession(now: Long = System.currentTimeMillis()): StartSessionResult =
        mutationMutex.withLock {
            dao.getActive()?.let { return@withLock StartSessionResult.AlreadyActive(it) }
            val newSession = DelegationSessionEntity(
                status = DelegationSessionEntity.STATUS_ACTIVE,
                mode = DelegationSessionEntity.MODE_SLEEP,
                startedAt = now,
                defaultReplyLimit = DelegationSessionEntity.DEFAULT_REPLY_LIMIT,
            )
            try {
                val id = dao.insert(newSession)
                StartSessionResult.Created(newSession.copy(id = id))
            } catch (_: SQLiteConstraintException) {
                dao.getActive()?.let(StartSessionResult::AlreadyActive)
                    ?: StartSessionResult.Failure("无法创建托管 Session")
            } catch (_: Exception) {
                StartSessionResult.Failure("数据库无法创建托管 Session")
            }
        }

    suspend fun endActiveSession(now: Long = System.currentTimeMillis()): EndSessionResult =
        mutationMutex.withLock {
            val active = dao.getActive() ?: return@withLock EndSessionResult.NoActiveSession
            try {
                val changed = dao.endActive(active.id, now)
                if (changed == 1) {
                    EndSessionResult.Ended(
                        active.copy(
                            status = DelegationSessionEntity.STATUS_ENDED,
                            endedAt = now,
                            summaryStatus = DelegationSessionEntity.SUMMARY_PENDING,
                            activeSlot = null,
                        ),
                    )
                } else {
                    EndSessionResult.Failure("Session 状态已变化，请刷新后重试")
                }
            } catch (_: Exception) {
                EndSessionResult.Failure("数据库无法结束托管 Session")
            }
        }

    suspend fun saveSummary(
        sessionId: Long,
        status: String,
        summary: String,
        notificationText: String,
        contactCount: Int,
        needsOwnerCount: Int,
        generatedAt: Long = System.currentTimeMillis(),
    ): Boolean = dao.updateSummary(
        sessionId = sessionId,
        status = status,
        summary = summary,
        notificationText = notificationText,
        contactCount = contactCount,
        needsOwnerCount = needsOwnerCount,
        generatedAt = generatedAt,
    ) == 1

    suspend fun trimToMostRecent(keepCount: Int = 7): Int {
        require(keepCount > 0)
        val obsoleteIds = dao.idsBeyondMostRecent(keepCount)
        dao.deleteSessionData(obsoleteIds)
        return obsoleteIds.size
    }
}
