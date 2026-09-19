package com.esphub.lab

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
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
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

class BleManager(
    private val context: Context,
    private val masterKeyProvider: () -> ByteArray?,
    private val listener: Listener
) {
    private data class WriteTask(
        val payload: ByteArray,
        var attempts: Int = 0
    )
    interface Listener {
        fun onScanning(scanning: Boolean)
        fun onDiscovered(nodes: List<DiscoveredNode>)
        fun onNodeChanged(node: NodeState)
        fun onLog(message: String)
    }

    private val main =
        Handler(Looper.getMainLooper())

    private val bluetoothManager =
        context.getSystemService(
            Context.BLUETOOTH_SERVICE
        ) as BluetoothManager

    private val adapter
        get() = bluetoothManager.adapter

    private var scanner =
        adapter?.bluetoothLeScanner

    private var scanning = false

    private val discoveries =
        linkedMapOf<String, DiscoveredNode>()

    private val sessions =
        linkedMapOf<String, Session>()

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun bluetoothEnabled(): Boolean =
        adapter?.isEnabled == true

    fun startScan() = onMain {
        if (!hasPermissions()) {
            log("Permissões Bluetooth ausentes.")
            return@onMain
        }

        if (!bluetoothEnabled()) {
            log("Bluetooth desativado.")
            return@onMain
        }

        stopScanInternal()
        discoveries.clear()

        scanner =
            adapter?.bluetoothLeScanner

        val activeScanner =
            scanner ?: run {
                log("Scanner BLE indisponível.")
                return@onMain
            }

        val filter =
            ScanFilter.Builder()
                .setServiceUuid(
                    ParcelUuid(
                        Protocol.SERVICE_UUID
                    )
                )
                .build()

        val settings =
            ScanSettings.Builder()
                .setScanMode(
                    ScanSettings.SCAN_MODE_LOW_LATENCY
                )
                .build()

        val started =
            runCatching {
                activeScanner.startScan(
                    listOf(filter),
                    settings,
                    scanCallback
                )
                true
            }.getOrElse {
                log(
                    "Falha ao iniciar scan: " +
                        it.javaClass.simpleName +
                        ": " +
                        (it.message ?: "")
                )
                false
            }

        if (!started) {
            return@onMain
        }

        scanning = true
        listener.onScanning(true)
        listener.onDiscovered(emptyList())
        log("Scan ESPhub iniciado.")

        main.postDelayed({
            if (scanning) {
                stopScanInternal()
                log("Scan concluído.")
            }
        }, Protocol.SCAN_TIMEOUT_MS)
    }

    fun stopScan() = onMain {
        stopScanInternal()
    }

    fun connect(
        discovered: DiscoveredNode
    ) = onMain {
        if (!hasPermissions()) {
            log("Conexão recusada: permissões Bluetooth ausentes.")
            return@onMain
        }

        stopScanInternal()

        val key =
            normalize(
                discovered.address
            )

        val session =
            sessions.getOrPut(key) {
                Session(
                    device =
                        discovered.device,
                    initialAddress = key
                )
            }

        session.updateRssi(
            discovered.rssi
        )

        session.connect()
    }

    fun disconnect(
        address: String
    ) = onMain {
        sessions[
            normalize(address)
        ]?.disconnect(
            "Desconectado pelo usuário"
        )
    }

    fun disconnectAll() = onMain {
        sessions.values.forEach {
            it.disconnect(
                "Desconectado pelo usuário"
            )
        }
    }

    fun startLab(
        address: String,
        ssid: String,
        password: String,
        targetIpv4: String,
        level: Int,
        durationSeconds: Int
    ) = onMain {
        sessions[
            normalize(address)
        ]?.startLab(
            LabRequest(
                ssid = ssid,
                password = password,
                targetIpv4 = targetIpv4,
                level = level,
                durationSeconds =
                    durationSeconds
            )
        )
    }

    fun stopLab(
        address: String
    ) = onMain {
        sessions[
            normalize(address)
        ]?.sendText(
            "LAB_STOP"
        )
    }

    fun requestLabStatus(
        address: String
    ) = onMain {
        sessions[
            normalize(address)
        ]?.sendText(
            "LAB_STATUS"
        )
    }

    fun requestWifiScan(
        address: String
    ) = onMain {
        sessions[
            normalize(address)
        ]?.requestWifiScan()
    }

    fun startMonitor(
        address: String,
        channel: Int,
        durationSeconds: Int,
        bssid: String?
    ) = onMain {
        sessions[
            normalize(address)
        ]?.startMonitor(
            channel = channel,
            durationSeconds = durationSeconds,
            bssid = bssid
        )
    }

    fun stopMonitor(
        address: String
    ) = onMain {
        sessions[
            normalize(address)
        ]?.sendText(
            "MONITOR_STOP"
        )
    }

    fun requestMonitorStatus(
        address: String
    ) = onMain {
        sessions[
            normalize(address)
        ]?.sendText(
            "MONITOR_STATUS"
        )
    }

    fun close() = onMain {
        stopScanInternal()
        sessions.values.forEach {
            it.close()
        }
        sessions.clear()
    }

    private fun stopScanInternal() {
        if (
            scanning &&
            hasPermissions()
        ) {
            runCatching {
                scanner?.stopScan(
                    scanCallback
                )
            }
        }

        scanning = false
        listener.onScanning(false)
    }

    private val scanCallback =
        object : ScanCallback() {
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult?
            ) {
                if (result == null) {
                    return
                }

                onMain {
                    if (!scanning) {
                        return@onMain
                    }

                    val address =
                        runCatching {
                            result.device.address
                        }.getOrNull()
                            ?: return@onMain

                    val key =
                        normalize(address)

                    discoveries[key] =
                        DiscoveredNode(
                            device =
                                result.device,
                            address = key,
                            rssi =
                                result.rssi
                        )

                    listener.onDiscovered(
                        discoveries
                            .values
                            .sortedByDescending {
                                it.rssi
                            }
                    )
                }
            }

            override fun onScanFailed(
                errorCode: Int
            ) {
                onMain {
                    scanning = false
                    listener.onScanning(false)
                    log(
                        "Scan BLE falhou: " +
                            errorCode
                    )
                }
            }
        }

    private inner class Session(
        private val device: BluetoothDevice,
        initialAddress: String
    ) {
        private var state =
            NodeState(
                address =
                    normalize(initialAddress)
            )

        private var generation = 0
        private var gatt: BluetoothGatt? = null
        private var connected = false

        private var control:
            BluetoothGattCharacteristic? = null

        private var response:
            BluetoothGattCharacteristic? = null

        private val writeQueue =
            ArrayDeque<WriteTask>()

        private var writeInFlight = false

        private var sessionKey:
            ByteArray? = null

        private var legacySession = false
        private var migrationRequested = false

        private var pendingLab:
            LabRequest? = null

        fun updateRssi(
            rssi: Int
        ) {
            update {
                it.copy(
                    rssi = rssi
                )
            }
        }

        fun connect() {
            generation += 1
            val token = generation

            clearGatt()

            update {
                it.copy(
                    phase =
                        SessionPhase.Connecting,
                    status =
                        "Conectando...",
                    authenticated = false,
                    lastError = null
                )
            }

            val callback =
                callbackFor(token)

            val created =
                runCatching {
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.M
                    ) {
                        device.connectGatt(
                            context,
                            false,
                            callback,
                            BluetoothDevice.TRANSPORT_LE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        device.connectGatt(
                            context,
                            false,
                            callback
                        )
                    }
                }.getOrElse {
                    fail(
                        "connectGatt: " +
                            it.javaClass.simpleName
                    )
                    null
                }

            if (
                token != generation
            ) {
                runCatching {
                    created?.close()
                }
                return
            }

            if (created == null) {
                fail(
                    "Android não criou a sessão GATT."
                )
                return
            }

            gatt = created
            log(
                "GATT conectado ao fluxo de sessão " +
                    state.address
            )
        }

        fun disconnect(
            reason: String
        ) {
            generation += 1
            pendingLab = null
            sessionKey = null
            legacySession = false
            migrationRequested = false
            clearGatt()

            update {
                it.copy(
                    phase =
                        SessionPhase.Idle,
                    status = reason,
                    authenticated = false,
                    lab =
                        if (it.lab.active) {
                            it.lab.copy(
                                state =
                                    "STOPPED",
                                reason =
                                    "control_lost"
                            )
                        } else {
                            it.lab
                        }
                )
            }
        }

        fun close() {
            generation += 1
            clearGatt()
        }

        fun startLab(
            request: LabRequest
        ) {
            if (!state.authenticated) {
                fail(
                    "LabTest exige sessão autenticada."
                )
                return
            }

            if (
                "LABTEST_V1" !in
                state.capabilities
            ) {
                fail(
                    "Firmware sem LABTEST_V1."
                )
                return
            }

            pendingLab = request

            update {
                it.copy(
                    lab =
                        LabMetrics(
                            state =
                                "STARTING",
                            level =
                                request.level,
                            target =
                                request.targetIpv4,
                            port =
                                it.labDefaultPort
                        )
                )
            }

            val ssid =
                Base64.encodeToString(
                    request.ssid
                        .toByteArray(
                            Charsets.UTF_8
                        ),
                    Base64.NO_WRAP
                )

            val password =
                Base64.encodeToString(
                    request.password
                        .toByteArray(
                            Charsets.UTF_8
                        ),
                    Base64.NO_WRAP
                )

            sendText(
                "LAB_WIFI|" +
                    ssid +
                    "|" +
                    password
            )
        }

        fun requestWifiScan() {
            if (!state.authenticated) {
                log(
                    "Wi-Fi scan exige sessão autenticada."
                )
                return
            }

            if (
                "WIFI_SCAN_V1" !in
                state.capabilities
            ) {
                log(
                    "Firmware sem WIFI_SCAN_V1."
                )
                return
            }

            update {
                it.copy(
                    wifiAccessPoints =
                        emptyList(),
                    wifiScanState =
                        "SCANNING"
                )
            }

            sendText(
                "WIFI_SCAN"
            )
        }

        fun startMonitor(
            channel: Int,
            durationSeconds: Int,
            bssid: String?
        ) {
            if (!state.authenticated) {
                log(
                    "Monitor exige sessão autenticada."
                )
                return
            }

            if (
                "MONITOR_V1" !in
                state.capabilities
            ) {
                log(
                    "Firmware sem MONITOR_V1."
                )
                return
            }

            val target =
                bssid
                    ?.trim()
                    ?.uppercase(Locale.US)
                    .orEmpty()

            update {
                it.copy(
                    monitor =
                        MonitorMetrics(
                            state =
                                "STARTING",
                            channel =
                                channel,
                            hopping =
                                channel == 0
                        )
                )
            }

            sendText(
                "MONITOR_START|" +
                    channel +
                    "|" +
                    durationSeconds +
                    "|" +
                    target
            )
        }

        fun sendText(
            text: String
        ) {
            if (
                gatt == null ||
                control == null
            ) {
                log(
                    "Comando ignorado sem transporte: " +
                        text.substringBefore("|")
                )
                return
            }

            writeQueue.addLast(
                WriteTask(
                    payload =
                        text.toByteArray(
                            StandardCharsets.UTF_8
                        )
                )
            )

            pumpWrites()
        }

        private fun beginBonding(
            activeGatt: BluetoothGatt,
            token: Int
        ) {
            if (
                !isCurrent(
                    activeGatt,
                    token
                )
            ) {
                return
            }

            val bondState =
                safeValue(
                    BluetoothDevice.BOND_NONE
                ) {
                    device.bondState
                }

            if (
                bondState ==
                BluetoothDevice.BOND_BONDED
            ) {
                beginMtu(
                    activeGatt,
                    token
                )
                return
            }

            update {
                it.copy(
                    phase =
                        SessionPhase.Bonding,
                    status =
                        "Pareando • código " +
                            Protocol.PAIRING_PASSKEY
                )
            }

            val started =
                safeValue(false) {
                    when (
                        device.bondState
                    ) {
                        BluetoothDevice.BOND_BONDED,
                        BluetoothDevice.BOND_BONDING ->
                            true

                        else ->
                            device.createBond()
                    }
                }

            if (!started) {
                fail(
                    "Android não iniciou o pareamento."
                )
                return
            }

            val startedAt =
                System.currentTimeMillis()

            fun pollBond() {
                if (
                    token != generation
                ) {
                    return
                }

                val current =
                    safeValue(
                        BluetoothDevice.BOND_NONE
                    ) {
                        device.bondState
                    }

                when (current) {
                    BluetoothDevice.BOND_BONDED -> {
                        val currentGatt =
                            gatt

                        if (
                            connected &&
                            currentGatt != null
                        ) {
                            beginMtu(
                                currentGatt,
                                token
                            )
                        } else {
                            reconnectAfterBond(
                                token
                            )
                        }
                    }

                    BluetoothDevice.BOND_BONDING -> {
                        if (
                            System.currentTimeMillis() -
                            startedAt >
                            Protocol.BOND_TIMEOUT_MS
                        ) {
                            fail(
                                "Tempo de pareamento expirou."
                            )
                        } else {
                            main.postDelayed(
                                {
                                    pollBond()
                                },
                                300L
                            )
                        }
                    }

                    else -> {
                        if (
                            System.currentTimeMillis() -
                            startedAt >
                            Protocol.BOND_TIMEOUT_MS
                        ) {
                            fail(
                                "Pareamento cancelado ou recusado."
                            )
                        } else {
                            main.postDelayed(
                                {
                                    pollBond()
                                },
                                300L
                            )
                        }
                    }
                }
            }

            main.postDelayed(
                {
                    pollBond()
                },
                300L
            )
        }

        private fun reconnectAfterBond(
            previousToken: Int
        ) {
            if (
                previousToken != generation
            ) {
                return
            }

            clearGatt()
            generation += 1

            main.postDelayed(
                {
                    connect()
                },
                250L
            )
        }

        private fun beginMtu(
            activeGatt: BluetoothGatt,
            token: Int
        ) {
            if (
                !isCurrent(
                    activeGatt,
                    token
                )
            ) {
                return
            }

            update {
                it.copy(
                    phase =
                        SessionPhase.Mtu,
                    status =
                        "Negociando MTU..."
                )
            }

            safeUnit {
                activeGatt
                    .requestConnectionPriority(
                        BluetoothGatt
                            .CONNECTION_PRIORITY_HIGH
                    )
            }

            val requested =
                safeValue(false) {
                    activeGatt.requestMtu(
                        247
                    )
                }

            if (!requested) {
                beginDiscovery(
                    activeGatt,
                    token
                )
                return
            }

            main.postDelayed({
                if (
                    token == generation &&
                    state.phase ==
                    SessionPhase.Mtu
                ) {
                    beginDiscovery(
                        activeGatt,
                        token
                    )
                }
            }, Protocol.MTU_TIMEOUT_MS)
        }

        private fun beginDiscovery(
            activeGatt: BluetoothGatt,
            token: Int
        ) {
            if (
                !isCurrent(
                    activeGatt,
                    token
                )
            ) {
                return
            }

            if (
                state.phase ==
                SessionPhase.Discovering ||
                state.phase ==
                SessionPhase.Subscribing
            ) {
                return
            }

            update {
                it.copy(
                    phase =
                        SessionPhase.Discovering,
                    status =
                        "Descobrindo serviços..."
                )
            }

            val started =
                safeValue(false) {
                    activeGatt
                        .discoverServices()
                }

            if (!started) {
                fail(
                    "Não foi possível descobrir os serviços BLE."
                )
            }
        }

        private fun subscribe(
            activeGatt: BluetoothGatt,
            token: Int
        ) {
            val service =
                activeGatt.getService(
                    Protocol.SERVICE_UUID
                )

            if (service == null) {
                fail(
                    "Serviço ESPhub não encontrado."
                )
                return
            }

            control =
                service.getCharacteristic(
                    Protocol.CONTROL_UUID
                )

            response =
                service.getCharacteristic(
                    Protocol.RESPONSE_UUID
                )

            if (
                control == null ||
                response == null
            ) {
                fail(
                    "Characteristics ESPhub incompletas."
                )
                return
            }

            update {
                it.copy(
                    phase =
                        SessionPhase.Subscribing,
                    status =
                        "Ativando notificações..."
                )
            }

            val responseChar =
                response ?: return

            val localEnabled =
                safeValue(false) {
                    activeGatt
                        .setCharacteristicNotification(
                            responseChar,
                            true
                        )
                }

            if (!localEnabled) {
                fail(
                    "Android recusou notificações BLE."
                )
                return
            }

            val descriptor =
                responseChar.getDescriptor(
                    Protocol.CCCD_UUID
                )

            if (descriptor == null) {
                fail(
                    "CCCD não encontrado."
                )
                return
            }

            val started =
                safeValue(false) {
                    if (
                        Build.VERSION.SDK_INT >=
                        33
                    ) {
                        activeGatt
                            .writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor
                                    .ENABLE_NOTIFICATION_VALUE
                            ) ==
                            BluetoothStatusCodes.SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value =
                            BluetoothGattDescriptor
                                .ENABLE_NOTIFICATION_VALUE

                        @Suppress("DEPRECATION")
                        activeGatt.writeDescriptor(
                            descriptor
                        )
                    }
                }

            if (!started) {
                fail(
                    "Falha ao escrever CCCD."
                )
                return
            }

            main.postDelayed({
                if (
                    token == generation &&
                    state.phase ==
                    SessionPhase.Subscribing
                ) {
                    fail(
                        "Timeout habilitando notificações."
                    )
                }
            }, Protocol.CCCD_TIMEOUT_MS)
        }

        private fun beginAuth() {
            if (
                masterKeyProvider() == null
            ) {
                fail(
                    "Chave mestre não configurada."
                )
                return
            }

            update {
                it.copy(
                    phase =
                        SessionPhase.Authenticating,
                    status =
                        "Autenticando..."
                )
            }

            sendText(
                "HELLO"
            )
        }

        private fun handleMessage(
            text: String
        ) {
            log(
                state.address.takeLast(5) +
                    " ← " +
                    text
            )

            runCatching {
                when {
                    text.startsWith(
                        "PROVISION_REQUIRED|"
                    ) ->
                        handleProvisionRequired(
                            text
                        )

                    text.startsWith(
                        "PROVISION_OK|"
                    ) ->
                        update {
                            it.copy(
                                phase =
                                    SessionPhase.Authenticating,
                                status =
                                    "Provisionado • autenticando..."
                            )
                        }

                    text.startsWith(
                        "PROVISION_LOCKED|"
                    ) ->
                        fail(
                            "Provisionamento bloqueado; reinicie o ESP."
                        )

                    text.startsWith(
                        "HELLO2|"
                    ) ->
                        handleHello2(
                            text
                        )

                    text.startsWith(
                        "HELLO|"
                    ) ->
                        handleLegacyHello(
                            text
                        )

                    text.startsWith(
                        "AUTH_OK|"
                    ) ->
                        handleAuthOk(
                            text
                        )

                    text.startsWith(
                        "AUTH_FAIL|"
                    ) ->
                        fail(
                            "Autenticação HMAC rejeitada."
                        )

                    text ==
                        "ERR|link_not_encrypted" ->
                        fail(
                            "Link BLE não está criptografado."
                        )

                    text.startsWith(
                        "INFO|"
                    ) ->
                        handleInfo(
                            text
                        )

                    text.startsWith(
                        "TEL|"
                    ) ->
                        handleTelemetry(
                            text
                        )

                    text.startsWith(
                        "KEY_MIGRATE_OK|"
                    ) -> {
                        legacySession = false
                        migrationRequested = false
                        update {
                            it.copy(
                                authenticated =
                                    false,
                                phase =
                                    SessionPhase.Authenticating,
                                status =
                                    "Credencial migrada • reautenticando..."
                            )
                        }
                    }

                    text.startsWith(
                        "KEY_MIGRATE_ERROR|"
                    ) -> {
                        migrationRequested = false
                        log(text)
                    }

                    text ==
                        "WIFI_SCAN_STARTED" ->
                        update {
                            it.copy(
                                wifiAccessPoints =
                                    emptyList(),
                                wifiScanState =
                                    "SCANNING"
                            )
                        }

                    text.startsWith(
                        "WIFI_SCAN_RESULT|"
                    ) ->
                        update {
                            it.copy(
                                wifiScanState =
                                    "RESULTS"
                            )
                        }

                    text.startsWith(
                        "WIFI_AP|"
                    ) ->
                        handleWifiAccessPoint(
                            text
                        )

                    text.startsWith(
                        "WIFI_SCAN_DONE|"
                    ) ->
                        update {
                            it.copy(
                                wifiScanState =
                                    "DONE"
                            )
                        }

                    text.startsWith(
                        "WIFI_SCAN_ERROR|"
                    ) -> {
                        val fields =
                            parseFields(text)

                        update {
                            it.copy(
                                wifiScanState =
                                    "ERROR",
                                lastError =
                                    fields["reason"]
                            )
                        }
                    }

                    text.startsWith(
                        "MONITOR_"
                    ) ->
                        handleMonitorMessage(
                            text
                        )

                    text ==
                        "LAB_WIFI_OK" ->
                        continueLabAfterWifi()

                    text.startsWith(
                        "LAB_"
                    ) ->
                        handleLabMessage(
                            text
                        )
                }
            }.onFailure {
                log(
                    "Resposta inválida: " +
                        it.javaClass.simpleName +
                        ": " +
                        (it.message ?: "")
                )
            }
        }

        private fun handleProvisionRequired(
            text: String
        ) {
            val master =
                masterKeyProvider()
                    ?: error(
                        "Chave mestre ausente."
                    )

            val parts =
                text.split("|")

            require(
                parts.size >= 3
            )

            val modern =
                parts.size >= 4

            val nodeId =
                if (modern) {
                    parts[2].trim()
                } else {
                    null
                }

            val nonceHex =
                if (modern) {
                    parts[3].trim()
                } else {
                    parts[2].trim()
                }

            val key =
                if (
                    nodeId.isNullOrBlank()
                ) {
                    master
                } else {
                    update {
                        it.copy(
                            nodeId = nodeId
                        )
                    }

                    CryptoStore
                        .deriveNodeKey(
                            master,
                            nodeId
                        )
                }

            sessionKey = key

            val proof =
                CryptoStore.bytesToHex(
                    CryptoStore
                        .hmacSha256(
                            key,
                            CryptoStore
                                .hexToBytes(
                                    nonceHex
                                )
                        )
                )

            sendText(
                "PROVISION|" +
                    CryptoStore
                        .bytesToHex(key) +
                    "|" +
                    proof
            )
        }

        private fun handleHello2(
            text: String
        ) {
            val master =
                masterKeyProvider()
                    ?: error(
                        "Chave mestre ausente."
                    )

            val parts =
                text.split("|")

            require(
                parts.size >= 3
            )

            val nodeId =
                parts[1].trim()

            val nonce =
                CryptoStore
                    .hexToBytes(
                        parts[2].trim()
                    )

            val key =
                CryptoStore
                    .deriveNodeKey(
                        master,
                        nodeId
                    )

            sessionKey = key
            legacySession = false

            update {
                it.copy(
                    nodeId = nodeId
                )
            }

            val mac =
                CryptoStore.bytesToHex(
                    CryptoStore
                        .hmacSha256(
                            key,
                            nonce
                        )
                )

            sendText(
                "AUTH|" +
                    mac
            )
        }

        private fun handleLegacyHello(
            text: String
        ) {
            val master =
                masterKeyProvider()
                    ?: error(
                        "Chave mestre ausente."
                    )

            val nonce =
                CryptoStore
                    .hexToBytes(
                        text.substringAfter(
                            "HELLO|"
                        ).trim()
                    )

            sessionKey = master
            legacySession = true

            val mac =
                CryptoStore.bytesToHex(
                    CryptoStore
                        .hmacSha256(
                            master,
                            nonce
                        )
                )

            sendText(
                "AUTH|" +
                    mac
            )
        }

        private fun handleAuthOk(
            text: String
        ) {
            val parts =
                text.split("|")

            update {
                it.copy(
                    authenticated = true,
                    phase =
                        SessionPhase.Ready,
                    status =
                        "Conectado e autenticado",
                    firmware =
                        parts.getOrNull(1)
                            ?: it.firmware,
                    nodeId =
                        parts.getOrNull(2)
                            ?.takeIf {
                                value ->
                                value.isNotBlank()
                            }
                            ?: it.nodeId,
                    lastError = null
                )
            }

            sendText("INFO")
            sendText("STATUS")
        }

        private fun handleInfo(
            text: String
        ) {
            val fields =
                parseFields(text)

            update {
                it.copy(
                    nodeId =
                        fields["node_id"]
                            ?: it.nodeId,
                    model =
                        fields["model"]
                            ?: it.model,
                    board =
                        fields["board"]
                            ?: it.board,
                    role =
                        fields["role"]
                            ?: it.role,
                    firmware =
                        fields["fw"]
                            ?: it.firmware,
                    capabilities =
                        fields["caps"]
                            ?.split(",")
                            ?.map {
                                cap ->
                                cap.trim()
                            }
                            ?.filter {
                                cap ->
                                cap.isNotEmpty()
                            }
                            ?.toSet()
                            ?: it.capabilities
                )
            }

            if (
                legacySession &&
                !migrationRequested
            ) {
                val master =
                    masterKeyProvider()

                val nodeId =
                    state.nodeId

                if (
                    master != null &&
                    !nodeId.isNullOrBlank()
                ) {
                    val derived =
                        CryptoStore
                            .deriveNodeKey(
                                master,
                                nodeId
                            )

                    migrationRequested = true

                    sendText(
                        "KEY_MIGRATE|" +
                            CryptoStore
                                .bytesToHex(
                                    derived
                                )
                    )
                }
            }
        }

        private fun handleTelemetry(
            text: String
        ) {
            val fields =
                parseFields(text)

            update {
                it.copy(
                    nodeId =
                        fields["node_id"]
                            ?: it.nodeId,
                    model =
                        fields["model"]
                            ?: it.model,
                    role =
                        fields["role"]
                            ?: it.role,
                    firmware =
                        fields["fw"]
                            ?: it.firmware
                )
            }
        }

        private fun handleWifiAccessPoint(
            text: String
        ) {
            val fields =
                parseFields(text)

            val bssid =
                fields["bssid"]
                    ?.trim()
                    ?.uppercase(
                        Locale.US
                    )
                    ?: return

            val ssid =
                fields["ssid64"]
                    ?.let {
                        encoded ->
                        runCatching {
                            String(
                                Base64.decode(
                                    encoded,
                                    Base64.NO_WRAP
                                ),
                                Charsets.UTF_8
                            )
                        }.getOrDefault("")
                    }
                    ?: ""

            val accessPoint =
                WifiAccessPoint(
                    ssid = ssid,
                    bssid = bssid,
                    channel =
                        fields["channel"]
                            ?.toIntOrNull()
                            ?: 0,
                    rssi =
                        fields["rssi"]
                            ?.toIntOrNull()
                            ?: -127,
                    encryptionCode =
                        fields["enc"]
                            ?.toIntOrNull()
                            ?: -1,
                    open =
                        fields["open"] ==
                            "1"
                )

            update {
                current ->
                val aps =
                    current
                        .wifiAccessPoints
                        .filterNot {
                            ap ->
                            ap.bssid
                                .equals(
                                    bssid,
                                    true
                                )
                        } +
                        accessPoint

                current.copy(
                    wifiAccessPoints =
                        aps.sortedByDescending {
                            it.rssi
                        },
                    wifiScanState =
                        "RESULTS"
                )
            }
        }

        private fun handleMonitorMessage(
            text: String
        ) {
            val fields =
                parseFields(text)

            val monitorState =
                when {
                    text.startsWith(
                        "MONITOR_STARTED|"
                    ) ->
                        "RUNNING"

                    text.startsWith(
                        "MONITOR_TEL|"
                    ) ->
                        "RUNNING"

                    text.startsWith(
                        "MONITOR_DONE|"
                    ) ->
                        "DONE"

                    text.startsWith(
                        "MONITOR_ERROR|"
                    ) ->
                        "ERROR"

                    text.startsWith(
                        "MONITOR_STATUS|"
                    ) ->
                        fields["state"]
                            ?: state.monitor.state

                    else ->
                        state.monitor.state
                }

            update {
                current ->
                current.copy(
                    monitor =
                        current.monitor.copy(
                            state =
                                monitorState,
                            channel =
                                fields["channel"]
                                    ?.toIntOrNull()
                                    ?: current.monitor.channel,
                            hopping =
                                fields["hopping"]
                                    ?.let {
                                        value ->
                                        value == "1"
                                    }
                                    ?: current.monitor.hopping,
                            elapsedMs =
                                fields["elapsed_ms"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.elapsedMs,
                            fps =
                                fields["fps"]
                                    ?.toIntOrNull()
                                    ?: current.monitor.fps,
                            rssiAvg =
                                fields["rssi_avg"]
                                    ?.toIntOrNull()
                                    ?: current.monitor.rssiAvg,
                            frames =
                                fields["frames"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.frames,
                            management =
                                fields["mgmt"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.management,
                            control =
                                fields["ctrl"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.control,
                            data =
                                fields["data"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.data,
                            beacon =
                                fields["beacon"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.beacon,
                            probeRequest =
                                fields["probe_req"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.probeRequest,
                            probeResponse =
                                fields["probe_resp"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.probeResponse,
                            auth =
                                fields["auth"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.auth,
                            assoc =
                                fields["assoc"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.assoc,
                            reassoc =
                                fields["reassoc"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.reassoc,
                            deauthSeen =
                                fields["deauth_seen"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.deauthSeen,
                            disassocSeen =
                                fields["disassoc_seen"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.disassocSeen,
                            eapol =
                                fields["eapol"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.eapol,
                            m1 =
                                fields["m1"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.m1,
                            m2 =
                                fields["m2"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.m2,
                            m3 =
                                fields["m3"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.m3,
                            m4 =
                                fields["m4"]
                                    ?.toLongOrNull()
                                    ?: current.monitor.m4,
                            reason =
                                fields["reason"]
                                    ?: current.monitor.reason
                        ),
                    lastError =
                        if (
                            monitorState ==
                            "ERROR"
                        ) {
                            fields["reason"]
                                ?: current.lastError
                        } else {
                            current.lastError
                        }
                )
            }
        }

        private fun continueLabAfterWifi() {
            val request =
                pendingLab ?: return

            val port =
                state.labDefaultPort

            sendText(
                "LAB_CONFIG|" +
                    request.targetIpv4 +
                    "|" +
                    port +
                    "|" +
                    request.level +
                    "|" +
                    request.durationSeconds
            )
        }

        private fun handleLabMessage(
            text: String
        ) {
            val fields =
                parseFields(text)

            val labState =
                when {
                    text.startsWith(
                        "LAB_STARTED|"
                    ) ->
                        "RUNNING"

                    text.startsWith(
                        "LAB_CONNECTING|"
                    ) ->
                        "CONNECTING"

                    text.startsWith(
                        "LAB_STOPPED|"
                    ) ->
                        "STOPPED"

                    text.startsWith(
                        "LAB_ERROR|"
                    ) ->
                        "ERROR"

                    else ->
                        fields["state"]
                            ?: state.lab.state
                }

            val metrics =
                state.lab.copy(
                    state =
                        labState,
                    level =
                        fields["level"]
                            ?.toIntOrNull()
                            ?: state.lab.level,
                    target =
                        fields["target"]
                            ?: state.lab.target,
                    port =
                        fields["port"]
                            ?.toIntOrNull()
                            ?: state.lab.port,
                    profilePps =
                        fields["profile_pps"]
                            ?.toIntOrNull()
                            ?: state.lab.profilePps,
                    packetBytes =
                        fields["packet_bytes"]
                            ?.toIntOrNull()
                            ?: state.lab.packetBytes,
                    txPackets =
                        fields["tx_packets"]
                            ?.toLongOrNull()
                            ?: state.lab.txPackets,
                    txBytes =
                        fields["tx_bytes"]
                            ?.toLongOrNull()
                            ?: state.lab.txBytes,
                    actualPps =
                        fields["actual_pps"]
                            ?.toIntOrNull()
                            ?: state.lab.actualPps,
                    elapsedMs =
                        fields["elapsed_ms"]
                            ?.toLongOrNull()
                            ?: state.lab.elapsedMs,
                    rssi =
                        fields["rssi"]
                            ?.toIntOrNull()
                            ?: state.lab.rssi,
                    reason =
                        fields["reason"]
                            ?: state.lab.reason
                )

            update {
                it.copy(
                    lab = metrics
                )
            }

            if (
                text.startsWith(
                    "LAB_STATUS|"
                ) &&
                labState.equals(
                    "CONFIGURED",
                    true
                ) &&
                pendingLab != null
            ) {
                sendText(
                    "LAB_START"
                )
            }

            if (
                text.startsWith(
                    "LAB_STARTED|"
                )
            ) {
                pendingLab = null
            }

            if (
                text.startsWith(
                    "LAB_ERROR|"
                )
            ) {
                pendingLab = null
            }
        }

        private fun pumpWrites() {
            if (
                writeInFlight ||
                writeQueue.isEmpty()
            ) {
                return
            }

            val activeGatt =
                gatt ?: return

            val characteristic =
                control ?: return

            val task =
                writeQueue.first()

            writeInFlight = true

            val status =
                safeValue(-1) {
                    if (
                        Build.VERSION.SDK_INT >=
                        33
                    ) {
                        activeGatt
                            .writeCharacteristic(
                                characteristic,
                                task.payload,
                                BluetoothGattCharacteristic
                                    .WRITE_TYPE_DEFAULT
                            )
                    } else {
                        @Suppress("DEPRECATION")
                        characteristic.writeType =
                            BluetoothGattCharacteristic
                                .WRITE_TYPE_DEFAULT

                        @Suppress("DEPRECATION")
                        characteristic.value =
                            task.payload

                        @Suppress("DEPRECATION")
                        if (
                            activeGatt
                                .writeCharacteristic(
                                    characteristic
                                )
                        ) {
                            0
                        } else {
                            -1
                        }
                    }
                }

            val started =
                if (
                    Build.VERSION.SDK_INT >=
                    33
                ) {
                    status ==
                        BluetoothStatusCodes.SUCCESS
                } else {
                    status == 0
                }

            if (!started) {
                writeInFlight = false
                retryWrite(
                    task,
                    status
                )
            }
        }

        private fun retryWrite(
            task: WriteTask,
            status: Int
        ) {
            task.attempts += 1

            if (
                task.attempts >=
                Protocol.MAX_WRITE_RETRIES
            ) {
                if (
                    writeQueue.isNotEmpty() &&
                    writeQueue.first() ===
                    task
                ) {
                    writeQueue.removeFirst()
                }

                log(
                    "Write falhou após retries: " +
                        status
                )

                pumpWrites()
                return
            }

            main.postDelayed({
                if (
                    writeQueue.isNotEmpty() &&
                    writeQueue.first() ===
                    task
                ) {
                    pumpWrites()
                }
            }, 180L * task.attempts)
        }

        private fun callbackFor(
            token: Int
        ): BluetoothGattCallback {
            return object :
                BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    bluetoothGatt:
                        BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {
                    onMain {
                        if (
                            token != generation
                        ) {
                            runCatching {
                                bluetoothGatt.close()
                            }
                            return@onMain
                        }

                        if (
                            status ==
                            BluetoothGatt.GATT_SUCCESS &&
                            newState ==
                            BluetoothProfile
                                .STATE_CONNECTED
                        ) {
                            gatt =
                                bluetoothGatt
                            connected = true

                            beginBonding(
                                bluetoothGatt,
                                token
                            )

                            return@onMain
                        }

                        if (
                            newState ==
                            BluetoothProfile
                                .STATE_DISCONNECTED
                        ) {
                            connected = false

                            val bondState =
                                safeValue(
                                    BluetoothDevice
                                        .BOND_NONE
                                ) {
                                    device.bondState
                                }

                            if (
                                state.phase ==
                                SessionPhase.Bonding &&
                                (
                                    bondState ==
                                    BluetoothDevice
                                        .BOND_BONDING ||
                                    bondState ==
                                    BluetoothDevice
                                        .BOND_BONDED
                                )
                            ) {
                                runCatching {
                                    bluetoothGatt.close()
                                }

                                if (
                                    gatt ===
                                    bluetoothGatt
                                ) {
                                    gatt = null
                                }

                                return@onMain
                            }

                            if (
                                state.phase !=
                                SessionPhase.Idle
                            ) {
                                fail(
                                    "BLE desconectou • status " +
                                        status
                                )
                            }
                        }
                    }
                }

                override fun onMtuChanged(
                    bluetoothGatt:
                        BluetoothGatt,
                    mtu: Int,
                    status: Int
                ) {
                    onMain {
                        if (
                            !isCurrent(
                                bluetoothGatt,
                                token
                            )
                        ) {
                            return@onMain
                        }

                        if (
                            state.phase ==
                            SessionPhase.Mtu
                        ) {
                            beginDiscovery(
                                bluetoothGatt,
                                token
                            )
                        }
                    }
                }

                override fun onServicesDiscovered(
                    bluetoothGatt:
                        BluetoothGatt,
                    status: Int
                ) {
                    onMain {
                        if (
                            !isCurrent(
                                bluetoothGatt,
                                token
                            )
                        ) {
                            return@onMain
                        }

                        if (
                            status !=
                            BluetoothGatt
                                .GATT_SUCCESS
                        ) {
                            fail(
                                "Falha ao descobrir serviços • " +
                                    status
                            )
                            return@onMain
                        }

                        subscribe(
                            bluetoothGatt,
                            token
                        )
                    }
                }

                override fun onDescriptorWrite(
                    bluetoothGatt:
                        BluetoothGatt,
                    descriptor:
                        BluetoothGattDescriptor,
                    status: Int
                ) {
                    onMain {
                        if (
                            !isCurrent(
                                bluetoothGatt,
                                token
                            ) ||
                            descriptor.uuid !=
                            Protocol.CCCD_UUID
                        ) {
                            return@onMain
                        }

                        if (
                            status ==
                            BluetoothGatt
                                .GATT_SUCCESS
                        ) {
                            beginAuth()
                        } else {
                            fail(
                                "Falha ativando CCCD • " +
                                    status
                            )
                        }
                    }
                }

                override fun onCharacteristicWrite(
                    bluetoothGatt:
                        BluetoothGatt,
                    characteristic:
                        BluetoothGattCharacteristic,
                    status: Int
                ) {
                    onMain {
                        if (
                            !isCurrent(
                                bluetoothGatt,
                                token
                            ) ||
                            characteristic.uuid !=
                            Protocol.CONTROL_UUID
                        ) {
                            return@onMain
                        }

                        val task =
                            if (
                                writeQueue
                                    .isEmpty()
                            ) {
                                null
                            } else {
                                writeQueue.first()
                            }

                        writeInFlight = false

                        if (task == null) {
                            return@onMain
                        }

                        if (
                            status ==
                            BluetoothGatt
                                .GATT_SUCCESS
                        ) {
                            writeQueue
                                .removeFirst()
                            pumpWrites()
                        } else {
                            retryWrite(
                                task,
                                status
                            )
                        }
                    }
                }

                override fun onCharacteristicChanged(
                    bluetoothGatt:
                        BluetoothGatt,
                    characteristic:
                        BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    onMain {
                        if (
                            !isCurrent(
                                bluetoothGatt,
                                token
                            ) ||
                            characteristic.uuid !=
                            Protocol.RESPONSE_UUID
                        ) {
                            return@onMain
                        }

                        handleMessage(
                            value.toString(
                                StandardCharsets.UTF_8
                            )
                        )
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    bluetoothGatt:
                        BluetoothGatt,
                    characteristic:
                        BluetoothGattCharacteristic
                ) {
                    if (
                        Build.VERSION.SDK_INT >=
                        33
                    ) {
                        return
                    }

                    onMain {
                        if (
                            !isCurrent(
                                bluetoothGatt,
                                token
                            ) ||
                            characteristic.uuid !=
                            Protocol.RESPONSE_UUID
                        ) {
                            return@onMain
                        }

                        val bytes =
                            characteristic.value
                                ?: return@onMain

                        handleMessage(
                            bytes.toString(
                                StandardCharsets.UTF_8
                            )
                        )
                    }
                }
            }
        }

        private fun isCurrent(
            activeGatt: BluetoothGatt,
            token: Int
        ): Boolean =
            token == generation &&
                gatt === activeGatt

        private fun clearGatt() {
            connected = false
            control = null
            response = null
            writeQueue.clear()
            writeInFlight = false

            val old =
                gatt

            gatt = null

            if (old != null) {
                safeUnit {
                    old.disconnect()
                }

                safeUnit {
                    old.close()
                }
            }
        }

        private fun fail(
            message: String
        ) {
            update {
                it.copy(
                    phase =
                        SessionPhase.Error,
                    status = message,
                    authenticated = false,
                    lastError = message
                )
            }

            log(
                state.address.takeLast(5) +
                    " ERROR: " +
                    message
            )
        }

        private fun update(
            transform:
                (NodeState) ->
                NodeState
        ) {
            state =
                transform(state)
                    .copy(
                        address =
                            normalize(
                                state.address
                            )
                    )

            listener.onNodeChanged(
                state
            )
        }

        private fun <T> safeValue(
            fallback: T,
            block: () -> T
        ): T =
            runCatching(block)
                .getOrDefault(
                    fallback
                )

        private fun safeUnit(
            block: () -> Unit
        ) {
            runCatching(block)
        }
    }

    private data class LabRequest(
        val ssid: String,
        val password: String,
        val targetIpv4: String,
        val level: Int,
        val durationSeconds: Int
    )

    private fun parseFields(
        message: String
    ): Map<String, String> =
        buildMap {
            message
                .split("|")
                .drop(1)
                .forEach {
                    field ->
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

    private fun normalize(
        address: String
    ): String =
        address.trim()
            .uppercase(
                Locale.US
            )

    private fun onMain(
        block: () -> Unit
    ) {
        if (
            Looper.myLooper() ==
            Looper.getMainLooper()
        ) {
            runCatching(block)
                .onFailure {
                    log(
                        "Exceção BLE contida: " +
                            it.javaClass.simpleName +
                            ": " +
                            (it.message ?: "")
                    )
                }
        } else {
            main.post {
                runCatching(block)
                    .onFailure {
                        log(
                            "Exceção BLE contida: " +
                                it.javaClass.simpleName +
                                ": " +
                                (it.message ?: "")
                        )
                    }
            }
        }
    }

    private fun log(
        text: String
    ) {
        val stamp =
            SimpleDateFormat(
                "HH:mm:ss",
                Locale.US
            ).format(
                Date()
            )

        listener.onLog(
            "[$stamp] " +
                text
        )
    }
}
