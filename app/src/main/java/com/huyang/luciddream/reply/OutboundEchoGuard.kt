package com.huyang.luciddream.reply

import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

class EchoToken internal constructor(internal val key: String)

@Singleton
class OutboundEchoGuard @Inject constructor() {
    private val pending = LinkedHashMap<String, Long>()

    @Synchronized
    fun register(notificationKey: String, reply: String, now: Long = System.currentTimeMillis()): EchoToken {
        purge(now)
        val key = fingerprint(notificationKey, reply)
        pending[key] = now + TTL_MILLIS
        return EchoToken(key)
    }

    @Synchronized
    fun cancel(token: EchoToken) {
        pending.remove(token.key)
    }

    @Synchronized
    fun consumeIfEcho(
        notificationKey: String,
        content: String,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        purge(now)
        return pending.remove(fingerprint(notificationKey, content)) != null
    }

    private fun purge(now: Long) {
        pending.entries.removeAll { it.value < now }
    }

    private fun fingerprint(notificationKey: String, content: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest("$notificationKey\u0000${content.trim()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TTL_MILLIS = 2 * 60 * 1_000L
    }
}
