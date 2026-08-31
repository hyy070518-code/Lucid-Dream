package com.huyang.luciddream.reply

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundEchoGuardTest {
    @Test
    fun `registered reply is consumed only once`() {
        val guard = OutboundEchoGuard()
        guard.register("notification-key", " reply ", now = 100L)

        assertTrue(guard.consumeIfEcho("notification-key", "reply", now = 101L))
        assertFalse(guard.consumeIfEcho("notification-key", "reply", now = 102L))
    }

    @Test
    fun `cancelled reply is not suppressed`() {
        val guard = OutboundEchoGuard()
        val token = guard.register("notification-key", "reply", now = 100L)

        guard.cancel(token)

        assertFalse(guard.consumeIfEcho("notification-key", "reply", now = 101L))
    }

    @Test
    fun `old reply expires`() {
        val guard = OutboundEchoGuard()
        guard.register("notification-key", "reply", now = 100L)

        assertFalse(guard.consumeIfEcho("notification-key", "reply", now = 120_101L))
    }
}
