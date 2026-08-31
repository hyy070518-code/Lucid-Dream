package com.huyang.luciddream.notification

import com.huyang.luciddream.data.entity.DelegationSessionEntity
import com.huyang.luciddream.reply.ReplyTransport

interface SafeExternalMessageHandler {
    suspend fun handle(
        message: NormalizedExternalMessage,
        activeSession: DelegationSessionEntity,
        replyTransport: ReplyTransport?,
    )
}
