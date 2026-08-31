package com.huyang.luciddream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTaskUiStatusTest {
    @Test
    fun acceptedTaskClosesPanelAndLocksDuplicateSubmission() {
        val accepted = HomeInteractionState(
            isAgentPanelVisible = true,
            agentTaskDraft = "打开系统设置",
            agentTaskStatus = AgentTaskUiStatus.Submitting,
        ).withAcceptedAgentTask("task-123")

        assertFalse(accepted.isAgentPanelVisible)
        assertEquals(AgentTaskUiStatus.Queued("task-123"), accepted.agentTaskStatus)
        assertFalse(accepted.agentTaskStatus.allowsSubmission)
    }

    @Test
    fun submittingQueuedAndRunningStatesBlockSubmission() {
        assertFalse(AgentTaskUiStatus.Submitting.allowsSubmission)
        assertFalse(AgentTaskUiStatus.Queued("task-123").allowsSubmission)
        assertFalse(AgentTaskUiStatus.Running("task-123").allowsSubmission)
    }

    @Test
    fun idleAndFailedStatesAllowIntentionalSubmissionOrRetry() {
        assertTrue(AgentTaskUiStatus.Idle.allowsSubmission)
        assertTrue(AgentTaskUiStatus.Failed("temporary error").allowsSubmission)
    }

    @Test
    fun completedTaskCanOpenCleanNewTask() {
        assertOpensCleanNewTask(AgentTaskUiStatus.Completed("old-task", "old result"))
    }

    @Test
    fun failedTaskCanOpenCleanNewTask() {
        assertOpensCleanNewTask(AgentTaskUiStatus.Failed("old error", "old-task"))
    }

    @Test
    fun blockedTaskCanOpenCleanNewTask() {
        assertOpensCleanNewTask(AgentTaskUiStatus.Blocked("old-task", "owner required"))
    }

    @Test
    fun maxStepsTaskCanOpenCleanNewTask() {
        assertOpensCleanNewTask(AgentTaskUiStatus.MaxSteps("old-task", "step limit"))
    }

    @Test
    fun pollingTimeoutTaskCanOpenCleanNewTask() {
        assertOpensCleanNewTask(AgentTaskUiStatus.PollingTimeout("old-task"))
    }

    @Test
    fun unknownStatusTaskCanOpenCleanNewTask() {
        assertOpensCleanNewTask(AgentTaskUiStatus.StatusUnknown("old-task", "bad status"))
    }

    @Test
    fun activeTaskCannotOpenOrPrepareSecondTask() {
        listOf(
            AgentTaskUiStatus.Submitting,
            AgentTaskUiStatus.Queued("active-task"),
            AgentTaskUiStatus.Running("active-task"),
        ).forEach { activeStatus ->
            val current = HomeInteractionState(
                isAgentPanelVisible = false,
                agentTaskDraft = "active task",
                agentTaskStatus = activeStatus,
            )

            assertEquals(current, current.prepareNewAgentTask())
            assertFalse(activeStatus.allowsSubmission)
        }
    }

    @Test
    fun editingDraftDoesNotResetTaskLifecycle() {
        val terminal = HomeInteractionState(
            agentTaskDraft = "old task",
            agentTaskStatus = AgentTaskUiStatus.Failed("old error", "old-task"),
        )

        val edited = terminal.withAgentTaskDraft("new task")

        assertEquals("new task", edited.agentTaskDraft)
        assertEquals(terminal.agentTaskStatus, edited.agentTaskStatus)
    }

    @Test
    fun stalePollingUpdateCannotOverwriteNewTaskState() {
        val current = HomeInteractionState(
            agentTaskStatus = AgentTaskUiStatus.Queued("new-task"),
        )

        val afterStaleUpdate = current.withPolledAgentTaskStatus(
            taskId = "old-task",
            status = AgentTaskUiStatus.Completed("old-task", "old result"),
        )

        assertEquals(current, afterStaleUpdate)
    }

    private fun assertOpensCleanNewTask(terminalStatus: AgentTaskUiStatus) {
        val prepared = HomeInteractionState(
            isAgentPanelVisible = false,
            agentTaskDraft = "old task",
            agentTaskStatus = terminalStatus,
        ).prepareNewAgentTask()

        assertTrue(prepared.isAgentPanelVisible)
        assertEquals("", prepared.agentTaskDraft)
        assertEquals(AgentTaskUiStatus.Idle, prepared.agentTaskStatus)
        assertEquals(null, prepared.agentTaskStatus.taskId)
        assertTrue(prepared.agentTaskStatus.allowsSubmission)
    }
}
