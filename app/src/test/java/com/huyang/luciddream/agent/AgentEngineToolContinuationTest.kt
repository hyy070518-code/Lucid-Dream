package com.huyang.luciddream.agent

import com.huyang.luciddream.network.DeepSeekCompletionResult
import com.huyang.luciddream.network.DeepSeekToolCall
import com.huyang.luciddream.network.DeepSeekToolCallFunction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEngineToolContinuationTest {
    @Test
    fun finalTextIsAcceptedAfterOneToolExecution() {
        val completion = acceptOwnerToolContinuationCompletion(
            DeepSeekCompletionResult.Success("{\"action\":\"REPLY\"}"),
        )

        assertEquals(
            OwnerToolContinuationCompletion.FinalContent("{\"action\":\"REPLY\"}"),
            completion,
        )
    }

    @Test
    fun secondToolCallFailsClosedInsteadOfExecutingAgain() {
        val completion = acceptOwnerToolContinuationCompletion(
            DeepSeekCompletionResult.Success(
                content = "",
                toolCalls = listOf(
                    DeepSeekToolCall(
                        id = "call-second",
                        type = "function",
                        function = DeepSeekToolCallFunction(
                            name = "android_agent",
                            arguments = "{\"task\":\"打开设置\"}",
                        ),
                    ),
                ),
            ),
        )

        assertTrue(completion is OwnerToolContinuationCompletion.Failure)
        assertTrue(
            (completion as OwnerToolContinuationCompletion.Failure).message.contains("重新确认"),
        )
    }
}
