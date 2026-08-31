package com.huyang.luciddream.settings

import com.huyang.luciddream.security.SecureApiKeyStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentTokenSettings(
    val hasToken: Boolean = false,
    val maskedToken: String? = null,
)

@Singleton
class AgentTokenRepository @Inject constructor(
    private val secureApiKeyStore: SecureApiKeyStore,
) {
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AgentTokenSettings> = _settings.asStateFlow()

    fun save(token: String) {
        val trimmedToken = token.trim()
        require(trimmedToken.isNotEmpty()) { "Agent Token must not be empty" }
        secureApiKeyStore.saveAgentToken(trimmedToken)
        _settings.value = loadSettings()
    }

    fun clear() {
        secureApiKeyStore.clearAgentToken()
        _settings.value = loadSettings()
    }

    fun tokenForRequest(): String? = secureApiKeyStore.readAgentToken()?.takeIf { it.isNotBlank() }

    private fun loadSettings(): AgentTokenSettings {
        val token = secureApiKeyStore.readAgentToken()
        return AgentTokenSettings(
            hasToken = !token.isNullOrBlank(),
            maskedToken = token?.takeIf { it.isNotBlank() }?.let(::maskToken),
        )
    }

    private fun maskToken(token: String): String = if (token.length > 4) {
        "****${token.takeLast(4)}"
    } else {
        "********"
    }
}
