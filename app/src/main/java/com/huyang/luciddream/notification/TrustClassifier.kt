package com.huyang.luciddream.notification

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrustClassifier @Inject constructor() {
    fun classifyExternalNotification(packageName: String): TrustLevel {
        require(packageName.isNotBlank())
        return TrustLevel.EXTERNAL_UNTRUSTED
    }

    fun classifyOwnerChat(): TrustLevel = TrustLevel.OWNER_TRUSTED
}
