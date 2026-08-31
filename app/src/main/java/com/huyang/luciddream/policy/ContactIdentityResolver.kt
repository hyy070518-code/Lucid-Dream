package com.huyang.luciddream.policy

import com.huyang.luciddream.notification.MessageFingerprint
import com.huyang.luciddream.notification.NormalizedExternalMessage
import com.huyang.luciddream.notification.NotificationNormalizer
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class ContactIdentity(
    val key: String,
    val displayName: String,
    val sourcePackage: String,
)

/**
 * Temporary v0.1 identity: parsed sender name, with notification-key fallback for Unknown.
 * A stable conversation ID from the source app should replace this in a later version.
 */
@Singleton
class ContactIdentityResolver @Inject constructor() {
    fun resolve(message: NormalizedExternalMessage): ContactIdentity {
        val key = if (message.sender == NotificationNormalizer.UNKNOWN_SENDER) {
            "${message.sourcePackage}|notification:${MessageFingerprint.notificationKeyHash(message.notificationKey)}"
        } else {
            val normalizedName = Normalizer.normalize(message.sender.trim(), Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
            "${message.sourcePackage}|name:$normalizedName"
        }
        return ContactIdentity(
            key = key,
            displayName = message.sender,
            sourcePackage = message.sourcePackage,
        )
    }
}
