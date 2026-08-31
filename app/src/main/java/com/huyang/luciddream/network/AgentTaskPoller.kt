package com.huyang.luciddream.network

import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

sealed interface AgentTaskPollingOutcome {
    data class Terminal(val snapshot: AgentTaskStatusSnapshot) : AgentTaskPollingOutcome
    data class Failure(val message: String) : AgentTaskPollingOutcome
    data object Timeout : AgentTaskPollingOutcome
}

class AgentTaskPoller @Inject constructor(
    private val client: AgentTaskClient,
) {
    suspend fun poll(
        taskId: String,
        token: String,
        onStatus: (AgentTaskStatusSnapshot) -> Unit,
    ): AgentTaskPollingOutcome = withTimeoutOrNull(POLLING_TIMEOUT_MILLIS) {
        pollWith(
            pollIntervalMillis = POLL_INTERVAL_MILLIS,
            maxPollAttempts = MAX_POLL_ATTEMPTS,
            onStatus = onStatus,
            fetchStatus = { client.getStatus(taskId, token) },
        )
    } ?: AgentTaskPollingOutcome.Timeout

    internal suspend fun pollWith(
        pollIntervalMillis: Long,
        maxPollAttempts: Int,
        onStatus: (AgentTaskStatusSnapshot) -> Unit,
        fetchStatus: suspend () -> AgentTaskStatusResult,
    ): AgentTaskPollingOutcome {
        require(pollIntervalMillis >= 0) { "pollIntervalMillis must not be negative" }
        require(maxPollAttempts > 0) { "maxPollAttempts must be positive" }

        repeat(maxPollAttempts) {
            delay(pollIntervalMillis)

            when (val result = fetchStatus()) {
                is AgentTaskStatusResult.Failure -> {
                    return AgentTaskPollingOutcome.Failure(result.message)
                }
                is AgentTaskStatusResult.Success -> {
                    onStatus(result.snapshot)
                    if (result.snapshot.status.isTerminal) {
                        return AgentTaskPollingOutcome.Terminal(result.snapshot)
                    }
                }
            }
        }

        return AgentTaskPollingOutcome.Timeout
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 1_000L
        const val MAX_POLL_ATTEMPTS = 300
        const val POLLING_TIMEOUT_MILLIS = 5 * 60 * 1_000L
    }
}
