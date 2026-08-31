package com.huyang.luciddream.notification

import java.security.MessageDigest

object MessageFingerprint {
    fun notificationKeyHash(key: String): String = sha256(key).take(24)

    fun safetyEvent(notificationKey: String, sourceTimestamp: Long): String =
        sha256("$notificationKey\u0000$sourceTimestamp")

    fun of(message: NormalizedExternalMessage): String = sha256(
        listOf(
            message.sourcePackage,
            message.notificationId.toString(),
            message.sourceTimestamp.toString(),
            message.sender,
            message.content,
        ).joinToString(separator = "\u0000"),
    )

    private fun sha256(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
