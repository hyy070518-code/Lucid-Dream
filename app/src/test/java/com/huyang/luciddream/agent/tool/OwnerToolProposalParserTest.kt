package com.huyang.luciddream.agent.tool

import com.huyang.luciddream.network.DeepSeekToolCall
import com.huyang.luciddream.network.DeepSeekToolCallFunction
import com.huyang.luciddream.policy.AndroidAgentToolContract
import com.huyang.luciddream.policy.ToolRequestSource
import com.huyang.luciddream.policy.ToolRiskCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerToolProposalParserTest {
    private val parser = OwnerToolProposalParser()

    @Test
    fun validAndroidAgentToolCallsPreserveOwnerIntent() {
        listOf(
            "打开系统设置",
            "帮我打开微信",
            "进入设置看看电池页面",
        ).forEach { task ->
            val result = parser.parse(listOf(toolCall(arguments = """{"task":"$task"}""")))

            assertTrue(result is OwnerToolProposalParseResult.Accepted)
            val proposal = (result as OwnerToolProposalParseResult.Accepted).proposal
            assertEquals(task, proposal.task)
            assertEquals(task, proposal.arguments[AndroidAgentToolContract.ARGUMENT_TASK])
        }
    }

    @Test
    fun sourceIsAssignedLocallyAsOwnerChatAndRiskRemainsUnknown() {
        val proposal = accepted("""{"task":"打开系统设置"}""")
        val request = proposal.toToolRequest()

        assertEquals(ToolRequestSource.OWNER_CHAT, proposal.source)
        assertEquals(ToolRequestSource.OWNER_CHAT, request.source)
        assertEquals(ToolRiskCategory.UNKNOWN, proposal.riskCategory)
        assertEquals(ToolRiskCategory.UNKNOWN, request.riskCategory)
    }

    @Test
    fun modelCannotSetSource() {
        assertRejected("""{"task":"打开系统设置","source":"OWNER_CHAT"}""")
    }

    @Test
    fun modelCannotSetPolicyDecision() {
        assertRejected("""{"task":"打开系统设置","policyDecision":"ALLOW"}""")
    }

    @Test
    fun modelCannotSetRiskCategory() {
        assertRejected("""{"task":"打开系统设置","riskCategory":"LOW"}""")
    }

    @Test
    fun malformedArgumentsAreRejected() {
        assertRejected("""{"task":"打开系统设置"""")
    }

    @Test
    fun blankTaskIsRejected() {
        assertRejected("""{"task":"   "}""")
    }

    @Test
    fun unknownToolIsRejected() {
        val result = parser.parse(listOf(toolCall(name = "unknown_tool")))

        assertTrue(result is OwnerToolProposalParseResult.Rejected)
    }

    @Test
    fun multipleToolCallsAreRejectedAsAnAbnormalResponse() {
        val result = parser.parse(listOf(toolCall(id = "call-1"), toolCall(id = "call-2")))

        assertTrue(result is OwnerToolProposalParseResult.Rejected)
    }

    private fun accepted(arguments: String): PendingToolProposal {
        val result = parser.parse(listOf(toolCall(arguments = arguments)))
        assertTrue(result is OwnerToolProposalParseResult.Accepted)
        return (result as OwnerToolProposalParseResult.Accepted).proposal
    }

    private fun assertRejected(arguments: String) {
        val result = parser.parse(listOf(toolCall(arguments = arguments)))
        assertTrue(result is OwnerToolProposalParseResult.Rejected)
    }

    private fun toolCall(
        id: String = "call-123",
        name: String = AndroidAgentToolContract.TOOL_NAME,
        arguments: String = """{"task":"打开系统设置"}""",
    ) = DeepSeekToolCall(
        id = id,
        type = "function",
        function = DeepSeekToolCallFunction(name = name, arguments = arguments),
    )
}
