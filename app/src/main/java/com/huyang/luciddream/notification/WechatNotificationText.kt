package com.huyang.luciddream.notification

object WechatNotificationText {
    fun latestContent(snapshot: NotificationSnapshot): String? {
        snapshot.messages.lastOrNull { !it.text.isNullOrBlank() }
            ?.text
            ?.trim()
            ?.let { return it }
        return listOf(snapshot.bigText, snapshot.text)
            .firstNotNullOfOrNull { candidate ->
                candidate?.trim()?.takeIf { it.isNotBlank() }?.let { raw ->
                    parseAggregate(raw)?.content ?: raw
                }
            }
    }

    fun latestSender(snapshot: NotificationSnapshot): String? {
        snapshot.messages.lastOrNull { !it.text.isNullOrBlank() }
            ?.sender
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val aggregateSender = listOf(snapshot.bigText, snapshot.text)
            .firstNotNullOfOrNull { candidate ->
                candidate?.trim()?.let(::parseAggregate)?.sender
            }
        return aggregateSender ?: snapshot.title?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseAggregate(raw: String): Aggregate? {
        val match = AGGREGATE_PATTERN.matchEntire(raw) ?: return null
        val sender = match.groupValues[1].trim()
        val content = match.groupValues[2].trim()
        if (sender.isBlank() || content.isBlank()) return null
        return Aggregate(sender, content)
    }

    private data class Aggregate(val sender: String, val content: String)

    private val AGGREGATE_PATTERN = Regex(
        pattern = """^\[\s*\d+\s*条]\s*(.+?)\s*[:：]\s*(.+)$""",
        option = RegexOption.DOT_MATCHES_ALL,
    )
}
