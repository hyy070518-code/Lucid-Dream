package com.huyang.luciddream.network

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekToolCallingTest {
    private val json = Json { explicitNulls = false }
    private val messages = listOf(DeepSeekMessage(role = "user", content = "打开系统设置"))

    @Test
    fun ownerRequestRegistersOfficialAndroidAgentToolWithAutoChoice() {
        val payload = Json.parseToJsonElement(
            json.encodeToString(ownerToolDeepSeekRequest("deepseek-v4-flash", messages)),
        ).jsonObject

        assertEquals("auto", payload["tool_choice"]?.jsonPrimitive?.content)
        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        val function = tool["function"]!!.jsonObject
        assertEquals("android_agent", function["name"]?.jsonPrimitive?.content)
        val parameters = function["parameters"]!!.jsonObject
        assertEquals(listOf("task"), parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(parameters["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(
            setOf("task"),
            parameters["properties"]!!.jsonObject.keys,
        )
    }

    @Test
    fun structuredRequestUsedByDelegationDoesNotContainTools() {
        val payload = Json.parseToJsonElement(
            json.encodeToString(structuredDeepSeekRequest("deepseek-v4-flash", messages)),
        ).jsonObject

        assertFalse(payload.containsKey("tools"))
        assertFalse(payload.containsKey("tool_choice"))
    }

    @Test
    fun normalTextResponseRemainsARegularCompletion() {
        val result = DeepSeekChatResponse(
            choices = listOf(
                DeepSeekChoice(
                    message = DeepSeekResponseMessage(content = "{\"action\":\"REPLY\"}"),
                    finishReason = "stop",
                ),
            ),
        ).toCompletionResult()

        assertEquals(
            DeepSeekCompletionResult.Success("{\"action\":\"REPLY\"}"),
            result,
        )
    }

    @Test
    fun officialToolCallsResponseIsPreservedForOwnerParser() {
        val response = json.decodeFromString<DeepSeekChatResponse>(
            """
            {
              "choices": [{
                "message": {
                  "content": null,
                  "tool_calls": [{
                    "id": "call-123",
                    "type": "function",
                    "function": {
                      "name": "android_agent",
                      "arguments": "{\"task\":\"打开系统设置\"}"
                    }
                  }]
                },
                "finish_reason": "tool_calls"
              }]
            }
            """.trimIndent(),
        )
        val call = response.choices.single().message.toolCalls.single()
        val result = response.toCompletionResult()

        assertEquals(DeepSeekCompletionResult.Success("", listOf(call)), result)
    }

    @Test
    fun abnormalToolCallsFinishReasonFailsClosed() {
        val result = DeepSeekChatResponse(
            choices = listOf(
                DeepSeekChoice(
                    message = DeepSeekResponseMessage(
                        content = null,
                        toolCalls = listOf(
                            DeepSeekToolCall(
                                id = "call-123",
                                type = "function",
                                function = DeepSeekToolCallFunction("android_agent", "{}"),
                            ),
                        ),
                    ),
                    finishReason = "stop",
                ),
            ),
        ).toCompletionResult()

        assertTrue(result is DeepSeekCompletionResult.Failure)
    }

    @Test
    fun continuationUsesOriginalAssistantToolCallAndMatchingRoleToolId() {
        val originalCall = DeepSeekToolCall(
            id = "call-original-123",
            type = "function",
            function = DeepSeekToolCallFunction(
                name = "android_agent",
                arguments = "{\"task\":\"打开微信\"}",
            ),
        )
        val payload = Json.parseToJsonElement(
            json.encodeToString(
                ownerToolContinuationDeepSeekRequest(
                    model = "deepseek-v4-flash",
                    originalMessages = messages,
                    assistantToolCall = originalCall,
                    toolResultContent = "{\"status\":\"completed\"}",
                ),
            ),
        ).jsonObject
        val continuationMessages = payload["messages"]!!.jsonArray
        val assistant = continuationMessages[1].jsonObject
        val tool = continuationMessages[2].jsonObject

        assertEquals("assistant", assistant["role"]?.jsonPrimitive?.content)
        assertEquals(
            "call-original-123",
            assistant["tool_calls"]!!.jsonArray.single().jsonObject["id"]?.jsonPrimitive?.content,
        )
        assertEquals("tool", tool["role"]?.jsonPrimitive?.content)
        assertEquals("call-original-123", tool["tool_call_id"]?.jsonPrimitive?.content)
        assertEquals("{\"status\":\"completed\"}", tool["content"]?.jsonPrimitive?.content)
    }
}
