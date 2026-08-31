package com.huyang.luciddream.session

import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.data.repository.DelegationRepository
import com.huyang.luciddream.data.repository.EndSessionResult
import com.huyang.luciddream.data.repository.StartSessionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class DelegationManager @Inject constructor(
    private val repository: DelegationRepository,
    private val eventRepository: AppEventRepository,
    private val summaryGenerator: SessionSummaryGenerator,
    private val sessionEndNotifier: SessionEndNotifier,
) {
    private val summaryMutex = Mutex()
    val activeSession: Flow<DelegationSessionEntity?> = repository.observeActive()
    val recentSessions: Flow<List<DelegationSessionEntity>> = repository.observeRecent()

    suspend fun startSleepDelegation(): StartSessionResult {
        val result = repository.startSleepSession()
        if (result is StartSessionResult.Created) {
            runCatching {
                eventRepository.record(
                    "SESSION",
                    "Session #${result.session.id} STARTED · Mode=SLEEP · ReplyLimit=${result.session.defaultReplyLimit}",
                )
            }
        }
        return result
    }

    suspend fun endDelegation(): EndSessionResult {
        val result = repository.endActiveSession()
        if (result is EndSessionResult.Ended) {
            withContext(NonCancellable) {
                runCatching {
                    eventRepository.record(
                        "SESSION",
                        "Session #${result.session.id} ENDED · 新 Agent 任务已停止 · Summary=GENERATING",
                    )
                }
                completePendingSummary(result.session.id)
            }
        }
        return result
    }

    suspend fun recoverPendingSummaries() {
        repository.pendingSummaries().forEach { session ->
            completePendingSummary(session.id)
        }
    }

    private suspend fun completePendingSummary(sessionId: Long) = summaryMutex.withLock {
        val current = repository.getSession(sessionId) ?: return@withLock
        if (current.summaryStatus != DelegationSessionEntity.SUMMARY_PENDING) return@withLock
        val outcome = runCatching { summaryGenerator.generate(sessionId) }
            .getOrElse { error ->
                SessionSummaryOutcome(
                    status = DelegationSessionEntity.SUMMARY_FAILED,
                    summary = "本次托管总结生成失败。正常消息仍保存在本机，可在 Inspector 查看处理记录。",
                    notificationText = "托管已结束，但总结生成失败。点击查看本地记录。",
                    contactCount = 0,
                    needsOwnerCount = 0,
                    deepSeekStatus = "FAILED",
                    error = error.message ?: "本地总结流程异常",
                )
            }
        val saved = runCatching {
            repository.saveSummary(
                sessionId = sessionId,
                status = outcome.status,
                summary = outcome.summary,
                notificationText = outcome.notificationText,
                contactCount = outcome.contactCount,
                needsOwnerCount = outcome.needsOwnerCount,
            )
        }.getOrDefault(false)
        val notificationResult = if (saved) {
            sessionEndNotifier.show(sessionId, outcome.notificationText)
        } else {
            NotificationDeliveryResult.Failed
        }
        runCatching {
            eventRepository.record(
                "SESSION",
                buildString {
                    append("Session #$sessionId SUMMARY ${outcome.status}\n")
                    append("联系人：${outcome.contactCount}；需要本人：${outcome.needsOwnerCount}\n")
                    append("DeepSeek：${outcome.deepSeekStatus}\n")
                    append("本地保存：${if (saved) "SUCCESS" else "FAILED"}\n")
                    append("结束通知：$notificationResult")
                    outcome.error?.let { append("\n错误：$it") }
                },
            )
        }
        val deletedCount = runCatching { repository.trimToMostRecent(7) }.getOrDefault(0)
        if (deletedCount > 0) {
            runCatching {
                eventRepository.record(
                    "SESSION_CLEANUP",
                    "已删除 $deletedCount 个最旧 Session 及其消息、Decision、安全事件和预算；保留最近 7 次",
                )
            }
        }
    }
}
