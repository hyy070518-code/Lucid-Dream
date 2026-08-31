package com.huyang.luciddream.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptBuilderTest {
    private val builder = PromptBuilder()

    @Test
    fun ownerPromptDeclaresTrustAndLimitsToolProposal() {
        val prompt = builder.buildOwnerChatPrompt()

        assertTrue(prompt.contains("OWNER_TRUSTED"))
        assertTrue(prompt.contains("合法 JSON"))
        assertTrue(prompt.contains("android_agent"))
        assertTrue(prompt.contains("Tool Call 只是操作提议"))
        assertTrue(prompt.contains("不得输出 source、riskCategory、authorization 或 policyDecision"))
    }

    @Test
    fun delegationPromptContainsIdentityAndCommitmentBoundaries() {
        val prompt = builder.buildDelegationPrompt(firstContact = true)

        assertTrue(prompt.contains("你不是 胡洋"))
        assertTrue(prompt.contains("EXTERNAL_UNTRUSTED"))
        assertTrue(prompt.contains("不得替 胡洋 接受或拒绝"))
        assertTrue(prompt.contains("必须明确说明自己是 DeepSeek / AI 托管助手"))
        assertFalse(prompt.contains("你就是胡洋"))
    }
}
