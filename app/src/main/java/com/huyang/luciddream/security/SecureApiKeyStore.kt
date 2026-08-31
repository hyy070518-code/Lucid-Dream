package com.huyang.luciddream.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SecureApiKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun save(apiKey: String) {
        saveCredential(apiKey, KEY_CIPHERTEXT, KEY_IV)
    }

    @Synchronized
    fun read(): String? = readCredential(KEY_CIPHERTEXT, KEY_IV)

    @Synchronized
    fun clear() {
        clearCredential(KEY_CIPHERTEXT, KEY_IV)
    }

    @Synchronized
    fun saveAgentToken(token: String) {
        saveCredential(token, KEY_AGENT_TOKEN_CIPHERTEXT, KEY_AGENT_TOKEN_IV)
    }

    @Synchronized
    fun readAgentToken(): String? = readCredential(
        KEY_AGENT_TOKEN_CIPHERTEXT,
        KEY_AGENT_TOKEN_IV,
    )

    @Synchronized
    fun clearAgentToken() {
        clearCredential(KEY_AGENT_TOKEN_CIPHERTEXT, KEY_AGENT_TOKEN_IV)
    }

    private fun saveCredential(value: String, ciphertextKey: String, ivKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit {
            putString(ciphertextKey, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            putString(ivKey, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
    }

    private fun readCredential(ciphertextKey: String, ivKey: String): String? {
        val encrypted = preferences.getString(ciphertextKey, null) ?: return null
        val iv = preferences.getString(ivKey, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
            plaintext.toString(Charsets.UTF_8)
        }.getOrElse {
            clearCredential(ciphertextKey, ivKey)
            null
        }
    }

    private fun clearCredential(ciphertextKey: String, ivKey: String) {
        preferences.edit {
            remove(ciphertextKey)
            remove(ivKey)
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lucid_dream_deepseek_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES_NAME = "secure_api_credentials"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_IV = "api_key_iv"
        const val KEY_AGENT_TOKEN_CIPHERTEXT = "agent_token_ciphertext"
        const val KEY_AGENT_TOKEN_IV = "agent_token_iv"
    }
}
