package com.huyang.luciddream.reply

import com.huyang.luciddream.data.entity.ExternalMessageEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReplyDeliveryPolicy @Inject constructor(
    private val autoReplyState: AutoReplyState,
) {
    fun authorize(context: ReplyDeliveryContext): ReplyDeliveryAuthorization {
        if (!autoReplyState.isEnabled) {
            return ReplyDeliveryAuthorization.Deny("PREVIEW_ONLY", "微信自动回复总开关未开启")
        }
        if (context.sourcePackage != WECHAT_PACKAGE) {
            return ReplyDeliveryAuthorization.Deny("SOURCE_DENIED", "来源未被发送策略允许")
        }
        if (context.trustLevel != ExternalMessageEntity.TRUST_EXTERNAL_UNTRUSTED) {
            return ReplyDeliveryAuthorization.Deny("TRUST_MISMATCH", "外部回复信任标记异常")
        }
        if (context.reply.isBlank() || context.reply.length > MAX_REPLY_LENGTH) {
            return ReplyDeliveryAuthorization.Deny("REPLY_INVALID", "准备回复为空或过长")
        }
        if (context.transport == null) {
            return ReplyDeliveryAuthorization.Deny(
                "UNSUPPORTED",
                "微信通知没有提供 Android RemoteInput 快捷回复入口",
            )
        }
        return ReplyDeliveryAuthorization.Allow
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val MAX_REPLY_LENGTH = 1_000
    }
}
