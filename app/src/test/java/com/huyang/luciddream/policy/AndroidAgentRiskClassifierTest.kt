package com.huyang.luciddream.policy

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidAgentRiskClassifierTest {
    private val classifier = AndroidAgentRiskClassifier()

    @Test
    fun navigationAndReadOnlyTasksAreLowRisk() {
        assertTasks(
            ToolRiskCategory.LOW,
            "打开微信",
            "打开系统设置",
            "进入设置查看电池页面",
            "返回桌面",
            "打开相册",
            "查看系统版本",
        )
    }

    @Test
    fun reversibleLocalChangesAreMediumRisk() {
        assertTasks(
            ToolRiskCategory.MEDIUM,
            "调整屏幕亮度设置",
            "在备注框填写测试文字",
            "关闭蓝牙开关",
            "切换普通功能模式",
        )
    }

    @Test
    fun externalEffectsDestructiveActionsAndTransactionsAreHighRisk() {
        assertTasks(
            ToolRiskCategory.HIGH,
            "给 DemoContact 发送“我到了”",
            "删除这张照片",
            "发布一条朋友圈",
            "帮我下单购买商品",
            "给同事发送邮件",
            "提交这份表单",
        )
    }

    @Test
    fun authenticationSecretsAndIdentityChecksAreAuthenticationRisk() {
        assertTasks(
            ToolRiskCategory.AUTHENTICATION,
            "输入验证码123456",
            "输入支付密码",
            "完成指纹验证",
            "进行新设备验证",
            "使用 Face ID",
        )
    }

    @Test
    fun higherRiskRulesOverrideOpeningAndNavigationWords() {
        assertEquals(
            ToolRiskCategory.HIGH,
            classifier.classify("打开微信并发送消息"),
        )
        assertEquals(
            ToolRiskCategory.AUTHENTICATION,
            classifier.classify("打开支付宝并输入支付密码"),
        )
    }

    @Test
    fun negatedHighRiskPhraseIsConservativelyUnknownRatherThanLow() {
        assertEquals(
            ToolRiskCategory.UNKNOWN,
            classifier.classify("打开微信，不要发送任何消息"),
        )
    }

    @Test
    fun ambiguousAndEmptyTasksAreUnknown() {
        assertTasks(
            ToolRiskCategory.UNKNOWN,
            "处理一下这件事",
            "帮我弄一下",
            " ",
        )
    }

    private fun assertTasks(expected: ToolRiskCategory, vararg tasks: String) {
        tasks.forEach { task -> assertEquals("task=$task", expected, classifier.classify(task)) }
    }
}
