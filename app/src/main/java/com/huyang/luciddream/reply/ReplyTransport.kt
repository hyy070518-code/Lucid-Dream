package com.huyang.luciddream.reply

interface ReplyTransport {
    val type: String
    suspend fun dispatch(text: String, sessionId: Long): ReplyTransportResult
}

sealed interface ReplyTransportResult {
    data object Dispatched : ReplyTransportResult
    data class Failed(val reason: String) : ReplyTransportResult
    data class Rejected(val status: String, val reason: String) : ReplyTransportResult
}

data class ReplyDeliveryContext(
    val sourcePackage: String,
    val trustLevel: String,
    val reply: String,
    val transport: ReplyTransport?,
)

sealed interface ReplyDeliveryAuthorization {
    data object Allow : ReplyDeliveryAuthorization
    data class Deny(val status: String, val reason: String) : ReplyDeliveryAuthorization
}
