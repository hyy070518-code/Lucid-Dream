package com.huyang.luciddream.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentTaskPollerTest {
    private val poller = AgentTaskPoller(AgentTaskClient(OkHttpClient()))

    @Test
    fun queuedRunningCompletedStopsAtTerminalState() = runTest {
        val responses = ArrayDeque(
            listOf(
                snapshotResult(AgentServerTaskStatus.QUEUED),
                snapshotResult(AgentServerTaskStatus.RUNNING),
                snapshotResult(AgentServerTaskStatus.COMPLETED, "done"),
            ),
        )
        val updates = mutableListOf<AgentServerTaskStatus>()
        var fetchCount = 0

        val outcome = poller.pollWith(0, 10, { updates += it.status }) {
            fetchCount += 1
            responses.removeFirst()
        }

        assertEquals(
            listOf(
                AgentServerTaskStatus.QUEUED,
                AgentServerTaskStatus.RUNNING,
                AgentServerTaskStatus.COMPLETED,
            ),
            updates,
        )
        assertEquals(3, fetchCount)
        assertEquals(
            AgentTaskPollingOutcome.Terminal(
                AgentTaskStatusSnapshot("task-1", AgentServerTaskStatus.COMPLETED, "done"),
            ),
            outcome,
        )
    }

    @Test
    fun runningFailedStopsPolling() = terminalTransitionTest(AgentServerTaskStatus.FAILED)

    @Test
    fun runningBlockedStopsPolling() = terminalTransitionTest(AgentServerTaskStatus.BLOCKED)

    @Test
    fun runningMaxStepsStopsPolling() = terminalTransitionTest(AgentServerTaskStatus.MAX_STEPS)

    @Test
    fun runningCancelledStopsPolling() = terminalTransitionTest(AgentServerTaskStatus.CANCELLED)

    @Test
    fun pollingStopsAtConfiguredTimeout() = runTest {
        var fetchCount = 0

        val outcome = poller.pollWith(0, 2, {}) {
            fetchCount += 1
            snapshotResult(AgentServerTaskStatus.RUNNING)
        }

        assertEquals(AgentTaskPollingOutcome.Timeout, outcome)
        assertEquals(2, fetchCount)
    }

    @Test
    fun cancelledOldPollingCoroutineCannotEmitMoreUpdates() = runTest {
        val updates = mutableListOf<AgentServerTaskStatus>()
        var fetchCount = 0
        val job = launch {
            poller.pollWith(1_000, 10, { updates += it.status }) {
                fetchCount += 1
                snapshotResult(AgentServerTaskStatus.RUNNING)
            }
        }

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, fetchCount)

        job.cancelAndJoin()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, fetchCount)
        assertEquals(listOf(AgentServerTaskStatus.RUNNING), updates)
    }

    private fun terminalTransitionTest(terminalStatus: AgentServerTaskStatus) = runTest {
        val responses = ArrayDeque(
            listOf(
                snapshotResult(AgentServerTaskStatus.RUNNING),
                snapshotResult(terminalStatus, "terminal reason"),
            ),
        )
        var fetchCount = 0

        val outcome = poller.pollWith(0, 10, {}) {
            fetchCount += 1
            responses.removeFirst()
        }

        assertEquals(2, fetchCount)
        assertEquals(
            AgentTaskPollingOutcome.Terminal(
                AgentTaskStatusSnapshot("task-1", terminalStatus, "terminal reason"),
            ),
            outcome,
        )
    }

    private fun snapshotResult(
        status: AgentServerTaskStatus,
        reason: String? = null,
    ): AgentTaskStatusResult = AgentTaskStatusResult.Success(
        AgentTaskStatusSnapshot(
            taskId = "task-1",
            status = status,
            reason = reason,
        ),
    )
}
