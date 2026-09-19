package com.esphub.app.protocol

import com.esphub.app.model.NodeId
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object Protocol {
    const val MSG_HELLO: Byte = 0x01
    const val MSG_CHALLENGE: Byte = 0x02
    const val MSG_RESPONSE: Byte = 0x03
    const val MSG_AUTH_OK: Byte = 0x04
    const val MSG_AUTH_FAIL: Byte = 0x05
    const val MSG_TELEMETRY: Byte = 0x10

    const val NONCE_SIZE = 16
    const val HMAC_SIZE = 32
    const val SECRET_SIZE = 32
    const val TELEMETRY_PLAIN_SIZE = 28
    const val GCM_TAG_SIZE_BITS = 128

    private val random = SecureRandom()

    fun newNonce(): ByteArray = ByteArray(NONCE_SIZE).also(random::nextBytes)

    fun hello(appNonce: ByteArray): ByteArray {
        require(appNonce.size == NONCE_SIZE)
        return byteArrayOf(MSG_HELLO) + appNonce
    }

    fun parseChallenge(frame: ByteArray): Challenge? {
        if (frame.size != 1 + NONCE_SIZE + HMAC_SIZE || frame[0] != MSG_CHALLENGE) return null
        return Challenge(
            espNonce = frame.copyOfRange(1, 1 + NONCE_SIZE),
            espMac = frame.copyOfRange(1 + NONCE_SIZE, frame.size)
        )
    }

    fun expectedEspMac(secret: ByteArray, node: NodeId, appNonce: ByteArray, espNonce: ByteArray): ByteArray =
        hmac(secret, authMaterial("ESP-AUTH", node, appNonce, espNonce))

    fun response(secret: ByteArray, node: NodeId, appNonce: ByteArray, espNonce: ByteArray): ByteArray =
        byteArrayOf(MSG_RESPONSE) + hmac(secret, authMaterial("APP-AUTH", node, appNonce, espNonce))

    fun deriveSessionKey(secret: ByteArray, node: NodeId, appNonce: ByteArray, espNonce: ByteArray): ByteArray =
        hmac(secret, authMaterial("SESSION", node, appNonce, espNonce))

    fun decryptTelemetry(frame: ByteArray, sessionKey: ByteArray): Telemetry? {
        if (frame.size != 1 + 4 + TELEMETRY_PLAIN_SIZE + 16 || frame[0] != MSG_TELEMETRY) return null
        val seqBytes = frame.copyOfRange(1, 5)
        val seq = ByteBuffer.wrap(seqBytes).order(ByteOrder.LITTLE_ENDIAN).int.toUInt().toLong()
        val cipherTextAndTag = frame.copyOfRange(5, frame.size)
        val iv = telemetryIv(sessionKey, seqBytes)
        val aad = byteArrayOf(MSG_TELEMETRY) + seqBytes

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(sessionKey, "AES"),
                GCMParameterSpec(GCM_TAG_SIZE_BITS, iv)
            )
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(cipherTextAndTag)
            Telemetry.parse(seq, plain)
        } catch (_: Exception) {
            null
        }
    }

    private fun telemetryIv(sessionKey: ByteArray, seqBytes: ByteArray): ByteArray {
        val prefix = hmac(sessionKey, "IV".toByteArray(Charsets.US_ASCII)).copyOfRange(0, 8)
        return prefix + seqBytes
    }

    private fun authMaterial(
        label: String,
        node: NodeId,
        appNonce: ByteArray,
        espNonce: ByteArray
    ): ByteArray = label.toByteArray(Charsets.US_ASCII) + byteArrayOf(node.wireCode) + appNonce + espNonce

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    data class Challenge(val espNonce: ByteArray, val espMac: ByteArray)
}
