package com.huyang.luciddream.ui.settings

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huyang.luciddream.data.repository.ContactPolicyItem
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val summaryPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.refreshWechatDelegationState() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshAccessibilityState()
        viewModel.refreshWechatDelegationState()
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("设置") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SettingsCard(title = "身份") {
                    LabelValue("Owner", "胡洋")
                    LabelValue("Agent", "DeepSeek 托管助手")
                }
            }
            item {
                SettingsCard(title = "微信托管与消息监听") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("托管状态")
                            Text(
                                if (state.activeSession == null) "○ OFF" else "● 睡眠托管中",
                                color = if (state.activeSession == null) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    Color(0xFF55C987)
                                },
                                fontWeight = FontWeight.SemiBold,
                            )
                            state.activeSession?.let { session ->
                                Text(
                                    "Session #${session.id} · 开始 ${formatSessionTime(session.startedAt)} · " +
                                        "回复上限 ${session.defaultReplyLimit}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        Button(
                            onClick = if (state.activeSession == null) {
                                viewModel::startSleepDelegation
                            } else {
                                viewModel::endDelegation
                            },
                            enabled = !state.isDelegationChanging,
                        ) {
                            Text(
                                when {
                                    state.isDelegationChanging -> "处理中"
                                    state.activeSession == null -> "开启托管"
                                    else -> "结束托管"
                                },
                            )
                        }
                    }
                    state.delegationMessage?.let { message ->
                        Text(
                            message,
                            color = if (state.delegationMessageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF55C987)
                            },
                        )
                    }
                    HorizontalDivider()
                    Text("通知监听")
                    Text(
                        when {
                            !state.notificationAccessGranted -> "○ 未授权，无法捕获微信消息"
                            state.notificationListenerConnected -> "● 已授权，监听器在线"
                            else -> "◐ 已授权，但监听器离线"
                        },
                        color = when {
                            !state.notificationAccessGranted -> MaterialTheme.colorScheme.error
                            state.notificationListenerConnected -> Color(0xFF55C987)
                            else -> Color(0xFFFFB74D)
                        },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (state.notificationAccessGranted && !state.notificationListenerConnected) {
                            OutlinedButton(onClick = viewModel::reconnectNotificationListener) {
                                Text("重新连接")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                                )
                            },
                        ) {
                            Text(if (state.notificationAccessGranted) "查看权限" else "去授权")
                        }
                    }
                    if (!state.summaryNotificationPermissionGranted) {
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("托管结束通知")
                                Text(
                                    "尚未授权；总结仍会保存在本机",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                OutlinedButton(
                                    onClick = {
                                        summaryPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    },
                                ) {
                                    Text("授权")
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsCard(title = "Android Agent 服务") {
                    Text(
                        text = if (state.hasAgentToken) {
                            "Token 已保存：${state.maskedAgentToken}"
                        } else {
                            "尚未保存 Android Agent Token"
                        },
                        color = if (state.hasAgentToken) {
                            Color(0xFF55C987)
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    OutlinedTextField(
                        value = state.agentTokenInput,
                        onValueChange = viewModel::onAgentTokenChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                if (state.hasAgentToken) {
                                    "输入新 Token 可替换"
                                } else {
                                    "Android Agent Token"
                                },
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = viewModel::saveAgentToken,
                            enabled = state.agentTokenInput.isNotBlank(),
                        ) {
                            Text("保存 Token")
                        }
                        if (state.hasAgentToken) {
                            OutlinedButton(onClick = viewModel::clearAgentToken) {
                                Text("删除 Token")
                            }
                        }
                    }
                    state.agentTokenMessage?.let { message ->
                        Text(
                            text = message,
                            color = if (state.agentTokenMessageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF55C987)
                            },
                        )
                    }
                    Text(
                        "Token 使用 Android Keystore + AES-GCM 保存，界面只显示末四位。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    HorizontalDivider()
                    Text(
                        text = state.agentConnectionMessage,
                        color = when (state.agentConnectionStatus) {
                            AgentConnectionStatus.CONNECTED -> Color(0xFF55C987)
                            AgentConnectionStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    OutlinedButton(
                        onClick = viewModel::testAgentServerConnection,
                        enabled = state.agentConnectionStatus != AgentConnectionStatus.CONNECTING,
                    ) {
                        if (state.agentConnectionStatus == AgentConnectionStatus.CONNECTING) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(end = 8.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        Text("测试 Agent 服务")
                    }
                    Text(
                        "连接测试只访问本机 Agent Server 的 /health，不会创建任务。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                SettingsCard(title = "DeepSeek API") {
                    Text(
                        text = if (state.hasApiKey) {
                            "已保存：${state.maskedApiKey}"
                        } else {
                            "尚未保存 API Key"
                        },
                        color = if (state.hasApiKey) Color(0xFF55C987) else MaterialTheme.colorScheme.error,
                    )
                    OutlinedTextField(
                        value = state.apiKeyInput,
                        onValueChange = viewModel::onApiKeyChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(if (state.hasApiKey) "输入新 Key 可替换" else "DeepSeek API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.baseUrl,
                        onValueChange = viewModel::onBaseUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Base URL") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = state.model,
                        onValueChange = viewModel::onModelChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Model") },
                        singleLine = true,
                    )
                    state.message?.let {
                        Text(
                            text = it,
                            color = if (state.messageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF55C987)
                            },
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(onClick = viewModel::save, enabled = !state.isTesting) {
                            Text("保存")
                        }
                        OutlinedButton(onClick = viewModel::testConnection, enabled = !state.isTesting) {
                            if (state.isTesting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(end = 8.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text("测试连接")
                        }
                    }
                    if (state.hasApiKey) {
                        OutlinedButton(onClick = viewModel::clearApiKey, enabled = !state.isTesting) {
                            Text("删除 API Key")
                        }
                    }
                    Text(
                        "Key 由 Android Keystore 保护；界面与日志不会显示完整内容。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                SettingsCard(title = "微信自动回复（实验）") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Lucid Dream 无障碍服务")
                            Text(
                                when {
                                    state.accessibilityConnected -> "● 已授权，发送桥在线"
                                    state.accessibilityEnabled -> "◐ 已授权，服务等待连接"
                                    else -> "○ 未授权，只能尝试通知快捷回复"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.accessibilityConnected) {
                                    Color(0xFF55C987)
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                                )
                            },
                        ) {
                            Text(if (state.accessibilityEnabled) "查看" else "去授权")
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (state.autoReplyEnabled) "自动发送已开启" else "默认关闭：仅生成准备回复")
                            Text(
                                "只有 Safety、联系人额度和 Agent Decision 全部通过后，才尝试快捷回复或无障碍直通发送。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Switch(
                            checked = state.autoReplyEnabled,
                            onCheckedChange = viewModel::setAutoReplyEnabled,
                        )
                    }
                    Text(
                        "初版直通模式：通过微信通知入口打开聊天后直接写入并发送；按钮识别失败时使用本机实测坐标。手机有安全锁且处于锁定状态时仍不会发送。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    state.autoReplyMessage?.let {
                        Text(
                            text = it,
                            color = if (state.autoReplyMessageIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF55C987)
                            },
                        )
                    }
                }
            }
            item {
                SettingsCard(title = "联系人 / 白名单") {
                    Text(
                        "普通联系人每个 Session 默认 3 次 Agent 回复；白名单可设为 3、5 或 10。联系人名称来自通知，是 v0.1 临时身份。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (state.contacts.isEmpty()) {
                        Text("暂无联系人。托管开启时收到一条通过 Safety 的微信消息后会出现在这里。")
                    } else {
                        state.contacts.forEachIndexed { index, contact ->
                            if (index > 0) HorizontalDivider()
                            ContactPolicyEditor(
                                item = contact,
                                onAllowlistedChange = { viewModel.setAllowlisted(contact, it) },
                                onLimitChange = { viewModel.setReplyLimit(contact, it) },
                            )
                        }
                    }
                    state.contactMessage?.let { Text(it, color = Color(0xFF55C987)) }
                }
            }
            item { Text("Lucid Dream v0.2.5 · Phase 8B", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ContactPolicyEditor(
    item: ContactPolicyItem,
    onAllowlistedChange: (Boolean) -> Unit,
    onLimitChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    if (item.isAllowlisted) "白名单 · 上限 ${item.replyLimit}" else "普通联系人 · 上限 3",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = item.isAllowlisted, onCheckedChange = onAllowlistedChange)
        }
        if (item.isAllowlisted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3, 5, 10).forEach { limit ->
                    OutlinedButton(
                        onClick = { onLimitChange(limit) },
                        enabled = item.replyLimit != limit,
                    ) {
                        Text("$limit 次")
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun formatSessionTime(timestamp: Long): String = DateTimeFormatter
    .ofPattern("HH:mm")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(timestamp))
