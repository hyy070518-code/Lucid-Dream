package com.huyang.luciddream.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object ApiSettingsValidator {
    fun validateBaseUrl(value: String): String? {
        val url = value.trim().toHttpUrlOrNull()
            ?: return "Base URL 格式无效"
        if (url.scheme != "https") return "Base URL 必须使用 HTTPS"
        if (url.query != null || url.fragment != null) return "Base URL 不能包含查询参数或片段"
        return null
    }

    fun validateModel(value: String): String? =
        if (value.trim().isEmpty()) "Model 不能为空" else null
}
