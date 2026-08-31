package com.huyang.luciddream.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentDecisionParserTest {
    private val parser = AgentDecisionParser()

    @Test
    fun parsesValidStructuredDecision() {
        val decision = parser.parseOwnerChat(
            """
            {
              "action": "REPLY",
              "reply": "你好，胡洋。",
              "intent": "ACKNOWLEDGE",
              "needs_owner": false,
              "urgency": "LOW",
              "reason": "回应用户问候"
            }
            """.trimIndent(),
        )

        assertEquals(AgentAction.REPLY, decision.action)
        assertEquals(AgentIntent.ACKNOWLEDGE, decision.intent)
        assertEquals("你好，胡洋。", decision.reply)
    }

    @Test
    fun acceptsJsonInsideOptionalCodeFence() {
        val decision = parser.parseOwnerChat(
            """```json
            {"action":"REPLY","reply":"收到","intent":"ACKNOWLEDGE","needs_owner":false,"urgency":"LOW","reason":"确认"}
            ```""",
        )

        assertEquals("收到", decision.reply)
    }

    @Test
    fun rejectsReplyWithoutText() {
        assertThrows(AgentDecisionParseException::class.java) {
            parser.parse(
                """{"action":"REPLY","intent":"OTHER","needs_owner":false,"urgency":"LOW","reason":"测试"}""",
            )
        }
    }

    @Test
    fun rejectsUnknownFields() {
        assertThrows(AgentDecisionParseException::class.java) {
            parser.parseOwnerChat(
                """{"action":"REPLY","reply":"ok","intent":"OTHER","needs_owner":false,"urgency":"LOW","reason":"测试","tool":"shell"}""",
            )
        }
    }

    @Test
    fun ownerChatRejectsNonReplyAction() {
        assertThrows(AgentDecisionParseException::class.java) {
            parser.parseOwnerChat(
                """{"action":"WAIT","reply":null,"intent":"OTHER","needs_owner":false,"urgency":"LOW","reason":"测试"}""",
            )
        }
    }
}
