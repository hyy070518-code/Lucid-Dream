package com.huyang.luciddream.ui.home

import android.content.Context
import android.widget.Toast
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal enum class AgentTaskToastCue(val text: String) {
    STARTED("开始执行"),
    RUNNING("执行中..."),
    CANCELLING("正在停止..."),
    CANCELLED("任务已停止"),
    COMPLETED("执行完毕！"),
    INTERRUPTED("任务中断"),
}

/** Pure transition tracker; it never changes the task lifecycle. */
internal class AgentTaskToastTransitionTracker {
    private var previousStatus: AgentTaskUiStatus? = null
    private var executionStarted = false
    private var runningTaskId: String? = null

    fun onStatusChanged(status: AgentTaskUiStatus): AgentTaskToastCue? {
        if (status == previousStatus) return null

        val cue = when (status) {
            AgentTaskUiStatus.Submitting -> {
                executionStarted = true
                runningTaskId = null
                AgentTaskToastCue.STARTED
            }
            is AgentTaskUiStatus.Running -> {
                val shouldNotify = !executionStarted || runningTaskId != status.taskId
                executionStarted = true
                runningTaskId = status.taskId
                AgentTaskToastCue.RUNNING.takeIf { shouldNotify }
            }
            is AgentTaskUiStatus.Completed -> terminalCue(AgentTaskToastCue.COMPLETED)
            is AgentTaskUiStatus.Cancelling -> {
                AgentTaskToastCue.CANCELLING.takeIf { executionStarted }
            }
            is AgentTaskUiStatus.Cancelled -> terminalCue(AgentTaskToastCue.CANCELLED)
            is AgentTaskUiStatus.Failed,
            is AgentTaskUiStatus.Blocked,
            is AgentTaskUiStatus.MaxSteps,
            is AgentTaskUiStatus.PollingTimeout,
            is AgentTaskUiStatus.StatusUnknown,
            is AgentTaskUiStatus.PreflightFailed -> terminalCue(AgentTaskToastCue.INTERRUPTED)
            AgentTaskUiStatus.Idle -> {
                executionStarted = false
                runningTaskId = null
                null
            }
            is AgentTaskUiStatus.Queued -> null
        }
        previousStatus = status
        return cue
    }

    private fun terminalCue(cue: AgentTaskToastCue): AgentTaskToastCue? {
        val shouldNotify = executionStarted
        executionStarted = false
        runningTaskId = null
        return cue.takeIf { shouldNotify }
    }
}

@Singleton
class AgentTaskToastNotifier @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val applicationContext = context.applicationContext
    private val tracker = AgentTaskToastTransitionTracker()

    fun onStatusChanged(status: AgentTaskUiStatus) {
        val cue = tracker.onStatusChanged(status) ?: return
        Toast.makeText(applicationContext, cue.text, Toast.LENGTH_SHORT).show()
    }
}
