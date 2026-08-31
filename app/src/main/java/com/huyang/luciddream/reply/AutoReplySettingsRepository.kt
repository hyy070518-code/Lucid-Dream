package com.huyang.luciddream.reply

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AutoReplyState {
    val isEnabled: Boolean
}

@Singleton
class AutoReplySettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) : AutoReplyState {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    override val isEnabled: Boolean
        get() = _enabled.value

    @SuppressLint("UseKtx") // The KTX helper does not expose commit() failure to the caller.
    fun setEnabled(enabled: Boolean) {
        check(preferences.edit().putBoolean(KEY_ENABLED, enabled).commit()) {
            "Unable to persist auto-reply setting"
        }
        _enabled.value = enabled
    }

    private companion object {
        const val PREFERENCES_NAME = "auto_reply_settings"
        const val KEY_ENABLED = "wechat_auto_reply_enabled"
    }
}
