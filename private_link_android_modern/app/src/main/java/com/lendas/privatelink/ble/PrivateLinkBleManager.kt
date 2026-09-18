package com.lendas.privatelink.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.lendas.privatelink.core.Crypto
import com.lendas.privatelink.core.FastOtaCredentials
import com.lendas.privatelink.core.LinkState
import com.lendas.privatelink.core.NearbyDevice
import com.lendas.privatelink.core.NodeInfo
import com.lendas.privatelink.core.Protocol
import com.lendas.privatelink.core.Telemetry
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

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
    }

    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val scanResults = linkedMapOf<String, ScanResult>()

    private var gatt: BluetoothGatt? = null
    private var controlChar: BluetoothGattCharacteristic? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var otaChar: BluetoothGattCharacteristic? = null
    private var pendingBondDevice: BluetoothDevice? = null

    private var connected = false
    private var authenticated = false
    private var manualDisconnect = false
    private var privateKey: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var nodeId: String? = null
    private var legacySession = false
    private var migrationRequested = false
    private var authGeneration = 0
    private var awaitingBond = false
    private var bondGeneration = 0
    private var servicesDiscoveryStarted = false

    private data class WriteTask(
        val characteristic: BluetoothGattCharacteristic,
        val payload: ByteArray,
        val onSuccess: (() -> Unit)? = null,
        var attempts: Int = 0
    )

    private val writes = ArrayDeque<WriteTask>()
    private var writing = false

    private var otaActive = false
    private var otaFirmware: ByteArray? = null
    private var otaFileName: String? = null
    private var otaOffset = 0
    private var waitingOtaReady = false
    private var awaitingFastOta = false
    private var fastOtaRequestGeneration = 0
    private var receiverRegistered = false

    init {
        if (enableBondReceiver) {
            handler.post {
                runCatching {
                    ContextCompat.registerReceiver(
                        context,
                        bondReceiver,
                        IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    receiverRegistered = true
                }.onFailure { error ->
                    listener.onLinkState(
                        LinkState.Error,
                        "Falha ao iniciar monitor de pareamento"
                    )
                    log(
                        "Receiver de bonding não registrado: ${error.javaClass.simpleName}: ${error.message}"
                    )
                }
            }
        }
    }

    fun close() {
        stopScan()
        disconnect()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(bondReceiver) }
            receiverRegistered = false
        }
    }

    fun setPrivateKey(key: ByteArray?) {
        privateKey = key
        sessionKey = null
        nodeId = null
        legacySession = false
        migrationRequested = false
    }

    fun currentSessionKey(): ByteArray? =
        (sessionKey ?: privateKey)?.copyOf()

    fun currentNodeId(): String? = nodeId

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

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

    fun startScan() {
        if (!hasBlePermissions()) {
            listener.onLinkState(LinkState.Error, "Permissões Bluetooth necessárias")
            return
        }

        if (!isBluetoothEnabled()) {
            listener.onLinkState(LinkState.Error, "Ative o Bluetooth")
            return
        }

        stopScan()

        scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            listener.onLinkState(LinkState.Error, "Scanner BLE indisponível")
            return
        }

        scanResults.clear()
        listener.onDevices(emptyList())
        scanning = true
        listener.onLinkState(LinkState.Scanning, "Procurando PrivateLink próximo...")
        log("Scan BLE iniciado com filtro do serviço PrivateLink.")

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(Protocol.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)

        handler.postDelayed({
            if (scanning) stopScan()
        }, 12_000)
    }

    fun stopScan() {
        if (scanning && hasBlePermissions()) {
            runCatching { scanner?.stopScan(scanCallback) }
        }
        scanning = false
        scanner = null
    }

    fun connect(device: NearbyDevice) {
        if (!hasBlePermissions()) return

        manualDisconnect = false
        stopScan()
        pendingBondDevice = device.device
        listener.onConnectedAddress(device.address)

        // Android BLE is more reliable when bonding starts after a real GATT
        // connection exists. Starting createBond() from scan-only state can
        // remain in BOND_BONDING indefinitely on some Samsung/Android builds.
        connectGatt(device.device)
    }

    fun disconnect() {
        manualDisconnect = true
        connected = false
        authenticated = false
        authGeneration++
        awaitingBond = false
        bondGeneration++
        servicesDiscoveryStarted = false
        otaActive = false
        waitingOtaReady = false
        awaitingFastOta = false
        fastOtaRequestGeneration++
        synchronized(writes) {
            writes.clear()
            writing = false
        }

        if (hasBlePermissions()) {
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
        }

        gatt = null
        pendingBondDevice = null
        controlChar = null
        responseChar = null
        otaChar = null
        listener.onAuthenticated(false)
        listener.onConnectedAddress(null)
        listener.onLinkState(LinkState.Idle, "Nó PrivateLink desconectado")
    }

    fun sendCommand(command: String) {
        if (!authenticated) {
            log("Comando ignorado: canal ainda não autenticado.")
            return
        }
        enqueueText(controlChar, command)
    }

    fun requestFastOta() {
        if (!authenticated || controlChar == null) {
            listener.onFastOtaUnavailable("Canal BLE não autenticado.")
            return
        }

        awaitingFastOta = true
        val generation = ++fastOtaRequestGeneration

        listener.onLinkState(
            LinkState.PreparingFastOta,
            "Preparando canal Wi‑Fi privado..."
        )

        log("Solicitando sessão FAST_OTA pelo BLE autenticado.")
        enqueueText(controlChar, "FAST_OTA_BEGIN")

        handler.postDelayed({
            if (
                generation == fastOtaRequestGeneration &&
                awaitingFastOta &&
                authenticated
            ) {
                awaitingFastOta = false
                listener.onFastOtaUnavailable(
                    "Firmware atual não respondeu ao Fast OTA; usando BLE compatível."
                )
            }
        }, 6_000)
    }

    fun cancelFastOtaSession() {
        awaitingFastOta = false
        fastOtaRequestGeneration++

        if (authenticated) {
            enqueueText(controlChar, "FAST_OTA_CANCEL")
        }
    }


    fun startOta(firmware: ByteArray, fileName: String) {
        val key = currentSessionKey()
        if (!authenticated || key == null || otaActive || firmware.isEmpty()) {
            log("OTA indisponível no estado atual.")
            return
        }

        listener.onLinkState(LinkState.Updating, "Preparando atualização OTA...")
        listener.onOtaProgress(0f, fileName)

        Thread {
            try {
                val sha = Crypto.bytesToHex(Crypto.sha256(firmware))
                val hmac = Crypto.bytesToHex(Crypto.hmacSha256(key, firmware))

                otaFirmware = firmware
                otaFileName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
                otaOffset = 0
                waitingOtaReady = true
                otaActive = true

                val begin =
                    "OTA_BEGIN|${firmware.size}|$sha|$hmac|${otaFileName}"

                handler.post {
                    enqueueText(controlChar, begin)
                    log("OTA solicitada: ${firmware.size} bytes.")
                }
            } catch (error: Exception) {
                handler.post {
                    failOta("Falha ao preparar imagem: ${error.message}")
                }
            }
        }.start()
    }

    fun abortOta() {
        if (!otaActive) return
        enqueueText(controlChar, "OTA_ABORT")
        failOta("Cancelada pelo usuário")
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (!hasBlePermissions()) {
            listener.onLinkState(
                LinkState.Error,
                "Permissões Bluetooth ausentes"
            )
            return
        }

        runCatching { gatt?.close() }
        gatt = null
        servicesDiscoveryStarted = false

        listener.onLinkState(
            LinkState.Connecting,
            "Conectando ao nó PrivateLink..."
        )

        log("Conectando em ${device.address}")

        val result = runCatching {
            if (Build.VERSION.SDK_INT >= 23) {
                device.connectGatt(
                    context,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(
                    context,
                    false,
                    gattCallback
                )
            }
        }

        result.onSuccess { newGatt ->
            gatt = newGatt
            if (newGatt == null) {
                listener.onLinkState(
                    LinkState.Error,
                    "Android não criou a sessão GATT"
                )
                log("connectGatt retornou null.")
            }
        }.onFailure { error ->
            connected = false
            authenticated = false
            listener.onAuthenticated(false)
            listener.onLinkState(
                LinkState.Error,
                "Falha ao abrir conexão BLE"
            )
            log(
                "connectGatt protegido: ${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private fun beginBondingOnConnectedGatt(
        bluetoothGatt: BluetoothGatt
    ) {
        if (!hasBlePermissions()) return

        val device = bluetoothGatt.device

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            awaitingBond = false
            continueGattSetup(bluetoothGatt)
            return
        }

        awaitingBond = true
        val generation = ++bondGeneration

        listener.onLinkState(
            LinkState.Pairing,
            "Pareando • confirme o código ${Protocol.PAIRING_PASSKEY}"
        )

        log(
            "BLE conectado; iniciando pareamento seguro com ${device.address}."
        )

        handler.postDelayed({
            if (
                generation != bondGeneration ||
                !connected ||
                gatt !== bluetoothGatt
            ) {
                return@postDelayed
            }

            val started = runCatching {
                when (device.bondState) {
                    BluetoothDevice.BOND_BONDED -> true
                    BluetoothDevice.BOND_BONDING -> true
                    else -> device.createBond()
                }
            }.getOrDefault(false)

            if (!started) {
                awaitingBond = false
                bondGeneration++
                listener.onLinkState(
                    LinkState.Error,
                    "Android não iniciou o pareamento BLE"
                )
                log(
                    "createBond() foi recusado. Esqueça o dispositivo no Bluetooth e tente novamente."
                )
            }
        }, 250)

        handler.postDelayed({
            if (
                generation == bondGeneration &&
                awaitingBond &&
                device.bondState != BluetoothDevice.BOND_BONDED
            ) {
                awaitingBond = false
                bondGeneration++

                listener.onLinkState(
                    LinkState.Error,
                    "Pareamento expirou • tente novamente"
                )

                log(
                    "Timeout de pareamento após 20s. O app não ficará preso indefinidamente."
                )

                runCatching { bluetoothGatt.disconnect() }
            }
        }, 20_000)
    }

    private fun continueGattSetup(
        bluetoothGatt: BluetoothGatt
    ) {
        if (
            !connected ||
            gatt !== bluetoothGatt ||
            servicesDiscoveryStarted ||
            !hasBlePermissions()
        ) {
            return
        }

        servicesDiscoveryStarted = true

        listener.onLinkState(
            LinkState.Connecting,
            "Pareado • preparando canal seguro..."
        )

        bluetoothGatt.requestConnectionPriority(
            BluetoothGatt.CONNECTION_PRIORITY_HIGH
        )

        bluetoothGatt.requestMtu(247)

        handler.postDelayed({
            if (
                connected &&
                gatt === bluetoothGatt
            ) {
                val started = bluetoothGatt.discoverServices()

                if (!started) {
                    servicesDiscoveryStarted = false
                    listener.onLinkState(
                        LinkState.Error,
                        "Não foi possível descobrir serviços BLE"
                    )
                    log("discoverServices() não iniciou.")
                }
            }
        }, 450)
    }

    private fun beginAuthentication() {
        val key = privateKey
        if (!connected || controlChar == null || key == null) {
            log("Autenticação não iniciou: chave ou characteristic ausente.")
            return
        }

        authenticated = false
        listener.onAuthenticated(false)
        listener.onLinkState(LinkState.Authenticating, "Autenticando aplicativo...")

        val generation = ++authGeneration
        log("Iniciando HELLO seguro...")
        enqueueText(controlChar, "HELLO")

        handler.postDelayed({
            if (
                generation == authGeneration &&
                connected &&
                !authenticated
            ) {
                log("Timeout de autenticação. Repetindo HELLO...")
                synchronized(writes) {
                    writes.clear()
                    writing = false
                }
                enqueueText(controlChar, "HELLO")
            }
        }, 12_000)
    }

    private fun handleNotification(value: ByteArray) {
        val message = value.toString(StandardCharsets.UTF_8)
        log("NODE → $message")

        when {
            message.startsWith("PROVISION_REQUIRED|") -> {
                val masterKey = privateKey

                if (masterKey == null) {
                    listener.onLinkState(
                        LinkState.Error,
                        "Chave privada não configurada no aplicativo"
                    )
                    return
                }

                val parts = message.split("|")

                if (parts.size < 3) {
                    listener.onLinkState(
                        LinkState.Error,
                        "Resposta de provisionamento inválida"
                    )
                    return
                }

                val isMultiNode = parts.size >= 4
                val provisionNodeId =
                    if (isMultiNode) parts[2].trim() else null
                val nonceText =
                    if (isMultiNode) parts[3].trim() else parts[2].trim()

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

                runCatching {
                    val nonce = Crypto.hexToBytes(nonceText)
                    val proof = Crypto.bytesToHex(
                        Crypto.hmacSha256(key, nonce)
                    )
                    val keyHex = Crypto.bytesToHex(key)

                    listener.onLinkState(
                        LinkState.Authenticating,
                        if (provisionNodeId == null)
                            "Provisionando chave privada..."
                        else
                            "Provisionando identidade do nó..."
                    )

                    log(
                        if (provisionNodeId == null)
                            "Provisionamento legado iniciado pelo BLE criptografado."
                        else
                            "Chave independente derivada para o nó $provisionNodeId."
                    )

                    enqueueText(
                        controlChar,
                        "PROVISION|$keyHex|$proof"
                    )
                }.onFailure {
                    listener.onLinkState(
                        LinkState.Error,
                        "Falha no provisionamento"
                    )
                    log(
                        "Provisionamento inválido: ${it.message}"
                    )
                }
            }

            message.startsWith("PROVISION_OK|") -> {
                log(
                    "ESP32-S3 provisionado. Aguardando novo desafio HMAC..."
                )
                listener.onLinkState(
                    LinkState.Authenticating,
                    "Chave provisionada • autenticando..."
                )
            }

            message.startsWith("PROVISION_LOCKED|") -> {
                listener.onLinkState(
                    LinkState.Error,
                    "Janela de provisionamento encerrada • reinicie o ESP32-S3"
                )
                log(
                    "Provisionamento bloqueado pelo firmware até o próximo reboot."
                )
            }

            message.startsWith("PROVISION_ERROR|") -> {
                listener.onLinkState(
                    LinkState.Error,
                    "ESP32-S3 recusou o provisionamento"
                )
                log(message)
            }

            message == "ERR|link_not_encrypted" -> {
                listener.onLinkState(
                    LinkState.Error,
                    "Link BLE não criptografado • refaça o pareamento"
                )
                log(
                    "O ESP32 recusou a operação porque o link BLE não estava criptografado."
                )
            }

            message.startsWith("HELLO2|") -> {
                legacySession = false
                migrationRequested = false
                val masterKey = privateKey ?: return
                val parts = message.split("|")

                if (parts.size < 3) {
                    log("HELLO2 inválido.")
                    return
                }

                val receivedNodeId = parts[1].trim()
                val nonceText = parts[2].trim()
                nodeId = receivedNodeId

                val key = Crypto.deriveNodeKey(
                    masterKey,
                    receivedNodeId
                )

                sessionKey = key

                runCatching {
                    val nonce = Crypto.hexToBytes(nonceText)
                    val mac = Crypto.bytesToHex(
                        Crypto.hmacSha256(key, nonce)
                    )

                    enqueueText(
                        controlChar,
                        "AUTH|$mac"
                    )
                }.onFailure {
                    log(
                        "HELLO2 inválido: ${it.message}"
                    )
                }
            }

            message.startsWith("HELLO|") -> {
                legacySession = true
                migrationRequested = false
                val key = privateKey ?: return
                sessionKey = key
                val nonceText = message.substringAfter("HELLO|").trim()

                runCatching {
                    val nonce = Crypto.hexToBytes(nonceText)
                    val mac = Crypto.bytesToHex(
                        Crypto.hmacSha256(key, nonce)
                    )
                    enqueueText(
                        controlChar,
                        "AUTH|$mac"
                    )
                }.onFailure {
                    log(
                        "HELLO inválido: ${it.message}"
                    )
                }
            }

            message.startsWith("AUTH_OK|") -> {
                val parts = message.split("|")
                if (parts.size >= 3 && parts[2].isNotBlank()) {
                    nodeId = parts[2].trim()
                }

                authenticated = true
                authGeneration++
                listener.onAuthenticated(true)
                listener.onLinkState(
                    LinkState.Ready,
                    "Canal privado autenticado"
                )

                enqueueText(controlChar, "INFO")
                enqueueText(controlChar, "STATUS")
            }

            message.startsWith("AUTH_FAIL|") -> {
                authenticated = false
                listener.onAuthenticated(false)
                listener.onLinkState(LinkState.Error, "Chave privada rejeitada")
            }

            message.startsWith("TEL|") -> {
                listener.onTelemetry(parseTelemetry(message))
            }

            message.startsWith("INFO|") -> {
                val info = parseNodeInfo(message)
                listener.onNodeInfo(info)
                maybeMigrateLegacyKey(info)
            }

            message.startsWith("KEY_MIGRATE_OK|") -> {
                val migratedNodeId =
                    message.substringAfter("KEY_MIGRATE_OK|").trim()

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
                    "Chave legada migrada para credencial independente do nó."
                )
                listener.onLinkState(
                    LinkState.Authenticating,
                    "Credencial do nó atualizada • reautenticando..."
                )
            }

            message.startsWith("KEY_MIGRATE_ERROR|") -> {
                migrationRequested = false
                log(message)
            }

            message.startsWith("FAST_OTA_READY|") && awaitingFastOta -> {
                awaitingFastOta = false
                fastOtaRequestGeneration++

                val parts = message.split("|")

                if (parts.size < 8) {
                    listener.onFastOtaUnavailable(
                        "Resposta FAST_OTA inválida."
                    )
                } else {
                    val credentials = FastOtaCredentials(
                        ssid = parts[1],
                        password = parts[2],
                        tokenHex = parts[3],
                        host = parts[4],
                        port = parts[5].toIntOrNull() ?: Protocol.FAST_OTA_PORT,
                        channel = parts[6].toIntOrNull() ?: 6,
                        hidden = parts[7] == "1"
                    )

                    log(
                        "Sessão Fast OTA criada • canal ${credentials.channel} • " +
                            "rede temporária protegida."
                    )

                    listener.onFastOtaReady(credentials)
                }
            }

            message.startsWith("FAST_OTA_ERROR|") && awaitingFastOta -> {
                awaitingFastOta = false
                fastOtaRequestGeneration++
                listener.onFastOtaUnavailable(
                    message.substringAfter("FAST_OTA_ERROR|")
                )
            }

            message == "ERR|unknown_command" && awaitingFastOta -> {
                awaitingFastOta = false
                fastOtaRequestGeneration++
                listener.onFastOtaUnavailable(
                    "Fast OTA não existe neste firmware; usando BLE compatível."
                )
            }

            message.startsWith("OTA_READY|") && waitingOtaReady -> {
                waitingOtaReady = false
                otaOffset = 0
                listener.onLinkState(LinkState.Updating, "Enviando firmware...")
                handler.post { sendNextOtaChunk() }
            }

            message.startsWith("OTA_PROGRESS|") -> {
                val parts = message.split("|")
                if (parts.size >= 3) {
                    val done = parts[1].toLongOrNull() ?: 0
                    val total = parts[2].toLongOrNull() ?: 1
                    listener.onOtaProgress(
                        (done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f),
                        otaFileName
                    )
                }
            }

            message.startsWith("OTA_OK|") -> {
                otaActive = false
                waitingOtaReady = false
                listener.onOtaProgress(1f, otaFileName)
                listener.onLinkState(LinkState.Ready, "Firmware validado • reiniciando nó")
                log("OTA concluída com sucesso.")
            }

            message.startsWith("OTA_ERROR|") -> {
                failOta(message.substringAfter("OTA_ERROR|"))
            }
        }
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

        val masterKey = privateKey ?: return
        val id = info.nodeId ?: nodeId ?: return

        val derived =
            Crypto.deriveNodeKey(
                masterKey,
                id
            )

        migrationRequested = true

        enqueueText(
            controlChar,
            "KEY_MIGRATE|" +
                Crypto.bytesToHex(derived)
        )

        log(
            "Migrando credencial legada para chave exclusiva do nó $id."
        )
    }

    private fun parseNodeInfo(message: String): NodeInfo {
        val map = buildMap {
            message.split("|").drop(1).forEach { field ->
                val index = field.indexOf('=')
                if (index > 0) {
                    put(
                        field.substring(0, index),
                        field.substring(index + 1)
                    )
                }
            }
        }

        val parsedNodeId = map["node_id"]
        if (!parsedNodeId.isNullOrBlank()) {
            nodeId = parsedNodeId
        }

        return NodeInfo(
            nodeId = parsedNodeId,
            model = map["model"],
            board = map["board"],
            role = map["role"],
            firmware = map["fw"] ?: "-",
            flashBytes = map["flash_bytes"]?.toLongOrNull(),
            capabilities = map["caps"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toSet()
                ?: emptySet()
        )
    }

    private fun parseTelemetry(message: String): Telemetry {
        val map = buildMap {
            message.split("|").drop(1).forEach { field ->
                val index = field.indexOf('=')
                if (index > 0) {
                    put(field.substring(0, index), field.substring(index + 1))
                }
            }
        }

        return Telemetry(
            firmware = map["fw"] ?: "-",
            nodeId = map["node_id"],
            model = map["model"],
            role = map["role"],
            flashBytes = map["flash_bytes"]?.toLongOrNull(),
            uptimeMs = map["uptime_ms"]?.toLongOrNull(),
            heap = map["heap"]?.toLongOrNull(),
            batteryVolts = map["battery_v"] ?: "na",
            otaActive = map["ota"] == "1",
            fastOtaActive = map["fast_ota"] == "1",
            wifiAp = map["wifi_ap"]?.toIntOrNull(),
            wifiOpen = map["wifi_open"]?.toIntOrNull(),
            wifiSecure = map["wifi_secure"]?.toIntOrNull(),
            wifiBest = map["wifi_best"]?.toIntOrNull(),
            wifiPeakChannel = map["wifi_peak_ch"]?.toIntOrNull(),
            wifiPeakCount = map["wifi_peak_n"]?.toIntOrNull(),
            bleSeen = map["ble_seen"]?.toIntOrNull(),
            bleBest = map["ble_best"]?.toIntOrNull(),
            surveyAgeMs = map["survey_age_ms"]?.toLongOrNull()
        )
    }

    private fun sendNextOtaChunk() {
        val firmware = otaFirmware
        val characteristic = otaChar

        if (!otaActive || firmware == null || characteristic == null) return

        if (otaOffset >= firmware.size) {
            listener.onLinkState(LinkState.Updating, "Validando firmware no nó...")
            enqueueText(controlChar, "OTA_END")
            return
        }

        val end = (otaOffset + Protocol.BLE_OTA_CHUNK).coerceAtMost(firmware.size)
        val chunk = firmware.copyOfRange(otaOffset, end)

        enqueueWrite(characteristic, chunk) {
            otaOffset = end
            listener.onOtaProgress(
                otaOffset.toFloat() / firmware.size.toFloat(),
                otaFileName
            )
            sendNextOtaChunk()
        }
    }

    private fun failOta(reason: String) {
        otaActive = false
        waitingOtaReady = false
        listener.onLinkState(
            if (authenticated) LinkState.Ready else LinkState.Error,
            if (authenticated) "Canal privado autenticado" else "Falha OTA"
        )
        listener.onOtaProgress(0f, otaFileName)
        log("OTA falhou: $reason")
    }

    private fun enqueueText(
        characteristic: BluetoothGattCharacteristic?,
        text: String,
        onSuccess: (() -> Unit)? = null
    ) {
        characteristic ?: return
        enqueueWrite(characteristic, text.toByteArray(StandardCharsets.UTF_8), onSuccess)
    }

    private fun enqueueWrite(
        characteristic: BluetoothGattCharacteristic,
        payload: ByteArray,
        onSuccess: (() -> Unit)? = null
    ) {
        synchronized(writes) {
            writes.addLast(WriteTask(characteristic, payload, onSuccess))
        }
        pumpWrites()
    }

    private fun pumpWrites() {
        val localGatt = gatt
        if (localGatt == null || !connected || !hasBlePermissions()) return

        val task: WriteTask = synchronized(writes) {
            if (writing || writes.isEmpty()) return
            writing = true
            writes.first()
        }

        val status: Int
        val started: Boolean

        if (Build.VERSION.SDK_INT >= 33) {
            status = localGatt.writeCharacteristic(
                task.characteristic,
                task.payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            started = status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            task.characteristic.writeType =
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            task.characteristic.value = task.payload
            @Suppress("DEPRECATION")
            started = localGatt.writeCharacteristic(task.characteristic)
            status = if (started) 0 else -1
        }

        if (!started) {
            task.attempts++
            synchronized(writes) {
                writing = false
            }

            log(
                "Write BLE ocupado/recusado. código=$status " +
                    "tentativa=${task.attempts}/${Protocol.MAX_WRITE_RETRIES}"
            )

            if (task.attempts >= Protocol.MAX_WRITE_RETRIES) {
                synchronized(writes) {
                    if (writes.isNotEmpty()) writes.removeFirst()
                }

                if (otaActive) {
                    failOta("BLE recusou escrita • código $status")
                } else if (!authenticated) {
                    listener.onLinkState(
                        LinkState.Error,
                        "Falha BLE antes do HELLO • código $status"
                    )
                }
                return
            }

            handler.postDelayed(
                { pumpWrites() },
                (180L * task.attempts).coerceAtMost(1800L)
            )
        }
    }

    private fun log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        listener.onLog("[$stamp] $message")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            result ?: return
            if (result.rssi < Protocol.MIN_RSSI) return

            val address = result.device.address
            scanResults[address] = result

            val devices = scanResults.values
                .map {
                    NearbyDevice(
                        device = it.device,
                        address = it.device.address,
                        rssi = it.rssi
                    )
                }
                .sortedByDescending { it.rssi }

            listener.onDevices(devices)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            listener.onLinkState(LinkState.Error, "Falha no scan BLE • $errorCode")
            log("Scan BLE falhou: $errorCode")
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            } ?: return

            val pending = pendingBondDevice ?: return
            if (device.address != pending.address) return

            when (
                intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR
                )
            ) {
                BluetoothDevice.BOND_BONDING -> {
                    awaitingBond = true
                    listener.onLinkState(
                        LinkState.Pairing,
                        "Aguardando confirmação do pareamento..."
                    )
                    log("Android informou BOND_BONDING.")
                }

                BluetoothDevice.BOND_BONDED -> {
                    awaitingBond = false
                    bondGeneration++
                    log("Pareamento concluído.")

                    if (manualDisconnect) {
                        log(
                            "Pareamento terminou após desconexão manual; reconexão cancelada."
                        )
                        return
                    }

                    val activeGatt = gatt

                    if (
                        connected &&
                        activeGatt != null &&
                        activeGatt.device.address == device.address
                    ) {
                        continueGattSetup(activeGatt)
                    } else {
                        log(
                            "Pareamento concluído após desconexão; reconectando GATT."
                        )
                        handler.postDelayed({
                            if (!manualDisconnect) {
                                connectGatt(device)
                            }
                        }, 500)
                    }
                }

                BluetoothDevice.BOND_NONE -> {
                    if (awaitingBond) {
                        awaitingBond = false
                        bondGeneration++
                        listener.onLinkState(
                            LinkState.Error,
                            "Pareamento cancelado ou rejeitado"
                        )
                        log(
                            "Pareamento não concluído. Confirme o diálogo do Android e use o código ${Protocol.PAIRING_PASSKEY}."
                        )
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            bluetoothGatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connected = true
                servicesDiscoveryStarted = false
                listener.onConnectedAddress(bluetoothGatt.device.address)
                listener.onLinkState(
                    LinkState.Connecting,
                    "BLE conectado • verificando segurança..."
                )
                log("BLE conectado.")

                if (hasBlePermissions()) {
                    runCatching {
                        beginBondingOnConnectedGatt(
                            bluetoothGatt
                        )
                    }.onFailure { error ->
                        listener.onLinkState(
                            LinkState.Error,
                            "Falha ao iniciar segurança BLE"
                        )
                        log(
                            "Bonding protegido: ${error.javaClass.simpleName}: ${error.message}"
                        )
                        runCatching {
                            bluetoothGatt.disconnect()
                        }
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                authenticated = false
                servicesDiscoveryStarted = false
                otaActive = false
                listener.onAuthenticated(false)

                val device = bluetoothGatt.device

                if (
                    awaitingBond &&
                    device.bondState == BluetoothDevice.BOND_BONDING
                ) {
                    listener.onLinkState(
                        LinkState.Pairing,
                        "Pareamento em andamento..."
                    )
                    log(
                        "GATT desconectou durante BOND_BONDING; aguardando resultado do sistema."
                    )
                } else if (
                    !manualDisconnect &&
                    device.bondState == BluetoothDevice.BOND_BONDED &&
                    pendingBondDevice?.address == device.address
                ) {
                    listener.onLinkState(
                        LinkState.Connecting,
                        "Pareado • reconectando..."
                    )
                    log(
                        "BLE desconectou após pareamento; reconexão automática."
                    )

                    handler.postDelayed({
                        if (
                            !connected &&
                            !manualDisconnect
                        ) {
                            connectGatt(device)
                        }
                    }, 700)
                } else {
                    listener.onLinkState(
                        LinkState.Idle,
                        if (manualDisconnect)
                            "Desconectado pelo usuário"
                        else
                            "BLE desconectado"
                    )

                    log(
                        if (manualDisconnect)
                            "BLE desconectado pelo usuário."
                        else
                            "BLE desconectado. status=$status"
                    )
                }
            }
        }

        override fun onMtuChanged(
            bluetoothGatt: BluetoothGatt,
            mtu: Int,
            status: Int
        ) {
            log("MTU: $mtu")
        }

        override fun onServicesDiscovered(
            bluetoothGatt: BluetoothGatt,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Falha ao descobrir serviços: $status")
                return
            }

            val service: BluetoothGattService =
                bluetoothGatt.getService(Protocol.SERVICE_UUID)
                    ?: run {
                        log("Serviço PrivateLink não encontrado.")
                        return
                    }

            controlChar = service.getCharacteristic(Protocol.CONTROL_UUID)
            responseChar = service.getCharacteristic(Protocol.RESPONSE_UUID)
            otaChar = service.getCharacteristic(Protocol.OTA_UUID)

            if (
                controlChar == null ||
                responseChar == null ||
                otaChar == null
            ) {
                log("Characteristics PrivateLink incompletas.")
                return
            }

            if (!hasBlePermissions()) return

            val response = responseChar ?: return
            bluetoothGatt.setCharacteristicNotification(response, true)

            val descriptor = response.getDescriptor(Protocol.CCCD_UUID)

            if (descriptor == null) {
                handler.postDelayed({ beginAuthentication() }, 1200)
                return
            }

            val started = if (Build.VERSION.SDK_INT >= 33) {
                bluetoothGatt.writeDescriptor(
                    descriptor,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                bluetoothGatt.writeDescriptor(descriptor)
            }

            if (!started) {
                log("Descriptor CCCD não iniciou; tentando autenticar após espera.")
                handler.postDelayed({ beginAuthentication() }, 1500)
            }
        }

        override fun onDescriptorWrite(
            bluetoothGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == Protocol.CCCD_UUID) {
                log("Notifications ativas. status=$status")
                handler.postDelayed({ beginAuthentication() }, 1200)
            }
        }

        override fun onCharacteristicChanged(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == Protocol.RESPONSE_UUID) {
                handleNotification(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (
                Build.VERSION.SDK_INT < 33 &&
                characteristic.uuid == Protocol.RESPONSE_UUID
            ) {
                handleNotification(characteristic.value ?: return)
            }
        }

        override fun onCharacteristicWrite(
            bluetoothGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            val completed: WriteTask? = synchronized(writes) {
                val task = if (writes.isNotEmpty()) writes.removeFirst() else null
                writing = false
                task
            }

            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("Write BLE falhou. status=$status")
                if (otaActive) failOta("Write GATT falhou • $status")
            } else {
                completed?.onSuccess?.let { handler.post(it) }
            }

            pumpWrites()
        }
    }
}
