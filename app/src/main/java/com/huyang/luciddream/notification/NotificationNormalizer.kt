package com.huyang.luciddream.notification

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationNormalizer @Inject constructor(
    private val sourceRegistry: MessageSourceRegistry,
    private val trustClassifier: TrustClassifier,
) {
    fun normalize(snapshot: NotificationSnapshot): NormalizationResult {
        val source = sourceRegistry.find(snapshot.packageName)
            ?: return NormalizationResult.Rejected(
                reason = "UNSUPPORTED_SOURCE",
                isSupportedSource = false,
            )

        if (snapshot.isGroupSummary) {
            return NormalizationResult.Rejected("GROUP_SUMMARY", isSupportedSource = true)
        }

        val latestMessage = snapshot.messages.lastOrNull { !it.text.isNullOrBlank() }
        val content = WechatNotificationText.latestContent(snapshot)?.take(MAX_CONTENT_LENGTH)
            ?: return NormalizationResult.Rejected("EMPTY_CONTENT", isSupportedSource = true)

        val sender = WechatNotificationText.latestSender(snapshot)
            ?.take(MAX_SENDER_LENGTH)
            ?: UNKNOWN_SENDER
        val sourceTimestamp = latestMessage?.timestamp
            ?.takeIf { it > 0L }
            ?: snapshot.postedAt

        return NormalizationResult.Accepted(
            NormalizedExternalMessage(
                notificationKey = snapshot.key,
                notificationId = snapshot.notificationId,
                sourcePackage = source.packageName,
                sourceApp = source.displayName,
                sender = sender,
                content = content,
                sourceTimestamp = sourceTimestamp,
                trustLevel = trustClassifier.classifyExternalNotification(snapshot.packageName),
            ),
        )
    }

    companion object {
        const val UNKNOWN_SENDER = "Unknown"
        private const val MAX_SENDER_LENGTH = 200
        private const val MAX_CONTENT_LENGTH = 4_000
    }
}
