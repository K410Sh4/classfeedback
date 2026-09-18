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
import com.lendas.privatelink.core.Protocol
import com.lendas.privatelink.core.Telemetry
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class PrivateLinkBleManager(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLinkState(state: LinkState, text: String)
        fun onDevices(devices: List<NearbyDevice>)
        fun onLog(message: String)
        fun onAuthenticated(authenticated: Boolean)
        fun onTelemetry(telemetry: Telemetry)
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
    private var privateKey: ByteArray? = null
    private var authGeneration = 0

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
        handler.post {
            ContextCompat.registerReceiver(
                context,
                bondReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
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
    }

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

        stopScan()
        pendingBondDevice = device.device
        listener.onConnectedAddress(device.address)

        if (device.device.bondState != BluetoothDevice.BOND_BONDED) {
            listener.onLinkState(
                LinkState.Pairing,
                "Pareando • código ${Protocol.PAIRING_PASSKEY}"
            )
            log("Iniciando pareamento com ${device.address}.")
            val started = runCatching { device.device.createBond() }.getOrDefault(false)
            if (!started) {
                log("Bonding não iniciou automaticamente; tentando conexão GATT.")
                connectGatt(device.device)
            }
        } else {
            connectGatt(device.device)
        }
    }

    fun disconnect() {
        connected = false
        authenticated = false
        authGeneration++
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
        controlChar = null
        responseChar = null
        otaChar = null
        listener.onAuthenticated(false)
        listener.onConnectedAddress(null)
        listener.onLinkState(LinkState.Idle, "Aguardando ESP32-S3")
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
        val key = privateKey
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
        if (!hasBlePermissions()) return

        runCatching { gatt?.close() }
        gatt = null

        listener.onLinkState(LinkState.Connecting, "Conectando ao ESP32-S3...")
        log("Conectando em ${device.address}")

        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(
                context,
                false,
                gattCallback,
                BluetoothDevice.TRANSPORT_LE
            )
        } else {
            @Suppress("DEPRECATION")
            device.connectGatt(context, false, gattCallback)
        }
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
        }, 8_000)
    }

    private fun handleNotification(value: ByteArray) {
        val message = value.toString(StandardCharsets.UTF_8)
        log("S3 → $message")

        when {
            message.startsWith("HELLO|") -> {
                val key = privateKey ?: return
                val nonceText = message.substringAfter("HELLO|").trim()

                runCatching {
                    val nonce = Crypto.hexToBytes(nonceText)
                    val mac = Crypto.bytesToHex(Crypto.hmacSha256(key, nonce))
                    enqueueText(controlChar, "AUTH|$mac")
                }.onFailure {
                    log("HELLO inválido: ${it.message}")
                }
            }

            message.startsWith("AUTH_OK|") -> {
                authenticated = true
                authGeneration++
                listener.onAuthenticated(true)
                listener.onLinkState(LinkState.Ready, "Canal privado autenticado")
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
                listener.onLinkState(LinkState.Ready, "Firmware validado • reiniciando S3")
                log("OTA concluída com sucesso.")
            }

            message.startsWith("OTA_ERROR|") -> {
                failOta(message.substringAfter("OTA_ERROR|"))
            }
        }
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
            listener.onLinkState(LinkState.Updating, "Validando firmware no ESP32-S3...")
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
                BluetoothDevice.BOND_BONDED -> {
                    log("Pareamento concluído.")
                    connectGatt(device)
                }

                BluetoothDevice.BOND_NONE -> {
                    listener.onLinkState(LinkState.Error, "Pareamento não concluído")
                    log("Pareamento não concluído.")
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
                listener.onConnectedAddress(bluetoothGatt.device.address)
                listener.onLinkState(LinkState.Connecting, "BLE conectado • preparando canal")
                log("BLE conectado.")

                if (hasBlePermissions()) {
                    bluetoothGatt.requestConnectionPriority(
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    )
                    bluetoothGatt.requestMtu(247)

                    handler.postDelayed({
                        if (connected && gatt === bluetoothGatt) {
                            bluetoothGatt.discoverServices()
                        }
                    }, 450)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected = false
                authenticated = false
                otaActive = false
                listener.onAuthenticated(false)
                listener.onLinkState(LinkState.Idle, "BLE desconectado")
                log("BLE desconectado. status=$status")
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
