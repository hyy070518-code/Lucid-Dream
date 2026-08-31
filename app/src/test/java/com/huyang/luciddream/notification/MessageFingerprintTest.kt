package com.huyang.luciddream.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MessageFingerprintTest {
    @Test
    fun `same message produces stable fingerprint and changed content does not`() {
        val first = message("你好")
        val same = message("你好")
        val changed = message("再见")

        assertEquals(MessageFingerprint.of(first), MessageFingerprint.of(same))
        assertNotEquals(MessageFingerprint.of(first), MessageFingerprint.of(changed))
    }

    private fun message(content: String) = NormalizedExternalMessage(
        notificationKey = "key",
        notificationId = 1,
        sourcePackage = "com.tencent.mm",
        sourceApp = "微信",
        sender = "Alice",
        content = content,
        sourceTimestamp = 10L,
        trustLevel = TrustLevel.EXTERNAL_UNTRUSTED,
    )
}
