package com.esphub.lab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoStore(
    private val context: Context
) {
    private val prefs =
        context.getSharedPreferences(
            "esphub_secure_store_v1",
            Context.MODE_PRIVATE
        )

    fun hasMasterKey(): Boolean =
        prefs.contains(KEY_CIPHERTEXT) &&
            prefs.contains(KEY_IV)

    fun saveMasterKeyHex(hex: String) {
        val normalized =
            hex.trim()
                .lowercase()

        require(
            normalized.matches(
                Regex("^[0-9a-f]{64}$")
            )
        ) {
            "A chave deve conter exatamente 64 caracteres hexadecimais."
        }

        saveMasterKey(
            hexToBytes(normalized)
        )
    }

    fun saveMasterKey(
        bytes: ByteArray
    ) {
        require(bytes.size == 32) {
            "A chave mestre precisa ter 32 bytes."
        }

        val cipher =
            Cipher.getInstance(
                "AES/GCM/NoPadding"
            )

        cipher.init(
            Cipher.ENCRYPT_MODE,
            wrappingKey()
        )

        val encrypted =
            cipher.doFinal(bytes)

        prefs.edit()
            .putString(
                KEY_CIPHERTEXT,
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )
            )
            .putString(
                KEY_IV,
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                )
            )
            .commit()
    }

    fun loadMasterKey(): ByteArray? {
        val ciphertext =
            prefs.getString(
                KEY_CIPHERTEXT,
                null
            ) ?: return null

        val iv =
            prefs.getString(
                KEY_IV,
                null
            ) ?: return null

        return runCatching {
            val cipher =
                Cipher.getInstance(
                    "AES/GCM/NoPadding"
                )

            cipher.init(
                Cipher.DECRYPT_MODE,
                wrappingKey(),
                GCMParameterSpec(
                    128,
                    Base64.decode(
                        iv,
                        Base64.NO_WRAP
                    )
                )
            )

            cipher.doFinal(
                Base64.decode(
                    ciphertext,
                    Base64.NO_WRAP
                )
            )
        }.getOrNull()
    }

    fun clear() {
        prefs.edit()
            .clear()
            .commit()
    }

    fun generateMasterKeyHex(): String {
        val bytes =
            ByteArray(32)

        SecureRandom().nextBytes(bytes)
        return bytesToHex(bytes)
    }

    private fun wrappingKey(): SecretKey {
        val store =
            KeyStore.getInstance(
                "AndroidKeyStore"
            ).apply {
                load(null)
            }

        (
            store.getKey(
                KEY_ALIAS,
                null
            ) as? SecretKey
        )?.let {
            return it
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(
                    KeyProperties.BLOCK_MODE_GCM
                )
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setKeySize(256)
                .build()
        )

        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS =
            "esphub_master_wrap_v1"

        private const val KEY_CIPHERTEXT =
            "ciphertext"

        private const val KEY_IV =
            "iv"

        fun deriveNodeKey(
            master: ByteArray,
            nodeId: String
        ): ByteArray =
            hmacSha256(
                master,
                (
                    Protocol.NODE_DERIVATION_PREFIX +
                        nodeId.uppercase()
                    ).toByteArray(
                        Charsets.UTF_8
                    )
            )

        fun hmacSha256(
            key: ByteArray,
            data: ByteArray
        ): ByteArray {
            val mac =
                Mac.getInstance(
                    "HmacSHA256"
                )

            mac.init(
                SecretKeySpec(
                    key,
                    "HmacSHA256"
                )
            )

            return mac.doFinal(data)
        }

        fun bytesToHex(
            bytes: ByteArray
        ): String =
            bytes.joinToString("") {
                "%02x".format(it)
            }

        fun hexToBytes(
            text: String
        ): ByteArray {
            val normalized =
                text.trim()

            require(
                normalized.length % 2 == 0 &&
                    normalized.matches(
                        Regex("^[0-9a-fA-F]+$")
                    )
            ) {
                "Hexadecimal inválido."
            }

            return ByteArray(
                normalized.length / 2
            ) { index ->
                normalized.substring(
                    index * 2,
                    index * 2 + 2
                )
                    .toInt(16)
                    .toByte()
            }
        }
    }
}
