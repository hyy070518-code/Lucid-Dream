package com.huyang.luciddream.policy

import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Deterministic v1 risk classifier. It never calls or trusts an LLM. */
@Singleton
class AndroidAgentRiskClassifier @Inject constructor() {
    fun classify(task: String): ToolRiskCategory {
        val normalized = task.trim()
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
        if (normalized.isEmpty()) return ToolRiskCategory.UNKNOWN

        if (AUTHENTICATION_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return ToolRiskCategory.AUTHENTICATION
        }

        val containsHighAction = HIGH_ACTION_PATTERN.containsMatchIn(normalized)
        if (containsHighAction) {
            val withoutNegatedHighActions = NEGATED_HIGH_ACTION_PATTERN.replace(normalized, " ")
            if (HIGH_ACTION_PATTERN.containsMatchIn(withoutNegatedHighActions)) {
                return ToolRiskCategory.HIGH
            }
            // A negated side effect is not authorization to perform it, but v1 also does not
            // downgrade the whole task to LOW based on brittle natural-language negation.
            return ToolRiskCategory.UNKNOWN
        }

        if (MEDIUM_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return ToolRiskCategory.MEDIUM
        }
        if (LOW_PATTERNS.any { it.containsMatchIn(normalized) }) {
            return ToolRiskCategory.LOW
        }
        return ToolRiskCategory.UNKNOWN
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")

        val AUTHENTICATION_PATTERNS = listOf(
            Regex("密码|验证码|指纹|人脸(?:识别|验证)?|生物识别|身份(?:认证|验证)"),
            Regex("新设备(?:认证|验证|确认)|登录(?:认证|验证)|安全(?:认证|验证)|两步验证"),
            Regex("\\b(?:otp|pin|passcode|password|2fa)\\b|face\\s*id|fingerprint|biometric"),
        )

        const val HIGH_ACTION_SOURCE =
            "(?:发送(?:消息|微信|短信|邮件)?|回复(?:消息|微信|短信|邮件)?|发微信|发短信|发邮件|" +
                "打电话|拨打电话|删除|清空|发布|发表|发朋友圈|发帖|发评论|点赞|(?:取消)?关注|" +
                "修改.{0,6}(?:账号|账户)|退出.{0,4}(?:账号|账户|登录)|注销.{0,4}(?:账号|账户)|" +
                "下单|购买|支付(?!宝)|付款|转账|提交.{0,6}(?:表单|订单|申请)|" +
                "\\b(?:send|reply|delete|post|comment|like|follow|buy|purchase|pay|transfer|submit|call)\\b)"

        val HIGH_ACTION_PATTERN = Regex(HIGH_ACTION_SOURCE)

        val NEGATED_HIGH_ACTION_PATTERN = Regex(
            "(?:不要|请勿|别|禁止|无需|不用|不需要|避免|do\\s+not|don't|dont|never|without)" +
                "\\s*[^，。；;,.!?！？\\n]{0,8}?$HIGH_ACTION_SOURCE",
        )

        val MEDIUM_PATTERNS = listOf(
            Regex("输入|填写|填入|键入"),
            Regex("(?:调整|修改|更改|切换|开启|关闭|保存).{0,8}(?:设置|开关|功能|模式|亮度|音量|蓝牙|wi-?fi|无线网络|飞行模式|定位|通知)"),
            Regex("\\b(?:type|input|fill|toggle|enable|disable|change)\\b.{0,20}\\b(?:text|setting|switch|mode|wifi|bluetooth)\\b"),
        )

        val LOW_PATTERNS = listOf(
            Regex("^(?:请|帮我|麻烦)?(?:打开|进入|查看|看看|浏览|返回|回到|前往|导航到|启动|显示|找到)"),
            Regex("^\\s*(?:please\\s+)?(?:open|view|browse|return|go\\s+to|navigate\\s+to|launch|show|find)\\b"),
        )
    }
}
