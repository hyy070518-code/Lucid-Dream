package com.huyang.luciddream.policy

import com.huyang.luciddream.notification.NormalizedExternalMessage
import com.huyang.luciddream.notification.NotificationNormalizer
import com.huyang.luciddream.notification.TrustLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactIdentityResolverTest {
    private val resolver = ContactIdentityResolver()

    @Test
    fun `known sender name is normalized for temporary identity`() {
        val first = resolver.resolve(message(" Alice ", "key-1"))
        val second = resolver.resolve(message("alice", "key-2"))

        assertEquals(first.key, second.key)
        assertTrue(first.key.startsWith("com.tencent.mm|name:"))
    }

    @Test
    fun `unknown senders use notification key instead of sharing one global budget`() {
        val first = resolver.resolve(message(NotificationNormalizer.UNKNOWN_SENDER, "key-1"))
        val second = resolver.resolve(message(NotificationNormalizer.UNKNOWN_SENDER, "key-2"))

        assertNotEquals(first.key, second.key)
    }

    private fun message(sender: String, key: String) = NormalizedExternalMessage(
        notificationKey = key,
        notificationId = 1,
        sourcePackage = "com.tencent.mm",
        sourceApp = "微信",
        sender = sender,
        content = "你好",
        sourceTimestamp = 1L,
        trustLevel = TrustLevel.EXTERNAL_UNTRUSTED,
    )
}
