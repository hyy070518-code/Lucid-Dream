package com.huyang.luciddream.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationNormalizerTest {
    private val classifier = TrustClassifier()
    private val normalizer = NotificationNormalizer(MessageSourceRegistry(), classifier)

    @Test
    fun `wechat messaging style uses last message and marks it external untrusted`() {
        val result = normalizer.normalize(
            snapshot(
                title = "旧标题",
                text = "旧正文",
                messages = listOf(
                    NotificationMessagePart("Alice", "第一条", 100L),
                    NotificationMessagePart("Bob", "第二条", 200L),
                ),
            ),
        )

        val accepted = assertType<NormalizationResult.Accepted>(result).message
        assertEquals("Bob", accepted.sender)
        assertEquals("第二条", accepted.content)
        assertEquals(200L, accepted.sourceTimestamp)
        assertEquals(TrustLevel.EXTERNAL_UNTRUSTED, accepted.trustLevel)
        assertEquals("微信", accepted.sourceApp)
    }

    @Test
    fun `wechat standard notification falls back to title and big text`() {
        val result = normalizer.normalize(
            snapshot(title = "文件传输助手", text = "短文本", bigText = "完整文本"),
        )

        val accepted = assertType<NormalizationResult.Accepted>(result).message
        assertEquals("文件传输助手", accepted.sender)
        assertEquals("完整文本", accepted.content)
    }

    @Test
    fun `wechat aggregated notification keeps only latest visible message`() {
        val result = normalizer.normalize(
            snapshot(title = "。", text = "[2条]。: 你在吗", bigText = "[2条]。: 你在吗"),
        )

        val accepted = assertType<NormalizationResult.Accepted>(result).message
        assertEquals("。", accepted.sender)
        assertEquals("你在吗", accepted.content)
    }

    @Test
    fun `missing sender is Unknown rather than guessed`() {
        val result = normalizer.normalize(snapshot(title = null, text = "你好"))

        assertEquals(
            NotificationNormalizer.UNKNOWN_SENDER,
            assertType<NormalizationResult.Accepted>(result).message.sender,
        )
    }

    @Test
    fun `unsupported package is rejected without treating it as supported`() {
        val result = normalizer.normalize(
            snapshot(packageName = "com.example.other", title = "A", text = "B"),
        )

        val rejected = assertType<NormalizationResult.Rejected>(result)
        assertEquals("UNSUPPORTED_SOURCE", rejected.reason)
        assertFalse(rejected.isSupportedSource)
    }

    @Test
    fun `wechat group summary is rejected`() {
        val result = normalizer.normalize(snapshot(text = "3条新消息", isGroupSummary = true))

        val rejected = assertType<NormalizationResult.Rejected>(result)
        assertEquals("GROUP_SUMMARY", rejected.reason)
        assertTrue(rejected.isSupportedSource)
    }

    @Test
    fun `all notification origins classify external untrusted`() {
        assertEquals(
            TrustLevel.EXTERNAL_UNTRUSTED,
            classifier.classifyExternalNotification("com.tencent.mm"),
        )
        assertEquals(TrustLevel.OWNER_TRUSTED, classifier.classifyOwnerChat())
    }

    private fun snapshot(
        packageName: String = "com.tencent.mm",
        title: String? = "Alice",
        text: String? = null,
        bigText: String? = null,
        messages: List<NotificationMessagePart> = emptyList(),
        isGroupSummary: Boolean = false,
    ) = NotificationSnapshot(
        key = "key",
        notificationId = 7,
        packageName = packageName,
        postedAt = 123L,
        title = title,
        text = text,
        bigText = bigText,
        messages = messages,
        isGroupSummary = isGroupSummary,
    )

    private inline fun <reified T> assertType(value: Any): T {
        assertTrue("Expected ${T::class.java.simpleName}, got ${value::class.java.simpleName}", value is T)
        return value as T
    }
}
