package com.huyang.luciddream.reply

import com.huyang.luciddream.data.entity.ExternalMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ReplyDeliveryPolicyTest {
    private val transport = object : ReplyTransport {
        override val type = "REMOTE_INPUT"
        override suspend fun dispatch(text: String, sessionId: Long) = ReplyTransportResult.Dispatched
    }

    @Test
    fun `disabled switch keeps reply in preview mode`() {
        val result = policy(false).authorize(context(transport))

        assertEquals("PREVIEW_ONLY", (result as ReplyDeliveryAuthorization.Deny).status)
    }

    @Test
    fun `enabled switch requires RemoteInput transport`() {
        val result = policy(true).authorize(context(null))

        assertEquals("UNSUPPORTED", (result as ReplyDeliveryAuthorization.Deny).status)
    }

    @Test
    fun `enabled switch still denies non WeChat source`() {
        val result = policy(true).authorize(context(transport).copy(sourcePackage = "other.app"))

        assertEquals("SOURCE_DENIED", (result as ReplyDeliveryAuthorization.Deny).status)
    }

    @Test
    fun `valid WeChat RemoteInput reply is allowed`() {
        val result = policy(true).authorize(context(transport))

        assertSame(ReplyDeliveryAuthorization.Allow, result)
    }

    private fun policy(enabled: Boolean) = ReplyDeliveryPolicy(
        object : AutoReplyState {
            override val isEnabled = enabled
        },
    )

    private fun context(transport: ReplyTransport?) = ReplyDeliveryContext(
        sourcePackage = "com.tencent.mm",
        trustLevel = ExternalMessageEntity.TRUST_EXTERNAL_UNTRUSTED,
        reply = "你好，我是 AI 托管助手。",
        transport = transport,
    )
}
