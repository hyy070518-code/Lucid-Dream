package com.huyang.luciddream.safety

import com.huyang.luciddream.notification.NormalizedExternalMessage

interface SafetyGateway {
    fun evaluate(message: NormalizedExternalMessage): SafetyResult
}

sealed interface SafetyResult {
    data object Pass : SafetyResult
    data class Block(val reasonCode: String) : SafetyResult
}
