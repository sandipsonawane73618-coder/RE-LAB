package com.relab.app
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM credentials encrypted by a non-exportable Android Keystore key. */
class AiKeyVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("relab_ai_vault", Context.MODE_PRIVATE)
    fun save(provider: String, apiKey: String) {
        require(provider in PROVIDERS)
        val clean = apiKey.trim()
        require(clean.isNotEmpty())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        val packed = ByteBuffer.allocate(cipher.iv.size + encrypted.size).put(cipher.iv).put(encrypted).array()
        check(prefs.edit().putString(provider, Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
    }
    fun read(provider: String): String? {
        require(provider in PROVIDERS)
        val packed = prefs.getString(provider, null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return null
        require(packed.size > IV_LENGTH) { "Credential data is damaged; save the key again" }
        val buffer = ByteBuffer.wrap(packed)
        val iv = ByteArray(IV_LENGTH).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }
    fun hasKey(provider: String) = prefs.contains(provider)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    companion object {
        const val GEMINI = "gemini"
        const val GROQ = "groq"
        private val PROVIDERS = setOf(GEMINI, GROQ)
        private const val ALIAS = "relab.ai.credentials.aes.v1"
        private const val IV_LENGTH = 12
    }
}
