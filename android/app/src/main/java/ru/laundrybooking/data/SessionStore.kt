package ru.laundrybooking.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class SessionStore(context: Context, private val apiUrl: String) {
    private val preferences = context.getSharedPreferences("laundry_session", Context.MODE_PRIVATE)

    suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (preferences.getString("api_url", null) != apiUrl) return@withContext null
        val encrypted = preferences.getString("token", null) ?: return@withContext null
        runCatching {
            val (iv, ciphertext) = encrypted.split('.', limit = 2)
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, decode(iv)))
            cipher.doFinal(decode(ciphertext)).decodeToString()
        }.getOrElse {
            // После сброса ключа или повреждения записи потребуется повторный вход.
            preferences.edit().clear().apply()
            null
        }
    }

    suspend fun save(token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cipher = Cipher.getInstance(CIPHER)
            cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: createKey())
            val encrypted = encode(cipher.iv) + "." + encode(cipher.doFinal(token.encodeToByteArray()))
            preferences.edit().putString("api_url", apiUrl).putString("token", encrypted).commit()
        }.getOrDefault(false)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        preferences.edit().clear().apply()
    }

    private fun existingKey(): SecretKey? =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KEY_ALIAS, null) as? SecretKey

    private fun createKey(): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String) = Base64.decode(value, Base64.NO_WRAP)

    private companion object {
        const val CIPHER = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "laundry-session"
    }
}
