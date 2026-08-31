package com.huyang.luciddream.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel = hiltViewModel()) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("托管历史") }) }) { padding ->
        if (sessions.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("尚无托管 Session", fontWeight = FontWeight.SemiBold)
                Text("在首页开启一次睡眠托管后会显示在这里")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        "最近 ${sessions.size} 次 · 最多保留 7 次托管及其本地数据",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(sessions, key = { it.id }) { session -> SessionCard(session) }
            }
        }
    }
}

@Composable
private fun SessionCard(session: DelegationSessionEntity) {
    val isActive = session.status == DelegationSessionEntity.STATUS_ACTIVE
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Session #${session.id}", fontWeight = FontWeight.SemiBold)
                Text(
                    if (isActive) "● ACTIVE" else "ENDED",
                    color = if (isActive) Color(0xFF55C987) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SessionField("模式", "睡眠托管")
            SessionField("开始", formatDateTime(session.startedAt))
            session.endedAt?.let {
                SessionField("结束", formatDateTime(it))
                SessionField("持续", formatDuration(session.startedAt, it))
            }
            SessionField("默认回复上限", session.defaultReplyLimit.toString())
            if (!isActive) {
                SessionSummary(session)
            }
        }
    }
}

@Composable
private fun SessionSummary(session: DelegationSessionEntity) {
    when (session.summaryStatus) {
        DelegationSessionEntity.SUMMARY_PENDING -> {
            Text("正在生成交接总结…", color = MaterialTheme.colorScheme.primary)
        }
        DelegationSessionEntity.SUMMARY_COMPLETED,
        DelegationSessionEntity.SUMMARY_EMPTY,
        DelegationSessionEntity.SUMMARY_FAILED,
        -> {
            Text("交接总结", fontWeight = FontWeight.SemiBold)
            SessionField("联系人", session.summaryContactCount.toString())
            SessionField("需要本人处理", session.summaryNeedsOwnerCount.toString())
            Text(
                session.summary ?: "总结不可用",
                color = if (session.summaryStatus == DelegationSessionEntity.SUMMARY_FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        else -> Text("本次 Session 尚无总结", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SessionField(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

private fun formatDateTime(timestamp: Long): String = DateTimeFormatter
    .ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(timestamp))

private fun formatDuration(startedAt: Long, endedAt: Long): String {
    val duration = Duration.ofMillis((endedAt - startedAt).coerceAtLeast(0))
    val hours = duration.toHours()
    val minutes = duration.minusHours(hours).toMinutes()
    val seconds = duration.minusHours(hours).minusMinutes(minutes).seconds
    return when {
        hours > 0 -> "${hours}小时${minutes}分"
        minutes > 0 -> "${minutes}分${seconds}秒"
        else -> "${seconds}秒"
    }
}
