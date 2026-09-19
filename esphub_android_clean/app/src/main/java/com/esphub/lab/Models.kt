package com.esphub.lab

import android.bluetooth.BluetoothDevice

enum class SessionPhase {
    Idle,
    Connecting,
    Bonding,
    Mtu,
    Discovering,
    Subscribing,
    Authenticating,
    Ready,
    Error
}

data class DiscoveredNode(
    val device: BluetoothDevice,
    val address: String,
    val rssi: Int
)

data class LabMetrics(
    val state: String = "IDLE",
    val level: Int = 0,
    val target: String? = null,
    val port: Int? = null,
    val profilePps: Int? = null,
    val packetBytes: Int? = null,
    val txPackets: Long = 0,
    val txBytes: Long = 0,
    val actualPps: Int? = null,
    val elapsedMs: Long = 0,
    val rssi: Int? = null,
    val reason: String? = null
) {
    val active: Boolean
        get() =
            state.equals("CONNECTING", true) ||
                state.equals("RUNNING", true) ||
                state.equals("STARTING", true)
}

data class NodeState(
    val address: String,
    val rssi: Int? = null,
    val phase: SessionPhase = SessionPhase.Idle,
    val status: String = "Desconectado",
    val authenticated: Boolean = false,
    val nodeId: String? = null,
    val firmware: String? = null,
    val model: String? = null,
    val board: String? = null,
    val role: String? = null,
    val capabilities: Set<String> = emptySet(),
    val lab: LabMetrics = LabMetrics(),
    val lastError: String? = null
) {
    val displayName: String
        get() =
            when {
                model?.contains("C6", ignoreCase = true) == true ->
                    "nanoESP32-C6"
                model?.contains("S3", ignoreCase = true) == true ->
                    "ESP32-S3"
                model?.contains("DevKit", ignoreCase = true) == true ||
                    model?.equals("ESP32", ignoreCase = true) == true ->
                    "ESP32 DevKit V1"
                else ->
                    model ?: address
            }

    val labDefaultPort: Int
        get() =
            when {
                model?.contains("C6", ignoreCase = true) == true -> 41002
                model?.contains("S3", ignoreCase = true) == true -> 41003
                else -> 41001
            }
}

data class AppState(
    val hasMasterKey: Boolean = false,
    val scanning: Boolean = false,
    val discovered: List<DiscoveredNode> = emptyList(),
    val nodes: Map<String, NodeState> = emptyMap(),
    val selectedAddress: String? = null,
    val logs: List<String> = listOf("ESPhub pronto."),
    val lastCrash: String? = null
) {
    val selectedNode: NodeState?
        get() =
            selectedAddress?.let {
                nodes[it.uppercase()]
            }
}
