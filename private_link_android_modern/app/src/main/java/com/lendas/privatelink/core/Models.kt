package com.lendas.privatelink.core

import android.bluetooth.BluetoothDevice

data class NearbyDevice(
    val device: BluetoothDevice,
    val address: String,
    val rssi: Int
)

data class Telemetry(
    val firmware: String = "-",
    val nodeId: String? = null,
    val model: String? = null,
    val role: String? = null,
    val flashBytes: Long? = null,
    val uptimeMs: Long? = null,
    val heap: Long? = null,
    val batteryVolts: String = "na",
    val otaActive: Boolean = false,
    val fastOtaActive: Boolean = false,
    val wifiAp: Int? = null,
    val wifiOpen: Int? = null,
    val wifiSecure: Int? = null,
    val wifiBest: Int? = null,
    val wifiPeakChannel: Int? = null,
    val wifiPeakCount: Int? = null,
    val bleSeen: Int? = null,
    val bleBest: Int? = null,
    val surveyAgeMs: Long? = null
)

data class NodeInfo(
    val nodeId: String? = null,
    val model: String? = null,
    val board: String? = null,
    val role: String? = null,
    val firmware: String = "-",
    val flashBytes: Long? = null,
    val capabilities: Set<String> = emptySet()
)

enum class LinkState {
    Idle,
    Scanning,
    Pairing,
    Connecting,
    Authenticating,
    Ready,
    PreparingFastOta,
    ConnectingFastOta,
    Updating,
    Verifying,
    Error
}

enum class OtaTransport {
    None,
    Ble,
    WifiFast
}

data class LabStatus(
    val state: String = "IDLE",
    val level: Int = 0,
    val target: String? = null,
    val port: Int? = null,
    val profilePps: Int? = null,
    val packetBytes: Int? = null,
    val txPackets: Long = 0L,
    val txBytes: Long = 0L,
    val actualPps: Int? = null,
    val elapsedMs: Long = 0L,
    val rssi: Int? = null,
    val reason: String? = null
) {
    val active: Boolean
        get() =
            state == "CONNECTING" ||
            state == "RUNNING"
}

data class NodeState(
    val address: String,
    val rssi: Int? = null,
    val linkState: LinkState = LinkState.Idle,
    val statusText: String = "Disponível",
    val authenticated: Boolean = false,
    val info: NodeInfo = NodeInfo(),
    val telemetry: Telemetry = Telemetry(),
    val otaProgress: Float = 0f,
    val otaFileName: String? = null,
    val otaTransport: OtaTransport = OtaTransport.None,
    val otaSpeedBytesPerSecond: Long? = null,
    val otaTransferredBytes: Long = 0L,
    val otaTotalBytes: Long = 0L,
    val lab: LabStatus = LabStatus(),
    val lastError: String? = null
) {
    val displayName: String
        get() = when {
            info.model?.contains("C6", ignoreCase = true) == true ->
                "nanoESP32-C6"
            info.model?.contains("S3", ignoreCase = true) == true ->
                "ESP32-S3"
            info.model?.contains("DEVKIT", ignoreCase = true) == true ||
                info.model?.equals("ESP32", ignoreCase = true) == true ->
                "ESP32 DevKit V1"
            info.model != null ->
                info.model
            else ->
                "ESPhub Node"
        }

    val nodeKey: String
        get() = info.nodeId ?: address
}

data class PrivateLinkUiState(
    val scanning: Boolean = false,
    val statusText: String = "Aguardando nós ESPhub",
    val devices: List<NearbyDevice> = emptyList(),
    val nodes: List<NodeState> = emptyList(),
    val selectedNodeKey: String? = null,
    val logs: List<String> = listOf("Pronto."),
    val hasPrivateKey: Boolean = false,
    val lastError: String? = null
) {
    val selectedNode: NodeState?
        get() = nodes.firstOrNull {
            it.nodeKey == selectedNodeKey ||
                it.address == selectedNodeKey
        }

    val authenticatedCount: Int
        get() = nodes.count { it.authenticated }
}
