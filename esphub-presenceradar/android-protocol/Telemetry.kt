package com.esphub.app.protocol

import com.esphub.app.model.NodeId
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Telemetry(
    val sequence: Long,
    val node: NodeId,
    val flags: Int,
    val uptimeSeconds: Long,
    val freeHeapBytes: Long,
    val minFreeHeapBytes: Long,
    val freePsramBytes: Long,
    val radarState: Int,
    val radarScore: Int,
    val radarRssi: Int,
    val radarRole: Int,
    val radarSamples: Int,
    val radarBaselineWindows: Int
) {
    companion object {
        fun parse(sequence: Long, data: ByteArray): Telemetry? {
            if (data.size != Protocol.TELEMETRY_PLAIN_SIZE) return null
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val version = b.get().toInt() and 0xff
            if (version != 2) return null
            val node = NodeId.fromWireCode(b.get()) ?: return null
            val flags = b.short.toInt() and 0xffff
            return Telemetry(
                sequence = sequence,
                node = node,
                flags = flags,
                uptimeSeconds = b.int.toUInt().toLong(),
                freeHeapBytes = b.int.toUInt().toLong(),
                minFreeHeapBytes = b.int.toUInt().toLong(),
                freePsramBytes = b.int.toUInt().toLong(),
                radarState = b.get().toInt() and 255,
                radarScore = b.get().toInt() and 255,
                radarRssi = b.get().toInt(),
                radarRole = b.get().toInt() and 255,
                radarSamples = b.short.toInt() and 65535,
                radarBaselineWindows = b.short.toInt() and 65535
            )
        }
    }
}
