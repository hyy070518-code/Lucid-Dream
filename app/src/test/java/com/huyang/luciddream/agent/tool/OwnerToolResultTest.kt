package com.huyang.luciddream.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OwnerToolResultTest {
    @Test
    fun completedResultContainsOnlyRealTerminalFacts() {
        val content = OwnerToolResultPayload(
            status = OwnerToolResultStatus.COMPLETED,
            task = "打开微信",
            reason = "微信已成功打开",
            taskId = "task-123",
        ).toToolResultContent()
        val payload = Json.parseToJsonElement(content).jsonObject

        assertEquals("completed", payload["status"]?.jsonPrimitive?.content)
        assertEquals("task-123", payload["task_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun failureNeverSerializesAsSuccess() {
        val content = OwnerToolResultPayload(
            status = OwnerToolResultStatus.FAILED,
            task = "播放歌曲",
            reason = "无法完成歌曲播放",
        ).toToolResultContent()

        assertFalse(content.contains("\"status\":\"completed\""))
        assertEquals(
            "failed",
            Json.parseToJsonElement(content).jsonObject["status"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun deniedAndCancelledHaveDistinctClosedResults() {
        val denied = OwnerToolResultPayload(
            OwnerToolResultStatus.DENIED,
            "输入验证码",
            "该操作涉及身份验证，需要用户本人完成",
        ).toToolResultContent()
        val cancelled = OwnerToolResultPayload(
            OwnerToolResultStatus.CANCELLED_BY_USER,
            "发送消息",
            "用户取消了本次手机操作",
        ).toToolResultContent()

        assertEquals(
            "denied",
            Json.parseToJsonElement(denied).jsonObject["status"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "cancelled_by_user",
            Json.parseToJsonElement(cancelled).jsonObject["status"]?.jsonPrimitive?.content,
        )
    }
}
