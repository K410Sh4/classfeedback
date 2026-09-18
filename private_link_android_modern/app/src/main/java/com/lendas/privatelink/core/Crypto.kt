package com.lendas.privatelink.core

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    fun hexToBytes(input: String): ByteArray {
        val text = input.trim()
        require(text.length % 2 == 0) { "Hex inválido" }

        return ByteArray(text.length / 2) { index ->
            text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    fun deriveNodeKey(
        masterKey: ByteArray,
        nodeId: String
    ): ByteArray {
        require(masterKey.size == 32) {
            "Master key inválida"
        }

        val context =
            "LENDAS_PRIVATE_LINK_NODE_V1|" +
                nodeId.trim().uppercase()

        return hmacSha256(
            masterKey,
            context.toByteArray(Charsets.UTF_8)
        )
    }
}
