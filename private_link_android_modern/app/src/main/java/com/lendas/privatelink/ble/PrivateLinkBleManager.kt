package com.lendas.privatelink.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.lendas.privatelink.core.Crypto
import com.lendas.privatelink.core.FastOtaCredentials
import com.lendas.privatelink.core.LabStatus
import com.lendas.privatelink.core.LinkState
import com.lendas.privatelink.core.NearbyDevice
import com.lendas.privatelink.core.NodeInfo
import com.lendas.privatelink.core.Protocol
import com.lendas.privatelink.core.Telemetry
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ESPhub BLE facade.
 *
 * Responsibilities:
 *  - scan PrivateLink/ESPhub service advertisements;
 *  - delegate all GATT/bond/MTU/CCCD/write sequencing to BleSessionController;
 *  - implement HMAC authentication, provisioning, telemetry and OTA protocol.
 *
 * Android BLE calls are never issued directly from this class.
 */
class PrivateLinkBleManager(
    private val context: Context,
    private val listener: Listener,
    private val enableBondReceiver: Boolean = true
) {
    interface Listener {
        fun onLinkState(state: LinkState, text: String)
        fun onDevices(devices: List<NearbyDevice>)
        fun onLog(message: String)
        fun onAuthenticated(authenticated: Boolean)
        fun onTelemetry(telemetry: Telemetry)
        fun onNodeInfo(info: NodeInfo)
        fun onOtaProgress(progress: Float, fileName: String?)
        fun onConnectedAddress(address: String?)
        fun onFastOtaReady(credentials: FastOtaCredentials)
        fun onFastOtaUnavailable(reason: String)
        fun onLabStatus(status: LabStatus)
    }

    private val main = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val scanResults = linkedMapOf<String, ScanResult>()

    private val session: BleSessionController? =
        if (enableBondReceiver) {
            BleSessionController(
                context,
                SessionListener()
            )
        } else {
            null
        }

    private var closed = false
    private var authenticated = false
    private var privateKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var nodeId: String? = null
    private var legacySession = false
    private var migrationRequested = false
    private var authGeneration = 0

    private var otaActive = false
    private var otaFirmware: ByteArray? = null
    private var otaFileName: String? = null
    private var otaOffset = 0
    private var waitingOtaReady = false

    private var awaitingFastOta = false
    private var fastOtaRequestGeneration = 0

    fun close() = onMain {
        if (closed) return@onMain
        closed = true
        stopScanInternal()
        resetProtocolState()
        session?.close()
    }

    fun setPrivateKey(key: ByteArray?) = onMain {
        privateKey = key?.copyOf()
        sessionKey = null
        nodeId = null
        legacySession = false
        migrationRequested = false
    }

    fun currentSessionKey(): ByteArray? =
        (sessionKey ?: privateKey)?.copyOf()

    fun currentNodeId(): String? = nodeId

    fun isBluetoothEnabled(): Boolean =
        adapter?.isEnabled == true

    fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun startScan() = onMain {
        if (closed) return@onMain

        if (!hasBlePermissions()) {
            listener.onLinkState(
                LinkState.Error,
                "Permissões Bluetooth necessárias"
            )
            return@onMain
        }

        if (!isBluetoothEnabled()) {
            listener.onLinkState(
                LinkState.Error,
                "Ative o Bluetooth"
            )
            return@onMain
        }

        stopScanInternal()

        scanner = runCatching {
            adapter?.bluetoothLeScanner
        }.getOrNull()

        if (scanner == null) {
            listener.onLinkState(
                LinkState.Error,
                "Scanner BLE indisponível"
            )
            return@onMain
        }

        scanResults.clear()
        listener.onDevices(emptyList())
        scanning = true
        listener.onLinkState(
            LinkState.Scanning,
            "Procurando nós ESPhub..."
        )
        log("Scan BLE iniciado com filtro do serviço ESPhub.")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching {
            scanner?.startScan(
                listOf(filter),
                settings,
                scanCallback
            )
        }.onFailure { error ->
            scanning = false
            scanner = null
            listener.onLinkState(
                LinkState.Error,
                "Falha ao iniciar scan BLE"
            )
            log(
                "startScan: " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
        }

        main.postDelayed({
            if (scanning) {
                stopScanInternal()
                listener.onLinkState(
                    LinkState.Idle,
                    "Busca concluída"
                )
            }
        }, 12_000)
    }

    fun stopScan() = onMain {
        stopScanInternal()
    }

    fun connect(device: NearbyDevice) = onMain {
        if (closed) return@onMain

        val activeSession = session
        if (activeSession == null) {
            listener.onLinkState(
                LinkState.Error,
                "Gerenciador de scan não pode abrir sessão"
            )
            return@onMain
        }

        if (!hasBlePermissions()) {
            listener.onLinkState(
                LinkState.Error,
                "Permissões Bluetooth necessárias"
            )
            return@onMain
        }

        stopScanInternal()
        resetProtocolState()
        listener.onConnectedAddress(device.address)
        listener.onLinkState(
            LinkState.Connecting,
            "Abrindo sessão BLE..."
        )
        activeSession.connect(device.device)
    }

    fun disconnect() = onMain {
        resetProtocolState()
        session?.disconnect()
        listener.onAuthenticated(false)
        listener.onConnectedAddress(null)
        listener.onLinkState(
            LinkState.Idle,
            "Nó ESPhub desconectado"
        )
    }

    fun sendCommand(command: String) = onMain {
        if (!authenticated) {
            log("Comando ignorado: canal ainda não autenticado.")
            return@onMain
        }
        writeControl(command)
    }

    fun requestFastOta() = onMain {
        if (!authenticated) {
            listener.onFastOtaUnavailable(
                "Canal BLE não autenticado."
            )
            return@onMain
        }

        awaitingFastOta = true
        val generation = ++fastOtaRequestGeneration

        listener.onLinkState(
            LinkState.PreparingFastOta,
            "Preparando canal Wi-Fi privado..."
        )

        log("Solicitando sessão FAST_OTA pelo BLE autenticado.")
        writeControl("FAST_OTA_BEGIN")

        main.postDelayed({
            if (
                generation == fastOtaRequestGeneration &&
                awaitingFastOta &&
                authenticated
            ) {
                awaitingFastOta = false
                listener.onFastOtaUnavailable(
                    "Firmware não respondeu ao Fast OTA; usando BLE compatível."
                )
            }
        }, 6_000)
    }

    fun cancelFastOtaSession() = onMain {
        awaitingFastOta = false
        fastOtaRequestGeneration++

        if (authenticated) {
            writeControl("FAST_OTA_CANCEL")
        }
    }

    fun startOta(
        firmware: ByteArray,
        fileName: String
    ) {
        val key = currentSessionKey()

        if (
            !authenticated ||
            key == null ||
            otaActive ||
            firmware.isEmpty()
        ) {
            log("OTA indisponível no estado atual.")
            return
        }

        val firmwareCopy = firmware.copyOf()
        val safeName =
            fileName.replace(
                Regex("[^A-Za-z0-9._-]"),
                "_"
            )

        listener.onLinkState(
            LinkState.Updating,
            "Preparando atualização OTA..."
        )
        listener.onOtaProgress(0f, safeName)

        Thread {
            runCatching {
                val sha =
                    Crypto.bytesToHex(
                        Crypto.sha256(firmwareCopy)
                    )

                val hmac =
                    Crypto.bytesToHex(
                        Crypto.hmacSha256(
                            key,
                            firmwareCopy
                        )
                    )

                Triple(sha, hmac, firmwareCopy)
            }.onSuccess { prepared ->
                onMain {
                    if (!authenticated || closed) return@onMain

                    otaFirmware = prepared.third
                    otaFileName = safeName
                    otaOffset = 0
                    waitingOtaReady = true
                    otaActive = true

                    val begin =
                        "OTA_BEGIN|" +
                            prepared.third.size + "|" +
                            prepared.first + "|" +
                            prepared.second + "|" +
                            safeName

                    writeControl(begin)
                    log(
                        "OTA solicitada: " +
                            prepared.third.size +
                            " bytes."
                    )
                }
            }.onFailure { error ->
                onMain {
                    failOta(
                        "Falha ao preparar imagem: " +
                            (error.message ?: "erro desconhecido")
                    )
                }
            }
        }.start()
    }

    fun abortOta() = onMain {
        if (!otaActive) return@onMain
        writeControl("OTA_ABORT")
        failOta("Cancelada pelo usuário")
    }

    private fun beginAuthentication() {
        val key = privateKey

        if (key == null) {
            listener.onLinkState(
                LinkState.Error,
                "Chave privada não configurada"
            )
            return
        }

        authenticated = false
        listener.onAuthenticated(false)
        listener.onLinkState(
            LinkState.Authenticating,
            "Autenticando aplicativo..."
        )

        val generation = ++authGeneration
        log("Iniciando HELLO seguro.")
        writeControl("HELLO")

        main.postDelayed({
            if (
                generation == authGeneration &&
                !authenticated &&
                !closed
            ) {
                log("Timeout de autenticação; repetindo HELLO uma vez.")
                writeControl("HELLO")
            }
        }, 12_000)
    }

    private fun handleNotification(value: ByteArray) {
        val message =
            value.toString(StandardCharsets.UTF_8)

        log("NODE → " + message)

        runCatching {
            when {
                message.startsWith("PROVISION_REQUIRED|") ->
                    handleProvisionRequired(message)

                message.startsWith("PROVISION_OK|") -> {
                    log("Provisionamento concluído; aguardando desafio HMAC.")
                    listener.onLinkState(
                        LinkState.Authenticating,
                        "Chave provisionada • autenticando..."
                    )
                }

                message.startsWith("PROVISION_LOCKED|") -> {
                    listener.onLinkState(
                        LinkState.Error,
                        "Janela de provisionamento encerrada • reinicie o ESP32"
                    )
                }

                message.startsWith("PROVISION_ERROR|") -> {
                    listener.onLinkState(
                        LinkState.Error,
                        "ESP32 recusou o provisionamento"
                    )
                    log(message)
                }

                message == "ERR|link_not_encrypted" -> {
                    listener.onLinkState(
                        LinkState.Error,
                        "Link BLE não criptografado • refaça o pareamento"
                    )
                }

                message.startsWith("HELLO2|") ->
                    handleHello2(message)

                message.startsWith("HELLO|") ->
                    handleLegacyHello(message)

                message.startsWith("AUTH_OK|") ->
                    handleAuthOk(message)

                message.startsWith("AUTH_FAIL|") -> {
                    authenticated = false
                    listener.onAuthenticated(false)
                    listener.onLinkState(
                        LinkState.Error,
                        "Chave privada rejeitada"
                    )
                }

                message.startsWith("TEL|") -> {
                    listener.onTelemetry(
                        parseTelemetry(message)
                    )
                }

                message.startsWith("INFO|") -> {
                    val info = parseNodeInfo(message)
                    listener.onNodeInfo(info)
                    maybeMigrateLegacyKey(info)
                }

                message.startsWith("KEY_MIGRATE_OK|") ->
                    handleKeyMigrationOk(message)

                message.startsWith("KEY_MIGRATE_ERROR|") -> {
                    migrationRequested = false
                    log(message)
                }

                message.startsWith("FAST_OTA_READY|") &&
                    awaitingFastOta ->
                    handleFastOtaReady(message)

                message.startsWith("FAST_OTA_ERROR|") &&
                    awaitingFastOta -> {
                    awaitingFastOta = false
                    fastOtaRequestGeneration++
                    listener.onFastOtaUnavailable(
                        message.substringAfter(
                            "FAST_OTA_ERROR|"
                        )
                    )
                }

                message == "ERR|unknown_command" &&
                    awaitingFastOta -> {
                    awaitingFastOta = false
                    fastOtaRequestGeneration++
                    listener.onFastOtaUnavailable(
                        "Fast OTA não existe neste firmware; usando BLE compatível."
                    )
                }

                message.startsWith("OTA_READY|") &&
                    waitingOtaReady -> {
                    waitingOtaReady = false
                    otaOffset = 0
                    listener.onLinkState(
                        LinkState.Updating,
                        "Enviando firmware..."
                    )
                    sendNextOtaChunk()
                }

                message.startsWith("OTA_PROGRESS|") ->
                    handleOtaProgress(message)

                message.startsWith("OTA_OK|") -> {
                    otaActive = false
                    waitingOtaReady = false
                    listener.onOtaProgress(
                        1f,
                        otaFileName
                    )
                    listener.onLinkState(
                        LinkState.Ready,
                        "Firmware validado • nó reiniciando"
                    )
                    log("OTA concluída com sucesso.")
                }

                message.startsWith("OTA_ERROR|") -> {
                    failOta(
                        message.substringAfter(
                            "OTA_ERROR|"
                        )
                    )
                }

                message.startsWith("LAB_") -> {
                    handleLabMessage(message)
                }
            }
        }.onFailure { error ->
            log(
                "Mensagem do protocolo rejeitada: " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
            listener.onLinkState(
                LinkState.Error,
                "Resposta inválida recebida do nó"
            )
        }
    }

    private fun handleLabMessage(
        message: String
    ) {
        if (
            message == "LAB_WIFI_OK"
        ) {
            log("LabTest: credenciais Wi-Fi aceitas pelo nó.")
            return
        }

        val map = parseFields(message)

        val state =
            when {
                message.startsWith("LAB_STARTED|") ->
                    "RUNNING"
                message.startsWith("LAB_TEL|") ->
                    map["state"] ?: "RUNNING"
                message.startsWith("LAB_CONNECTING|") ->
                    "CONNECTING"
                message.startsWith("LAB_STOPPED|") ->
                    "STOPPED"
                message.startsWith("LAB_ERROR|") ->
                    "ERROR"
                message.startsWith("LAB_STATUS|") ->
                    map["state"] ?: "IDLE"
                else ->
                    map["state"] ?: "IDLE"
            }

        listener.onLabStatus(
            LabStatus(
                state = state,
                level =
                    map["level"]
                        ?.toIntOrNull()
                        ?: 0,
                target =
                    map["target"],
                port =
                    map["port"]
                        ?.toIntOrNull(),
                profilePps =
                    map["profile_pps"]
                        ?.toIntOrNull(),
                packetBytes =
                    map["packet_bytes"]
                        ?.toIntOrNull(),
                txPackets =
                    map["tx_packets"]
                        ?.toLongOrNull()
                        ?: 0L,
                txBytes =
                    map["tx_bytes"]
                        ?.toLongOrNull()
                        ?: 0L,
                actualPps =
                    map["actual_pps"]
                        ?.toIntOrNull(),
                elapsedMs =
                    map["elapsed_ms"]
                        ?.toLongOrNull()
                        ?: 0L,
                rssi =
                    map["rssi"]
                        ?.toIntOrNull(),
                reason =
                    map["reason"]
            )
        )
    }

    private fun handleProvisionRequired(
        message: String
    ) {
        val masterKey = privateKey
            ?: error("Chave mestre ausente")

        val parts = message.split("|")
        require(parts.size >= 3) {
            "PROVISION_REQUIRED incompleto"
        }

        val isMultiNode = parts.size >= 4
        val provisionNodeId =
            if (isMultiNode) {
                parts[2].trim()
            } else {
                null
            }

        val nonceText =
            if (isMultiNode) {
                parts[3].trim()
            } else {
                parts[2].trim()
            }

        val key =
            if (provisionNodeId.isNullOrBlank()) {
                masterKey
            } else {
                nodeId = provisionNodeId
                Crypto.deriveNodeKey(
                    masterKey,
                    provisionNodeId
                ).also {
                    sessionKey = it
                }
            }

        val nonce =
            Crypto.hexToBytes(nonceText)

        val proof =
            Crypto.bytesToHex(
                Crypto.hmacSha256(
                    key,
                    nonce
                )
            )

        listener.onLinkState(
            LinkState.Authenticating,
            if (provisionNodeId == null) {
                "Provisionando chave privada..."
            } else {
                "Provisionando identidade do nó..."
            }
        )

        writeControl(
            "PROVISION|" +
                Crypto.bytesToHex(key) +
                "|" +
                proof
        )
    }

    private fun handleHello2(
        message: String
    ) {
        legacySession = false
        migrationRequested = false

        val masterKey = privateKey
            ?: error("Chave mestre ausente")

        val parts = message.split("|")
        require(parts.size >= 3) {
            "HELLO2 incompleto"
        }

        val receivedNodeId =
            parts[1].trim()

        val nonceText =
            parts[2].trim()

        require(receivedNodeId.isNotEmpty()) {
            "node_id vazio"
        }

        nodeId = receivedNodeId

        val key =
            Crypto.deriveNodeKey(
                masterKey,
                receivedNodeId
            )

        sessionKey = key

        val nonce =
            Crypto.hexToBytes(nonceText)

        val mac =
            Crypto.bytesToHex(
                Crypto.hmacSha256(
                    key,
                    nonce
                )
            )

        writeControl("AUTH|" + mac)
    }

    private fun handleLegacyHello(
        message: String
    ) {
        legacySession = true
        migrationRequested = false

        val key = privateKey
            ?: error("Chave mestre ausente")

        sessionKey = key

        val nonce =
            Crypto.hexToBytes(
                message
                    .substringAfter("HELLO|")
                    .trim()
            )

        val mac =
            Crypto.bytesToHex(
                Crypto.hmacSha256(
                    key,
                    nonce
                )
            )

        writeControl("AUTH|" + mac)
    }

    private fun handleAuthOk(
        message: String
    ) {
        val parts = message.split("|")

        if (
            parts.size >= 3 &&
            parts[2].isNotBlank()
        ) {
            nodeId = parts[2].trim()
        }

        authenticated = true
        authGeneration++
        listener.onAuthenticated(true)
        listener.onLinkState(
            LinkState.Ready,
            "Canal privado autenticado"
        )

        writeControl("INFO")
        writeControl("STATUS")
    }

    private fun handleKeyMigrationOk(
        message: String
    ) {
        val migratedNodeId =
            message
                .substringAfter(
                    "KEY_MIGRATE_OK|"
                )
                .trim()

        if (migratedNodeId.isNotBlank()) {
            nodeId = migratedNodeId
        }

        legacySession = false
        migrationRequested = false

        val masterKey = privateKey
        val id = nodeId

        if (
            masterKey != null &&
            !id.isNullOrBlank()
        ) {
            sessionKey =
                Crypto.deriveNodeKey(
                    masterKey,
                    id
                )
        }

        log(
            "Credencial legada migrada para chave independente do nó."
        )
        listener.onLinkState(
            LinkState.Authenticating,
            "Credencial do nó atualizada • reautenticando..."
        )
    }

    private fun handleFastOtaReady(
        message: String
    ) {
        awaitingFastOta = false
        fastOtaRequestGeneration++

        val parts = message.split("|")

        if (parts.size < 8) {
            listener.onFastOtaUnavailable(
                "Resposta FAST_OTA inválida."
            )
            return
        }

        val credentials =
            FastOtaCredentials(
                ssid = parts[1],
                password = parts[2],
                tokenHex = parts[3],
                host = parts[4],
                port =
                    parts[5].toIntOrNull()
                        ?: Protocol.FAST_OTA_PORT,
                channel =
                    parts[6].toIntOrNull()
                        ?: 6,
                hidden =
                    parts[7] == "1"
            )

        log(
            "Fast OTA criada • canal " +
                credentials.channel + "."
        )

        listener.onFastOtaReady(credentials)
    }

    private fun handleOtaProgress(
        message: String
    ) {
        val parts = message.split("|")
        if (parts.size < 3) return

        val done =
            parts[1].toLongOrNull()
                ?: return

        val total =
            parts[2].toLongOrNull()
                ?.coerceAtLeast(1)
                ?: return

        listener.onOtaProgress(
            (
                done.toFloat() /
                    total.toFloat()
                ).coerceIn(0f, 1f),
            otaFileName
        )
    }

    private fun maybeMigrateLegacyKey(
        info: NodeInfo
    ) {
        if (
            !legacySession ||
            migrationRequested ||
            !authenticated
        ) {
            return
        }

        val masterKey =
            privateKey ?: return

        val id =
            info.nodeId
                ?: nodeId
                ?: return

        val derived =
            Crypto.deriveNodeKey(
                masterKey,
                id
            )

        migrationRequested = true

        writeControl(
            "KEY_MIGRATE|" +
                Crypto.bytesToHex(derived)
        )

        log(
            "Migrando credencial legada para chave exclusiva do nó " +
                id + "."
        )
    }

    private fun parseNodeInfo(
        message: String
    ): NodeInfo {
        val map = parseFields(message)

        val parsedNodeId =
            map["node_id"]

        if (!parsedNodeId.isNullOrBlank()) {
            nodeId = parsedNodeId
        }

        return NodeInfo(
            nodeId = parsedNodeId,
            model = map["model"],
            board = map["board"],
            role = map["role"],
            firmware =
                map["fw"] ?: "-",
            flashBytes =
                map["flash_bytes"]
                    ?.toLongOrNull(),
            capabilities =
                map["caps"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.toSet()
                    ?: emptySet()
        )
    }

    private fun parseTelemetry(
        message: String
    ): Telemetry {
        val map = parseFields(message)

        return Telemetry(
            firmware =
                map["fw"] ?: "-",
            nodeId =
                map["node_id"],
            model =
                map["model"],
            role =
                map["role"],
            flashBytes =
                map["flash_bytes"]
                    ?.toLongOrNull(),
            uptimeMs =
                map["uptime_ms"]
                    ?.toLongOrNull(),
            heap =
                map["heap"]
                    ?.toLongOrNull(),
            batteryVolts =
                map["battery_v"]
                    ?: "na",
            otaActive =
                map["ota"] == "1",
            fastOtaActive =
                map["fast_ota"] == "1",
            wifiAp =
                map["wifi_ap"]
                    ?.toIntOrNull(),
            wifiOpen =
                map["wifi_open"]
                    ?.toIntOrNull(),
            wifiSecure =
                map["wifi_secure"]
                    ?.toIntOrNull(),
            wifiBest =
                map["wifi_best"]
                    ?.toIntOrNull(),
            wifiPeakChannel =
                map["wifi_peak_ch"]
                    ?.toIntOrNull(),
            wifiPeakCount =
                map["wifi_peak_n"]
                    ?.toIntOrNull(),
            bleSeen =
                map["ble_seen"]
                    ?.toIntOrNull(),
            bleBest =
                map["ble_best"]
                    ?.toIntOrNull(),
            surveyAgeMs =
                map["survey_age_ms"]
                    ?.toLongOrNull()
        )
    }

    private fun parseFields(
        message: String
    ): Map<String, String> =
        buildMap {
            message
                .split("|")
                .drop(1)
                .forEach { field ->
                    val index =
                        field.indexOf('=')

                    if (index > 0) {
                        put(
                            field.substring(
                                0,
                                index
                            ),
                            field.substring(
                                index + 1
                            )
                        )
                    }
                }
        }

    private fun sendNextOtaChunk() {
        val firmware =
            otaFirmware ?: return

        if (!otaActive) return

        if (otaOffset >= firmware.size) {
            listener.onLinkState(
                LinkState.Updating,
                "Validando firmware no nó..."
            )
            writeControl("OTA_END")
            return
        }

        val end =
            (otaOffset + Protocol.BLE_OTA_CHUNK)
                .coerceAtMost(
                    firmware.size
                )

        val chunk =
            firmware.copyOfRange(
                otaOffset,
                end
            )

        session?.writeOta(chunk) {
            otaOffset = end

            listener.onOtaProgress(
                otaOffset.toFloat() /
                    firmware.size.toFloat(),
                otaFileName
            )

            sendNextOtaChunk()
        }
    }

    private fun failOta(reason: String) {
        otaActive = false
        waitingOtaReady = false

        listener.onLinkState(
            if (authenticated) {
                LinkState.Ready
            } else {
                LinkState.Error
            },
            if (authenticated) {
                "Canal privado autenticado"
            } else {
                "Falha OTA"
            }
        )

        listener.onOtaProgress(
            0f,
            otaFileName
        )

        log("OTA falhou: " + reason)
    }

    private fun writeControl(
        text: String,
        onSuccess: (() -> Unit)? = null
    ) {
        session?.writeControl(
            text.toByteArray(
                StandardCharsets.UTF_8
            ),
            onSuccess
        )
    }

    private fun resetProtocolState() {
        authenticated = false
        listener.onAuthenticated(false)

        sessionKey = null
        nodeId = null
        legacySession = false
        migrationRequested = false
        authGeneration++

        otaActive = false
        otaFirmware = null
        otaFileName = null
        otaOffset = 0
        waitingOtaReady = false

        awaitingFastOta = false
        fastOtaRequestGeneration++
    }

    private fun stopScanInternal() {
        if (scanning && hasBlePermissions()) {
            runCatching {
                scanner?.stopScan(scanCallback)
            }.onFailure { error ->
                log(
                    "stopScan: " +
                        error.javaClass.simpleName + ": " +
                        (error.message ?: "")
                )
            }
        }

        scanning = false
        scanner = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runGuarded(block)
        } else {
            main.post {
                runGuarded(block)
            }
        }
    }

    private fun runGuarded(block: () -> Unit) {
        runCatching(block).onFailure { error ->
            log(
                "Exceção contida: " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )

            listener.onLinkState(
                LinkState.Error,
                "Falha interna do ESPhub"
            )
        }
    }

    private fun log(message: String) {
        val stamp =
            SimpleDateFormat(
                "HH:mm:ss",
                Locale.getDefault()
            ).format(Date())

        listener.onLog(
            "[" + stamp + "] " + message
        )
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?
            ) {
                if (result == null) return

                onMain {
                    if (
                        !scanning ||
                        result.rssi < Protocol.MIN_RSSI
                    ) {
                        return@onMain
                    }

                    val address =
                        runCatching {
                            result.device.address
                        }.getOrNull()
                            ?: return@onMain

                    scanResults[address] = result

                    val devices =
                        scanResults
                            .values
                            .mapNotNull {
                                runCatching {
                                    NearbyDevice(
                                        device = it.device,
                                        address =
                                            it.device.address,
                                        rssi = it.rssi
                                    )
                                }.getOrNull()
                            }
                            .sortedByDescending {
                                it.rssi
                            }

                    listener.onDevices(devices)
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) = onMain {
                scanning = false
                scanner = null

                listener.onLinkState(
                    LinkState.Error,
                    "Falha no scan BLE • " +
                        errorCode
                )

                log(
                    "Scan BLE falhou: " +
                        errorCode
                )
            }
        }

    private inner class SessionListener :
        BleSessionController.Listener {

        override fun onStatus(text: String) {
            val state =
                when {
                    text.contains(
                        "Pareando",
                        ignoreCase = true
                    ) ||
                    text.contains(
                        "pareamento",
                        ignoreCase = true
                    ) ->
                        LinkState.Pairing

                    text.contains(
                        "autentic",
                        ignoreCase = true
                    ) ->
                        LinkState.Authenticating

                    text.contains(
                        "erro",
                        ignoreCase = true
                    ) ->
                        LinkState.Error

                    else ->
                        LinkState.Connecting
                }

            listener.onLinkState(
                state,
                text
            )
        }

        override fun onLog(message: String) {
            log(message)
        }

        override fun onAddress(address: String?) {
            listener.onConnectedAddress(address)
        }

        override fun onTransportReady() {
            beginAuthentication()
        }

        override fun onNotification(value: ByteArray) {
            handleNotification(value)
        }

        override fun onDisconnected() {
            authenticated = false
            listener.onAuthenticated(false)
            listener.onConnectedAddress(null)
        }

        override fun onFatalError(message: String) {
            authenticated = false
            listener.onAuthenticated(false)
            listener.onLinkState(
                LinkState.Error,
                message
            )
        }

        override fun onWriteFailure(
            channel: BleSessionController.Channel,
            status: Int
        ) {
            log(
                "Write " +
                    channel.name +
                    " falhou após retries • " +
                    status
            )

            if (
                channel ==
                    BleSessionController.Channel.OTA &&
                otaActive
            ) {
                failOta(
                    "BLE recusou escrita • " +
                        status
                )
            } else if (!authenticated) {
                listener.onLinkState(
                    LinkState.Error,
                    "Falha BLE durante autenticação • " +
                        status
                )
            }
        }
    }
}
