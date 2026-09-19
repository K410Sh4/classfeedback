package com.esphub.app.protocol

import com.esphub.app.model.NodeId

/**
 * Fuses two CSI link-change indicators into a cautious environmental reading.
 * This class never computes a person's position or a probability of occupancy.
 */
class PresenceFusion(private val staleAfterMs: Long = 4_000L) {
    data class Reading(val telemetry: Telemetry, val atMs: Long)
    data class View(
        val headline: String,
        val detail: String,
        val c6: String,
        val devkit: String,
        val status: Status,
    )
    enum class Status { Waiting, Calibrating, Quiet, Change, Fault }

    private val readings = mutableMapOf<NodeId, Reading>()

    fun update(t: Telemetry, atMs: Long) { readings[t.node] = Reading(t, atMs) }

    fun snapshot(nowMs: Long): View {
        fun fresh(node: NodeId): Telemetry? = readings[node]?.takeIf {
            val age = nowMs - it.atMs
            age >= 0 && age <= staleAfterMs
        }?.telemetry
        val s3 = fresh(NodeId.S3)
        val c6 = fresh(NodeId.C6)
        val dev = fresh(NodeId.DEVKIT)
        val c6Text = describe(c6)
        val devText = describe(dev)
        if (s3?.radarState != 4) {
            return View("Aguardando emissor S3", "Sem referência Wi-Fi ativa.", c6Text, devText, Status.Waiting)
        }
        val sensors = listOfNotNull(c6, dev)
        if (sensors.isEmpty()) {
            return View("Aguardando receptores", "Conecte C6 e DevKit por BLE e à rede do S3.", c6Text, devText, Status.Waiting)
        }
        if (sensors.any { it.radarState == 5 }) {
            return View("Falha de medição", "Verifique o CSI e a conexão Wi-Fi.", c6Text, devText, Status.Fault)
        }
        if (sensors.any { it.radarState == 0 || it.radarState == 1 }) {
            return View("Calibrando / sinal insuficiente", "Deixe o ambiente vazio e estável; as leituras não são conclusivas.", c6Text, devText, Status.Calibrating)
        }
        val moving = sensors.filter { it.radarState == 3 }
        if (moving.isNotEmpty()) {
            val region = when {
                moving.size == 2 -> "ambos os enlaces"
                moving.first().node == NodeId.C6 -> "enlace S3–C6"
                else -> "enlace S3–DevKit"
            }
            return View("Alteração ambiental detectada", "Sinal alterado em ${region}. Não comprova presença humana ou posição exata.",
                c6Text, devText, Status.Change)
        }
        return View("Sem alteração relevante", "Os enlaces medidos estão próximos da referência calibrada; presença imóvel não está descartada.",
            c6Text, devText, Status.Quiet)
    }

    private fun describe(t: Telemetry?): String {
        if (t == null) return "Sem dados recentes"
        val state = when (t.radarState) {
            0 -> "Wi-Fi não configurado"; 1 -> "Calibrando / sem amostras"; 2 -> "Estável"
            3 -> "Alteração"; 4 -> "Emissor"; 5 -> "Erro"; else -> "Desconhecido"
        }
        return "${state} | índice ${t.radarScore}/100 | ${t.radarSamples} amostras/s | RSSI ${t.radarRssi} dBm"
    }
}
