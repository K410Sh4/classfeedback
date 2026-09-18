package com.lendas.privatelink.core

import android.bluetooth.BluetoothDevice

data class NearbyDevice(
    val device: BluetoothDevice,
    val address: String,
    val rssi: Int
)

data class Telemetry(
    val firmware: String = "-",
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

data class PrivateLinkUiState(
    val linkState: LinkState = LinkState.Idle,
    val statusText: String = "Aguardando ESP32-S3",
    val devices: List<NearbyDevice> = emptyList(),
    val connectedAddress: String? = null,
    val connectedRssi: Int? = null,
    val authenticated: Boolean = false,
    val telemetry: Telemetry = Telemetry(),
    val logs: List<String> = listOf("Pronto."),
    val otaProgress: Float = 0f,
    val otaFileName: String? = null,
    val otaTransport: OtaTransport = OtaTransport.None,
    val otaSpeedBytesPerSecond: Long? = null,
    val otaTransferredBytes: Long = 0L,
    val otaTotalBytes: Long = 0L,
    val hasPrivateKey: Boolean = false,
    val lastError: String? = null
)
