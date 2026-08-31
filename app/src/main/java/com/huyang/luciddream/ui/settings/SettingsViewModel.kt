package com.huyang.luciddream.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.data.repository.AppEventRepository
import com.huyang.luciddream.data.repository.ContactPolicyItem
import com.huyang.luciddream.data.repository.ContactPolicyRepository
import com.huyang.luciddream.data.repository.EndSessionResult
import com.huyang.luciddream.data.repository.StartSessionResult
import com.huyang.luciddream.notification.NotificationAccess
import com.huyang.luciddream.notification.NotificationListenerConnectionState
import com.huyang.luciddream.network.AgentHealthClient
import com.huyang.luciddream.network.AgentHealthResult
import com.huyang.luciddream.network.ConnectionTestResult
import com.huyang.luciddream.network.DeepSeekConnectionTester
import com.huyang.luciddream.reply.AutoReplySettingsRepository
import com.huyang.luciddream.accessibility.AccessibilityAccess
import com.huyang.luciddream.accessibility.AccessibilityConnectionState
import com.huyang.luciddream.settings.ApiSettingsRepository
import com.huyang.luciddream.settings.ApiSettingsValidator
import com.huyang.luciddream.settings.AgentTokenRepository
import com.huyang.luciddream.session.DelegationManager
import com.huyang.luciddream.session.SummaryNotificationPermission
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AgentConnectionStatus {
    UNTESTED,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class SettingsUiState(
    val baseUrl: String,
    val model: String,
    val apiKeyInput: String = "",
    val hasApiKey: Boolean,
    val maskedApiKey: String? = null,
    val isTesting: Boolean = false,
    val message: String? = null,
    val messageIsError: Boolean = false,
    val contacts: List<ContactPolicyItem> = emptyList(),
    val contactMessage: String? = null,
    val autoReplyEnabled: Boolean = false,
    val autoReplyMessage: String? = null,
    val autoReplyMessageIsError: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val agentConnectionStatus: AgentConnectionStatus = AgentConnectionStatus.UNTESTED,
    val agentConnectionMessage: String = "未测试",
    val agentTokenInput: String = "",
    val hasAgentToken: Boolean = false,
    val maskedAgentToken: String? = null,
    val agentTokenMessage: String? = null,
    val agentTokenMessageIsError: Boolean = false,
    val activeSession: DelegationSessionEntity? = null,
    val isDelegationChanging: Boolean = false,
    val delegationMessage: String? = null,
    val delegationMessageIsError: Boolean = false,
    val notificationAccessGranted: Boolean = false,
    val notificationListenerConnected: Boolean = false,
    val summaryNotificationPermissionGranted: Boolean = false,
)

internal fun SettingsUiState.withActiveDelegationSession(
    session: DelegationSessionEntity?,
): SettingsUiState = copy(activeSession = session)

internal fun SettingsUiState.withNotificationListenerConnection(
    connected: Boolean,
): SettingsUiState = copy(notificationListenerConnected = connected)

internal fun SettingsUiState.withNotificationPermissions(
    accessGranted: Boolean,
    summaryGranted: Boolean,
): SettingsUiState = copy(
    notificationAccessGranted = accessGranted,
    summaryNotificationPermissionGranted = summaryGranted,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: ApiSettingsRepository,
    private val agentTokenRepository: AgentTokenRepository,
    private val connectionTester: DeepSeekConnectionTester,
    private val agentHealthClient: AgentHealthClient,
    private val eventRepository: AppEventRepository,
    private val contactPolicyRepository: ContactPolicyRepository,
    private val autoReplySettingsRepository: AutoReplySettingsRepository,
    private val accessibilityAccess: AccessibilityAccess,
    private val accessibilityConnectionState: AccessibilityConnectionState,
    private val delegationManager: DelegationManager,
    private val notificationListenerConnectionState: NotificationListenerConnectionState,
) : ViewModel() {
    private val initialSettings = settingsRepository.settings.value
    private val initialAgentTokenSettings = agentTokenRepository.settings.value
    private val _uiState = MutableStateFlow(
        SettingsUiState(
            baseUrl = initialSettings.baseUrl,
            model = initialSettings.model,
            hasApiKey = initialSettings.hasApiKey,
            maskedApiKey = initialSettings.maskedApiKey,
            hasAgentToken = initialAgentTokenSettings.hasToken,
            maskedAgentToken = initialAgentTokenSettings.maskedToken,
        ),
    )
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            contactPolicyRepository.contacts.collect { contacts ->
                _uiState.update { it.copy(contacts = contacts) }
            }
        }
        viewModelScope.launch {
            autoReplySettingsRepository.enabled.collect { enabled ->
                _uiState.update { it.copy(autoReplyEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            accessibilityConnectionState.connected.collect { connected ->
                _uiState.update {
                    it.copy(
                        accessibilityConnected = connected,
                        accessibilityEnabled = connected || accessibilityAccess.isEnabled(),
                    )
                }
            }
        }
        viewModelScope.launch {
            delegationManager.activeSession.collect { session ->
                _uiState.update { it.withActiveDelegationSession(session) }
            }
        }
        viewModelScope.launch {
            notificationListenerConnectionState.connected.collect { connected ->
                _uiState.update { it.withNotificationListenerConnection(connected) }
            }
        }
    }

    fun onBaseUrlChange(value: String) = _uiState.update { it.copy(baseUrl = value, message = null) }
    fun onModelChange(value: String) = _uiState.update { it.copy(model = value, message = null) }
    fun onApiKeyChange(value: String) = _uiState.update { it.copy(apiKeyInput = value, message = null) }
    fun onAgentTokenChange(value: String) = _uiState.update {
        it.copy(
            agentTokenInput = value,
            agentTokenMessage = null,
        )
    }

    fun save() {
        val state = _uiState.value
        validationError(state)?.let { error ->
            _uiState.update { it.copy(message = error, messageIsError = true) }
            return
        }
        runCatching {
            settingsRepository.save(state.baseUrl, state.model, state.apiKeyInput.ifBlank { null })
        }.onSuccess {
            syncFromRepository("设置已安全保存", isError = false)
        }.onFailure {
            _uiState.update { it.copy(message = "保存失败，无法访问安全存储", messageIsError = true) }
        }
    }

    fun clearApiKey() {
        settingsRepository.clearApiKey()
        syncFromRepository("API Key 已删除", isError = false)
    }

    fun saveAgentToken() {
        val token = _uiState.value.agentTokenInput.trim()
        if (token.isEmpty()) {
            _uiState.update {
                it.copy(
                    agentTokenMessage = "请输入 Android Agent Token",
                    agentTokenMessageIsError = true,
                )
            }
            return
        }

        runCatching { agentTokenRepository.save(token) }
            .onSuccess { syncAgentToken("Android Agent Token 已安全保存", isError = false) }
            .onFailure { syncAgentToken("Android Agent Token 保存失败", isError = true) }
    }

    fun clearAgentToken() {
        runCatching { agentTokenRepository.clear() }
            .onSuccess { syncAgentToken("Android Agent Token 已删除", isError = false) }
            .onFailure { syncAgentToken("Android Agent Token 删除失败", isError = true) }
    }

    fun setAllowlisted(item: ContactPolicyItem, allowlisted: Boolean) {
        val limit = if (allowlisted) {
            item.replyLimit.takeIf { it > ContactPolicyRepository.DEFAULT_LIMIT } ?: 5
        } else {
            ContactPolicyRepository.DEFAULT_LIMIT
        }
        updateContactPolicy(item, allowlisted, limit)
    }

    fun setReplyLimit(item: ContactPolicyItem, limit: Int) {
        updateContactPolicy(item, allowlisted = true, replyLimit = limit)
    }

    fun setAutoReplyEnabled(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { autoReplySettingsRepository.setEnabled(enabled) }
                .onSuccess {
                    eventRepository.record(
                        "REPLY_DELIVERY_SETTING",
                        "OWNER_TRUSTED：微信自动回复=${if (enabled) "开启" else "关闭"}",
                    )
                    _uiState.update {
                        it.copy(
                            autoReplyMessage = if (enabled) {
                                "已开启；优先用通知快捷回复，否则经界面校验后使用无障碍发送"
                            } else {
                                "已关闭；Agent 只生成准备回复"
                            },
                            autoReplyMessageIsError = false,
                        )
                    }
                }
                .onFailure {
                    _uiState.update {
                        it.copy(
                            autoReplyMessage = "自动回复设置保存失败",
                            autoReplyMessageIsError = true,
                        )
                    }
                }
        }
    }

    fun refreshAccessibilityState() {
        _uiState.update {
            it.copy(accessibilityEnabled = accessibilityAccess.isEnabled())
        }
    }

    fun refreshWechatDelegationState() {
        val notificationGranted = NotificationAccess.isGranted(context)
        if (notificationGranted) NotificationAccess.requestRebind(context)
        _uiState.update {
            it.withNotificationPermissions(
                accessGranted = notificationGranted,
                summaryGranted = SummaryNotificationPermission.isGranted(context),
            )
        }
    }

    fun reconnectNotificationListener() {
        NotificationAccess.requestRebind(context)
    }

    fun startSleepDelegation() {
        if (_uiState.value.isDelegationChanging) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDelegationChanging = true,
                    delegationMessage = null,
                )
            }
            when (val result = delegationManager.startSleepDelegation()) {
                is StartSessionResult.Created -> _uiState.update {
                    it.copy(
                        isDelegationChanging = false,
                        delegationMessage = "睡眠托管已开启",
                        delegationMessageIsError = false,
                    )
                }
                is StartSessionResult.AlreadyActive -> _uiState.update {
                    it.copy(
                        isDelegationChanging = false,
                        delegationMessage = "睡眠托管已经开启",
                        delegationMessageIsError = false,
                    )
                }
                is StartSessionResult.Failure -> _uiState.update {
                    it.copy(
                        isDelegationChanging = false,
                        delegationMessage = result.message,
                        delegationMessageIsError = true,
                    )
                }
            }
        }
    }

    fun endDelegation() {
        if (_uiState.value.isDelegationChanging) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isDelegationChanging = true,
                    delegationMessage = null,
                )
            }
            when (val result = delegationManager.endDelegation()) {
                is EndSessionResult.Ended -> _uiState.update {
                    it.copy(
                        isDelegationChanging = false,
                        delegationMessage = "睡眠托管已结束，总结正在保存",
                        delegationMessageIsError = false,
                    )
                }
                EndSessionResult.NoActiveSession -> _uiState.update {
                    it.copy(
                        isDelegationChanging = false,
                        delegationMessage = "当前没有活动的托管 Session",
                        delegationMessageIsError = true,
                    )
                }
                is EndSessionResult.Failure -> _uiState.update {
                    it.copy(
                        isDelegationChanging = false,
                        delegationMessage = result.message,
                        delegationMessageIsError = true,
                    )
                }
            }
        }
    }

    fun testAgentServerConnection() {
        if (_uiState.value.agentConnectionStatus == AgentConnectionStatus.CONNECTING) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    agentConnectionStatus = AgentConnectionStatus.CONNECTING,
                    agentConnectionMessage = "正在连接",
                )
            }

            when (val result = agentHealthClient.check()) {
                AgentHealthResult.Success -> {
                    _uiState.update {
                        it.copy(
                            agentConnectionStatus = AgentConnectionStatus.CONNECTED,
                            agentConnectionMessage = "Agent 服务连接成功",
                        )
                    }
                }

                is AgentHealthResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            agentConnectionStatus = AgentConnectionStatus.FAILED,
                            agentConnectionMessage = "Agent 服务连接失败：${result.detail}",
                        )
                    }
                }
            }
        }
    }

    fun testConnection() {
        val state = _uiState.value
        validationError(state)?.let { error ->
            _uiState.update { it.copy(message = error, messageIsError = true) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isTesting = true, message = null) }
            try {
                settingsRepository.save(state.baseUrl, state.model, state.apiKeyInput.ifBlank { null })
                val apiKey = settingsRepository.apiKeyForRequest()
                val result = if (apiKey == null) {
                    ConnectionTestResult.Failure("请先输入并保存 DeepSeek API Key")
                } else {
                    connectionTester.test(state.baseUrl, apiKey)
                }
                when (result) {
                    ConnectionTestResult.Success -> {
                        eventRepository.record("API", "DeepSeek 连接测试成功")
                        syncFromRepository("连接成功", isError = false)
                    }
                    is ConnectionTestResult.Failure -> {
                        eventRepository.record("API", "DeepSeek 连接测试失败：${result.message}")
                        syncFromRepository(result.message, isError = true)
                    }
                }
            } catch (_: Exception) {
                eventRepository.record("API", "DeepSeek 连接测试失败：本地配置异常")
                syncFromRepository("连接测试失败，请检查配置", isError = true)
            } finally {
                _uiState.update { it.copy(isTesting = false) }
            }
        }
    }

    private fun validationError(state: SettingsUiState): String? =
        ApiSettingsValidator.validateBaseUrl(state.baseUrl)
            ?: ApiSettingsValidator.validateModel(state.model)

    private fun updateContactPolicy(
        item: ContactPolicyItem,
        allowlisted: Boolean,
        replyLimit: Int,
    ) {
        viewModelScope.launch {
            runCatching { contactPolicyRepository.update(item, allowlisted, replyLimit) }
                .onSuccess {
                    eventRepository.record(
                        "CONTACT_POLICY",
                        "OWNER_TRUSTED：${item.displayName}；白名单=$allowlisted；回复上限=$replyLimit",
                    )
                    _uiState.update { it.copy(contactMessage = "联系人策略已保存") }
                }
                .onFailure {
                    _uiState.update { it.copy(contactMessage = "联系人策略保存失败") }
                }
        }
    }

    private fun syncFromRepository(message: String, isError: Boolean) {
        val settings = settingsRepository.settings.value
        _uiState.update {
            it.copy(
                baseUrl = settings.baseUrl,
                model = settings.model,
                apiKeyInput = "",
                hasApiKey = settings.hasApiKey,
                maskedApiKey = settings.maskedApiKey,
                message = message,
                messageIsError = isError,
            )
        }
    }

    private fun syncAgentToken(message: String, isError: Boolean) {
        val tokenSettings = agentTokenRepository.settings.value
        _uiState.update {
            it.copy(
                agentTokenInput = "",
                hasAgentToken = tokenSettings.hasToken,
                maskedAgentToken = tokenSettings.maskedToken,
                agentTokenMessage = message,
                agentTokenMessageIsError = isError,
            )
        }
    }
}
