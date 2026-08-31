package com.huyang.luciddream.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huyang.luciddream.agent.tool.OwnerToolResultStatus
import com.huyang.luciddream.data.entity.OwnerChatMessageEntity
import com.huyang.luciddream.data.repository.OwnerChatRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val itemCount = state.messages.size + if (state.isLoading) 1 else 0
    val ownerToolBusy =
        state.ownerToolExecutionStatus is OwnerToolExecutionUiStatus.WaitingConfirmation ||
            state.ownerToolExecutionStatus is OwnerToolExecutionUiStatus.Executing ||
            (state.isLoading &&
                state.ownerToolExecutionStatus is OwnerToolExecutionUiStatus.Finished)

    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    if (state.isAgentPanelVisible) {
        AgentTaskSheet(
            draft = state.agentTaskDraft,
            status = state.agentTaskStatus,
            onDraftChange = viewModel::onAgentTaskDraftChange,
            onSubmit = viewModel::submitAgentTask,
            onDismiss = viewModel::closeAgentPanel,
        )
    }

    (state.ownerToolExecutionStatus as? OwnerToolExecutionUiStatus.WaitingConfirmation)?.let {
        OwnerToolConfirmationDialog(
            status = it,
            canConfirm = state.agentTaskStatus.allowsSubmission,
            onConfirm = { viewModel.confirmOwnerTool(it.evaluation.proposal.toolCallId) },
            onCancel = { viewModel.cancelOwnerTool(it.evaluation.proposal.toolCallId) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Lucid Dream", fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DeepSeek  ", fontSize = 12.sp)
                            Text(
                                text = if (state.hasApiKey) "● ${state.model}" else "○ API 未配置",
                                color = if (state.hasApiKey) Color(0xFF68D391) else Color(0xFFFFB4AB),
                                fontSize = 12.sp,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Agent Chat", fontWeight = FontWeight.SemiBold)
                OutlinedButton(
                    onClick = viewModel::openAgentPanel,
                    enabled = state.agentTaskStatus.allowsSubmission && !ownerToolBusy,
                ) {
                    Text("手机 Agent")
                }
            }
            if (state.ownerToolExecutionStatus == OwnerToolExecutionUiStatus.Idle) {
                AgentTaskStatusCard(state.agentTaskStatus)
            }
            OwnerToolExecutionStatusCard(state.ownerToolExecutionStatus)

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.messages.isEmpty()) {
                    item {
                        Text(
                            "这里是 OWNER_TRUSTED 对话。你可以先问 DeepSeek 一个问题，回复会以结构化 Agent Decision 处理并保存在本地。",
                            modifier = Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(state.messages, key = { it.id }) { message -> ChatBubble(message) }
                if (state.isLoading) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text("DeepSeek 正在生成 Agent Decision…")
                        }
                    }
                }
            }

            state.error?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(error, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::dismissError) { Text("关闭") }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.draft,
                    onValueChange = viewModel::onDraftChange,
                    enabled = state.hasApiKey && !state.isLoading && !ownerToolBusy,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (state.hasApiKey) "给你的 Agent 发消息" else "请先在设置中配置 API")
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { viewModel.send() }),
                    maxLines = 4,
                )
                Button(
                    onClick = viewModel::send,
                    enabled = state.hasApiKey && state.draft.isNotBlank() &&
                        !state.isLoading && !ownerToolBusy,
                ) {
                    Text("发送")
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun OwnerToolConfirmationDialog(
    status: OwnerToolExecutionUiStatus.WaitingConfirmation,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val evaluation = status.evaluation
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("准备执行手机操作") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(evaluation.proposal.task)
                Text(
                    "风险：${evaluation.proposal.riskCategory}",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "只有你明确确认后才会提交给 Android Agent。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!canConfirm) {
                    Text(
                        "已有手机 Agent 任务正在执行",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = canConfirm) { Text("确认执行") }
        },
    )
}

@Composable
private fun OwnerToolExecutionStatusCard(status: OwnerToolExecutionUiStatus) {
    val title: String
    val detail: String
    val isError: Boolean
    when (status) {
        OwnerToolExecutionUiStatus.Idle,
        is OwnerToolExecutionUiStatus.WaitingConfirmation -> return
        is OwnerToolExecutionUiStatus.Executing -> {
            title = "手机任务正在执行…"
            detail = status.task
            isError = false
        }
        is OwnerToolExecutionUiStatus.Finished -> {
            detail = status.result.reason
            when (status.result.status) {
                OwnerToolResultStatus.COMPLETED -> {
                    title = "手机任务完成"
                    isError = false
                }
                OwnerToolResultStatus.FAILED -> {
                    title = "手机任务失败"
                    isError = true
                }
                OwnerToolResultStatus.BLOCKED -> {
                    title = "手机任务需要你处理"
                    isError = true
                }
                OwnerToolResultStatus.MAX_STEPS -> {
                    title = "Agent 未能在限定步骤内完成任务"
                    isError = true
                }
                OwnerToolResultStatus.POLLING_TIMEOUT -> {
                    title = "暂时无法确认手机任务最终状态"
                    isError = true
                }
                OwnerToolResultStatus.STATUS_UNKNOWN -> {
                    title = "无法获取手机任务状态"
                    isError = true
                }
                OwnerToolResultStatus.CANCELLED_BY_USER -> {
                    title = "已取消手机操作"
                    isError = false
                }
                OwnerToolResultStatus.DENIED -> {
                    title = "该手机操作未获允许"
                    isError = true
                }
                OwnerToolResultStatus.PREFLIGHT_FAILED -> {
                    title = "Android Agent 运行环境未就绪"
                    isError = true
                }
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.tertiaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentTaskSheet(
    draft: String,
    status: AgentTaskUiStatus,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isSubmitting = status == AgentTaskUiStatus.Submitting
    val allowsSubmission = status.allowsSubmission

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "手机 Agent",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "任务只会在你主动点击执行后提交给本机 Android Agent。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = allowsSubmission,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("让手机执行一个任务……") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                minLines = 3,
                maxLines = 6,
            )
            Button(
                onClick = onSubmit,
                enabled = draft.isNotBlank() && allowsSubmission,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    when (status) {
                        AgentTaskUiStatus.Submitting -> "正在提交"
                        is AgentTaskUiStatus.Queued,
                        is AgentTaskUiStatus.Running -> "执行中"
                        else -> "执行任务"
                    },
                )
            }
            when (status) {
                AgentTaskUiStatus.Idle -> Text(
                    "未提交",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AgentTaskUiStatus.Submitting -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(strokeWidth = 2.dp)
                    Text("正在提交")
                }
                is AgentTaskUiStatus.Failed -> Text(
                    "提交失败：${status.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text("任务状态已更新")
            }
        }
    }
}

@Composable
private fun AgentTaskStatusCard(status: AgentTaskUiStatus) {
    val taskId = status.taskId ?: return
    val title: String
    val detail: String?
    val isError: Boolean

    when (status) {
        is AgentTaskUiStatus.Queued -> {
            title = "任务已进入队列"
            detail = null
            isError = false
        }
        is AgentTaskUiStatus.Running -> {
            title = "Agent 正在执行…"
            detail = null
            isError = false
        }
        is AgentTaskUiStatus.Completed -> {
            title = "任务完成"
            detail = status.reason
            isError = false
        }
        is AgentTaskUiStatus.Blocked -> {
            title = "任务需要你处理"
            detail = status.reason
            isError = true
        }
        is AgentTaskUiStatus.Failed -> {
            title = "任务失败"
            detail = status.message
            isError = true
        }
        is AgentTaskUiStatus.MaxSteps -> {
            title = "Agent 未能在限定步骤内完成任务"
            detail = status.reason
            isError = true
        }
        is AgentTaskUiStatus.PollingTimeout -> {
            title = "暂时无法确认任务最终状态"
            detail = "Agent 可能仍在后台执行"
            isError = true
        }
        is AgentTaskUiStatus.StatusUnknown -> {
            title = "无法获取任务状态"
            detail = status.message
            isError = true
        }
        else -> return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text("task_id: $taskId", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ChatBubble(message: OwnerChatMessageEntity) {
    val isOwner = message.role == OwnerChatRepository.ROLE_OWNER
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isOwner) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Card(
            modifier = Modifier.widthIn(max = 340.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = if (isOwner) 18.dp else 4.dp,
                bottomEnd = if (isOwner) 4.dp else 18.dp,
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (isOwner) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            ),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                Text(message.content)
            }
        }
    }
}
