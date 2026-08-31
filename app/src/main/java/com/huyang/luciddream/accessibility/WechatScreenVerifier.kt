package com.huyang.luciddream.accessibility

object WechatScreenVerifier {
    fun matches(screenText: String, expectedSender: String, expectedContent: String): Boolean {
        val screen = normalize(screenText)
        val content = normalize(expectedContent)
        val sender = normalize(expectedSender)
        return when {
            content.length >= MIN_MEANINGFUL_LENGTH && screen.contains(content) -> true
            sender.length >= MIN_MEANINGFUL_LENGTH && screen.contains(sender) -> true
            else -> false
        }
    }

    internal fun normalize(value: String): String = value
        .lowercase()
        .filter { it.isLetterOrDigit() }

    private const val MIN_MEANINGFUL_LENGTH = 2
}
