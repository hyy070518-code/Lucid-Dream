package com.huyang.luciddream.notification

import javax.inject.Inject
import javax.inject.Singleton

data class MessageSource(
    val packageName: String,
    val displayName: String,
)

@Singleton
class MessageSourceRegistry @Inject constructor() {
    fun find(packageName: String): MessageSource? = SOURCES[packageName]

    private companion object {
        val SOURCES = mapOf(
            "com.tencent.mm" to MessageSource(
                packageName = "com.tencent.mm",
                displayName = "微信",
            ),
        )
    }
}
