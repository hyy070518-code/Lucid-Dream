package com.huyang.luciddream.settings

data class ApiSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val hasApiKey: Boolean = false,
    val maskedApiKey: String? = null,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
    }
}
