package com.lendas.privatelink.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureKeyStore(private val context: Context) {
    companion object {
        private const val STORE_ALIAS = "lendas_private_link_store_v2"
        private const val PREFS = "lendas_private_link_secure"
        private const val KEY_BLOB = "hmac_blob"
    }

    fun hasKey(): Boolean = loadKey() != null

    fun saveKey(hex: String) {
        require(hex.matches(Regex("(?i)[0-9a-f]{64}"))) {
            "A chave precisa ter 64 caracteres hexadecimais"
        }

        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateStorageKey())

        val iv = cipher.iv
        val encrypted = cipher.doFinal(bytes)

        val packed = ByteArray(1 + iv.size + encrypted.size)
        packed[0] = iv.size.toByte()
        System.arraycopy(iv, 0, packed, 1, iv.size)
        System.arraycopy(encrypted, 0, packed, 1 + iv.size, encrypted.size)

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BLOB, Base64.encodeToString(packed, Base64.NO_WRAP))
            .apply()
    }

    fun loadKey(): ByteArray? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_BLOB, null)
            ?: return null

        return try {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            if (packed.isEmpty()) return null

            val ivSize = packed[0].toInt() and 0xff
            if (ivSize <= 0 || packed.size <= 1 + ivSize) return null

            val iv = packed.copyOfRange(1, 1 + ivSize)
            val encrypted = packed.copyOfRange(1 + ivSize, packed.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateStorageKey(),
                GCMParameterSpec(128, iv)
            )

            cipher.doFinal(encrypted).takeIf { it.size == 32 }
        } catch (_: Exception) {
            null
        }
    }

    fun clearKey() {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BLOB)
            .apply()
    }

    private fun getOrCreateStorageKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = keyStore.getEntry(STORE_ALIAS, null)
        if (existing is KeyStore.SecretKeyEntry) {
            return existing.secretKey
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                STORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )

        return generator.generateKey()
    }
}
