package com.huyang.luciddream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentTaskToastTransitionTrackerTest {
    @Test
    fun idleToSubmittingShowsStartedOnce() {
        val tracker = AgentTaskToastTransitionTracker()

        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Idle))
        assertEquals(
            AgentTaskToastCue.STARTED,
            tracker.onStatusChanged(AgentTaskUiStatus.Submitting),
        )
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Submitting))
    }

    @Test
    fun submittingToRunningShowsRunningOnlyOnceForSameTask() {
        val tracker = startedTracker()

        assertEquals(
            AgentTaskToastCue.RUNNING,
            tracker.onStatusChanged(AgentTaskUiStatus.Running("task-a")),
        )
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Running("task-a")))
        tracker.onStatusChanged(AgentTaskUiStatus.Queued("task-a"))
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Running("task-a")))
    }

    @Test
    fun runningToCompletedShowsCompleted() {
        val tracker = runningTracker()

        assertEquals(
            AgentTaskToastCue.COMPLETED,
            tracker.onStatusChanged(AgentTaskUiStatus.Completed("task-a", "done")),
        )
    }

    @Test
    fun everyActualFailureTerminalShowsInterrupted() {
        val terminals = listOf<AgentTaskUiStatus>(
            AgentTaskUiStatus.Failed("failed", "task-a"),
            AgentTaskUiStatus.Blocked("task-a", "blocked"),
            AgentTaskUiStatus.MaxSteps("task-a", "max"),
            AgentTaskUiStatus.PollingTimeout("task-a"),
            AgentTaskUiStatus.StatusUnknown("task-a", "unknown"),
            AgentTaskUiStatus.PreflightFailed("preflight"),
        )

        terminals.forEach { terminal ->
            assertEquals(
                AgentTaskToastCue.INTERRUPTED,
                runningTracker().onStatusChanged(terminal),
            )
        }
    }

    @Test
    fun cancellingAndCancelledHaveDedicatedDeduplicatedCues() {
        val tracker = runningTracker()

        assertEquals(
            AgentTaskToastCue.CANCELLING,
            tracker.onStatusChanged(AgentTaskUiStatus.Cancelling("task-a")),
        )
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Cancelling("task-a")))
        assertEquals(
            AgentTaskToastCue.CANCELLED,
            tracker.onStatusChanged(AgentTaskUiStatus.Cancelled("task-a", "stopped")),
        )
    }

    @Test
    fun secondTaskStillShowsFullLifecycleAfterFirstCompleted() {
        val tracker = runningTracker()
        tracker.onStatusChanged(AgentTaskUiStatus.Completed("task-a", "done"))
        tracker.onStatusChanged(AgentTaskUiStatus.Idle)

        assertEquals(
            AgentTaskToastCue.STARTED,
            tracker.onStatusChanged(AgentTaskUiStatus.Submitting),
        )
        tracker.onStatusChanged(AgentTaskUiStatus.Queued("task-b"))
        assertEquals(
            AgentTaskToastCue.RUNNING,
            tracker.onStatusChanged(AgentTaskUiStatus.Running("task-b")),
        )
        assertEquals(
            AgentTaskToastCue.COMPLETED,
            tracker.onStatusChanged(AgentTaskUiStatus.Completed("task-b", "done")),
        )
    }

    @Test
    fun waitingConfirmationAndDenyWithoutSubmissionProduceNoExecutionToast() {
        val tracker = AgentTaskToastTransitionTracker()

        // WaitingConfirmation and DENY leave the shared AgentTaskUiStatus at Idle.
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Idle))
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Idle))
        assertNull(tracker.onStatusChanged(AgentTaskUiStatus.Failed("本地校验失败")))
    }

    private fun startedTracker() = AgentTaskToastTransitionTracker().also {
        it.onStatusChanged(AgentTaskUiStatus.Idle)
        it.onStatusChanged(AgentTaskUiStatus.Submitting)
    }

    private fun runningTracker() = startedTracker().also {
        it.onStatusChanged(AgentTaskUiStatus.Queued("task-a"))
        it.onStatusChanged(AgentTaskUiStatus.Running("task-a"))
    }
}
