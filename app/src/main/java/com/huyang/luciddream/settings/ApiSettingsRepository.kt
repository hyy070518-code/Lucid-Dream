package com.huyang.luciddream.settings

import android.content.Context
import androidx.core.content.edit
import com.huyang.luciddream.security.SecureApiKeyStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class ApiSettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val secureApiKeyStore: SecureApiKeyStore,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ApiSettings> = _settings.asStateFlow()

    fun save(baseUrl: String, model: String, newApiKey: String?) {
        preferences.edit {
            putString(KEY_BASE_URL, baseUrl.trim().trimEnd('/'))
            putString(KEY_MODEL, model.trim())
        }
        if (!newApiKey.isNullOrBlank()) secureApiKeyStore.save(newApiKey.trim())
        _settings.value = loadSettings()
    }

    fun clearApiKey() {
        secureApiKeyStore.clear()
        _settings.value = loadSettings()
    }

    fun apiKeyForRequest(): String? = secureApiKeyStore.read()

    private fun loadSettings(): ApiSettings {
        val apiKey = secureApiKeyStore.read()
        val storedModel = preferences.getString(KEY_MODEL, null)
        val model = when (storedModel) {
            "deepseek-chat", "deepseek-reasoner" -> ApiSettings.DEFAULT_MODEL
            null -> ApiSettings.DEFAULT_MODEL
            else -> storedModel
        }
        if (storedModel != null && storedModel != model) {
            preferences.edit { putString(KEY_MODEL, model) }
        }
        return ApiSettings(
            baseUrl = preferences.getString(KEY_BASE_URL, null) ?: ApiSettings.DEFAULT_BASE_URL,
            model = model,
            hasApiKey = !apiKey.isNullOrBlank(),
            maskedApiKey = apiKey?.let(::maskApiKey),
        )
    }

    private fun maskApiKey(apiKey: String): String {
        val suffix = apiKey.takeLast(4)
        return "sk-****$suffix"
    }

    private companion object {
        const val PREFERENCES_NAME = "api_settings"
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
    }
}
