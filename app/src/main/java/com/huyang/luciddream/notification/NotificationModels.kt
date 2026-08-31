package com.huyang.luciddream.notification

data class NotificationMessagePart(
    val sender: String?,
    val text: String?,
    val timestamp: Long?,
)

data class NotificationSnapshot(
    val key: String,
    val notificationId: Int,
    val packageName: String,
    val postedAt: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val messages: List<NotificationMessagePart> = emptyList(),
    val isGroupSummary: Boolean = false,
)

data class NormalizedExternalMessage(
    val notificationKey: String,
    val notificationId: Int,
    val sourcePackage: String,
    val sourceApp: String,
    val sender: String,
    val content: String,
    val sourceTimestamp: Long,
    val trustLevel: TrustLevel,
)

enum class TrustLevel {
    OWNER_TRUSTED,
    EXTERNAL_UNTRUSTED,
}

sealed interface NormalizationResult {
    data class Accepted(val message: NormalizedExternalMessage) : NormalizationResult
    data class Rejected(val reason: String, val isSupportedSource: Boolean) : NormalizationResult
}
