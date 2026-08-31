package com.huyang.luciddream.safety

import com.huyang.luciddream.notification.NormalizedExternalMessage
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** v0.1 local rules; replaceable by another SafetyGateway implementation later. */
@Singleton
class LocalRuleSafetyGateway @Inject constructor() : SafetyGateway {
    override fun evaluate(message: NormalizedExternalMessage): SafetyResult {
        val normalized = message.content.lowercase(Locale.ROOT)
        val matchedRule = RULES.firstOrNull { it.pattern.containsMatchIn(normalized) }
        return if (matchedRule == null) SafetyResult.Pass else SafetyResult.Block(matchedRule.code)
    }

    private data class Rule(val code: String, val pattern: Regex)

    private companion object {
        val RULES = listOf(
            Rule(
                code = "EXPLICIT_SEXUAL_CONTENT",
                pattern = Regex("色情|成人视频|裸聊|约炮|卖淫|嫖娼|强奸"),
            ),
            Rule(
                code = "GAMBLING_CONTENT",
                pattern = Regex("赌博|赌场|下注|博彩|赌资|网络赌盘"),
            ),
            Rule(
                code = "ILLEGAL_DRUGS",
                pattern = Regex("毒品|冰毒|海洛因|可卡因|摇头丸|芬太尼|制毒|贩毒"),
            ),
            Rule(
                code = "DANGEROUS_CRIME",
                pattern = Regex("制作炸弹|制造爆炸物|实施绑架|杀人计划|纵火计划|诈骗教程|洗钱教程|勒索计划"),
            ),
        )
    }
}
