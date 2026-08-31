package com.huyang.luciddream.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionSummaryParserTest {
    private val parser = SessionSummaryParser()

    @Test
    fun `parses strict summary json and trims content`() {
        assertEquals("DemoContact 留下邀约，需要本人确认。", parser.parse("""{"summary":"  DemoContact 留下邀约，需要本人确认。  "}"""))
    }

    @Test
    fun `accepts optional markdown fence from model`() {
        assertEquals("没有待办。", parser.parse("""```json
            {"summary":"没有待办。"}
            ```""".trimIndent()))
    }

    @Test
    fun `rejects unknown fields and empty summary`() {
        assertThrows(SessionSummaryParseException::class.java) {
            parser.parse("""{"summary":"内容","extra":true}""")
        }
        assertThrows(SessionSummaryParseException::class.java) {
            parser.parse("""{"summary":" "}""")
        }
    }
}
