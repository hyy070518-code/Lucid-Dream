package com.huyang.luciddream.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WechatScreenVerifierTest {
    @Test
    fun `message content verifies intended chat`() {
        assertTrue(WechatScreenVerifier.matches("在干嘛呢  发送", "。", "在干嘛呢"))
    }

    @Test
    fun `meaningful sender can verify chat`() {
        assertTrue(WechatScreenVerifier.matches("Alice\n你好", "Alice", "新消息"))
    }

    @Test
    fun `punctuation-only sender never bypasses missing message`() {
        assertFalse(WechatScreenVerifier.matches("其他聊天", "。", ""))
    }
}
