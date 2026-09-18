package com.lendas.privatelink.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lendas.privatelink.PrivateLinkApplication
import com.lendas.privatelink.ble.PrivateLinkBleManager
import com.lendas.privatelink.core.FastOtaCredentials
import com.lendas.privatelink.core.LinkState
import com.lendas.privatelink.core.NearbyDevice
import com.lendas.privatelink.core.NodeInfo
import com.lendas.privatelink.core.NodeState
import com.lendas.privatelink.core.OtaTransport
import com.lendas.privatelink.core.PrivateLinkUiState
import com.lendas.privatelink.core.Telemetry
import com.lendas.privatelink.security.SecureKeyStore
import com.lendas.privatelink.wifi.FastOtaManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivateLinkViewModel(application: Application) :
    AndroidViewModel(application),
    FastOtaManager.Listener {

    private val secureKeyStore = SecureKeyStore(application)

    private val startupCrash =
        PrivateLinkApplication.consumeLastCrash(
            application
        )

    private val previousExit =
        PrivateLinkApplication.consumePreviousExit(
            application
        )

    private val _state = MutableStateFlow(
        PrivateLinkUiState(
            hasPrivateKey = secureKeyStore.hasKey(),
            lastError =
                if (startupCrash != null)
                    "O aplicativo fechou inesperadamente na sessão anterior."
                else
                    null,
            logs = buildList {
                if (previousExit != null) {
                    add("DIAGNÓSTICO DE SAÍDA ANTERIOR:")
                    add(previousExit)
                }
                if (startupCrash != null) {
                    add("CRASH DA SESSÃO ANTERIOR:")
                    add(startupCrash)
                }
                add("Pronto.")
            }
        )
    )

    val state: StateFlow<PrivateLinkUiState> =
        _state.asStateFlow()

    private val sessions =
        linkedMapOf<String, PrivateLinkBleManager>()

    private val discovery =
        PrivateLinkBleManager(
            application,
            DiscoveryListener(),
            enableBondReceiver = false
        ).also {
            it.setPrivateKey(
                secureKeyStore.loadKey()
            )
        }

    private val fastOta =
        FastOtaManager(
            application,
            this
        )

    private var pendingFirmware: ByteArray? = null
    private var pendingFirmwareName: String? = null
    private var otaTargetAddress: String? = null

    fun savePrivateKey(hex: String): Result<Unit> {
        return runCatching {
            require(
                hex.matches(
                    Regex("(?i)[0-9a-f]{64}")
                )
            ) {
                "Use uma chave hexadecimal de 64 caracteres."
            }

            secureKeyStore.saveKey(hex)
            val key = secureKeyStore.loadKey()

            discovery.setPrivateKey(key)
            sessions.values.forEach {
                it.setPrivateKey(key)
            }

            _state.value = _state.value.copy(
                hasPrivateKey = true,
                lastError = null
            )

            addLog(
                "Chave mestre protegida pelo Android Keystore."
            )
        }
    }

    fun clearPrivateKey() {
        fastOta.cancel(silent = true)
        sessions.values.forEach {
            it.close()
        }
        sessions.clear()

        discovery.stopScan()
        secureKeyStore.clearKey()
        discovery.setPrivateKey(null)

        clearPendingFirmware()

        _state.value = PrivateLinkUiState(
            hasPrivateKey = false,
            statusText = "Chave privada removida",
            logs = _state.value.logs
        )

        addLog(
            "Chave privada removida deste aparelho."
        )
    }

    fun startScan() {
        _state.value = _state.value.copy(
            scanning = true,
            statusText = "Procurando nós PrivateLink..."
        )

        discovery.startScan()
    }

    fun stopScan() {
        discovery.stopScan()
        _state.value = _state.value.copy(
            scanning = false
        )
    }

    fun connect(device: NearbyDevice) {
        discovery.stopScan()
        _state.value = _state.value.copy(
            scanning = false,
            statusText = "Abrindo sessão com o nó..."
        )

        val address = device.address
        val manager = sessions.getOrPut(
            address
        ) {
            PrivateLinkBleManager(
                getApplication(),
                NodeListener(address)
            ).also {
                it.setPrivateKey(
                    secureKeyStore.loadKey()
                )
            }
        }

        updateNode(address) {
            it.copy(
                rssi = device.rssi,
                linkState = LinkState.Connecting,
                statusText = "Conectando...",
                lastError = null
            )
        }

        _state.value = _state.value.copy(
            selectedNodeKey = address
        )

        manager.connect(device)
    }

    fun disconnect(address: String) {
        if (otaTargetAddress == address) {
            fastOta.cancel(silent = true)
            clearPendingFirmware()
        }

        sessions[address]?.disconnect()

        updateNode(address) {
            it.copy(
                linkState = LinkState.Idle,
                statusText = "Desconectado",
                authenticated = false,
                otaTransport = OtaTransport.None
            )
        }
    }

    fun disconnectAll() {
        fastOta.cancel(silent = true)
        clearPendingFirmware()

        sessions.forEach { (address, manager) ->
            manager.disconnect()
            updateNode(address) {
                it.copy(
                    linkState = LinkState.Idle,
                    statusText = "Desconectado",
                    authenticated = false
                )
            }
        }
    }

    fun selectNode(address: String) {
        _state.value = _state.value.copy(
            selectedNodeKey = address
        )
    }

    fun sendCommand(
        address: String,
        command: String
    ) {
        sessions[address]?.sendCommand(command)
    }

    fun startSmartOta(
        uri: Uri,
        address: String
    ) {
        viewModelScope.launch {
            try {
                val node = nodeByAddress(address)
                    ?: error("Nó não encontrado.")

                require(node.authenticated) {
                    "Conecte e autentique o nó primeiro."
                }

                updateNode(address) {
                    it.copy(
                        linkState = LinkState.PreparingFastOta,
                        statusText = "Lendo e validando firmware...",
                        otaProgress = 0f,
                        otaTransport = OtaTransport.None,
                        otaSpeedBytesPerSecond = null,
                        otaTransferredBytes = 0L,
                        otaTotalBytes = 0L,
                        lastError = null
                    )
                }

                val payload =
                    withContext(Dispatchers.IO) {
                        getApplication<Application>()
                            .contentResolver
                            .openInputStream(uri)
                            ?.use { it.readBytes() }
                            ?: error(
                                "Não foi possível abrir o arquivo."
                            )
                    }

                val fileName =
                    resolveFileName(uri)

                require(payload.isNotEmpty()) {
                    "Firmware vazio."
                }

                validateFirmwareTarget(
                    node,
                    fileName
                )

                pendingFirmware = payload
                pendingFirmwareName = fileName
                otaTargetAddress = address

                updateNode(address) {
                    it.copy(
                        otaFileName = fileName,
                        otaTotalBytes =
                            payload.size.toLong(),
                        statusText =
                            "Negociando canal de atualização..."
                    )
                }

                addLog(
                    address,
                    "Firmware preparado: $fileName • " +
                        "${payload.size} bytes."
                )

                sessions[address]
                    ?.requestFastOta()
                    ?: error(
                        "Sessão BLE não disponível."
                    )
            } catch (error: Exception) {
                clearPendingFirmware()

                updateNode(address) {
                    it.copy(
                        linkState =
                            if (it.authenticated)
                                LinkState.Ready
                            else
                                LinkState.Error,
                        statusText =
                            "Falha ao preparar firmware",
                        otaTransport =
                            OtaTransport.None,
                        lastError =
                            error.message
                    )
                }

                addLog(
                    address,
                    "OTA: ${error.message}"
                )
            }
        }
    }

    fun abortOta(address: String) {
        val node = nodeByAddress(address)
            ?: return

        when (node.otaTransport) {
            OtaTransport.WifiFast -> {
                fastOta.cancel(silent = true)
                sessions[address]
                    ?.cancelFastOtaSession()
            }

            OtaTransport.Ble -> {
                sessions[address]
                    ?.abortOta()
            }

            OtaTransport.None -> Unit
        }

        clearPendingFirmware()

        updateNode(address) {
            it.copy(
                linkState =
                    if (it.authenticated)
                        LinkState.Ready
                    else
                        LinkState.Idle,
                statusText =
                    if (it.authenticated)
                        "Canal privado autenticado"
                    else
                        "Atualização cancelada",
                otaTransport =
                    OtaTransport.None,
                otaProgress = 0f,
                otaSpeedBytesPerSecond = null,
                otaTransferredBytes = 0L,
                otaTotalBytes = 0L
            )
        }

        addLog(
            address,
            "Atualização cancelada."
        )
    }

    private fun startBleFallback(
        address: String
    ) {
        val payload =
            pendingFirmware ?: return

        val fileName =
            pendingFirmwareName
                ?: "firmware.bin"

        updateNode(address) {
            it.copy(
                linkState = LinkState.Updating,
                statusText =
                    "Atualizando por BLE compatível...",
                otaTransport =
                    OtaTransport.Ble,
                otaProgress = 0f,
                otaSpeedBytesPerSecond = null,
                otaTransferredBytes = 0L,
                otaTotalBytes =
                    payload.size.toLong()
            )
        }

        addLog(
            address,
            "Usando transporte BLE compatível."
        )

        sessions[address]?.startOta(
            payload,
            fileName
        )
    }

    private fun validateFirmwareTarget(
        node: NodeState,
        fileName: String
    ) {
        val normalized =
            fileName.uppercase()

        val model =
            node.info.model
                ?.uppercase()
                ?: node.telemetry.model
                    ?.uppercase()
                ?: ""

        if (
            model.contains("C6") &&
            normalized.contains("S3")
        ) {
            error(
                "Este firmware é do ESP32-S3, mas o nó selecionado é C6."
            )
        }

        if (
            model.contains("S3") &&
            normalized.contains("C6")
        ) {
            error(
                "Este firmware é do ESP32-C6, mas o nó selecionado é S3."
            )
        }

        if (
            normalized.contains("FULL")
        ) {
            error(
                "Imagens FULL devem ser gravadas por USB, não por OTA."
            )
        }
    }

    private suspend fun resolveFileName(
        uri: Uri
    ): String =
        withContext(Dispatchers.IO) {
            val resolver =
                getApplication<Application>()
                    .contentResolver

            resolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index =
                        cursor.getColumnIndex(
                            OpenableColumns.DISPLAY_NAME
                        )

                    if (index >= 0) {
                        return@withContext cursor
                            .getString(index)
                    }
                }
            }

            "firmware.bin"
        }

    private fun handleFastOtaReady(
        address: String,
        credentials: FastOtaCredentials
    ) {
        val payload =
            pendingFirmware

        val fileName =
            pendingFirmwareName

        val sessionKey =
            sessions[address]
                ?.currentSessionKey()

        if (
            otaTargetAddress != address ||
            payload == null ||
            fileName == null ||
            sessionKey == null
        ) {
            sessions[address]
                ?.cancelFastOtaSession()

            handleFastOtaUnavailable(
                address,
                "Dados da atualização rápida não estão disponíveis."
            )
            return
        }

        updateNode(address) {
            it.copy(
                linkState =
                    LinkState.ConnectingFastOta,
                statusText =
                    "Conectando ao Wi-Fi privado temporário...",
                otaTransport =
                    OtaTransport.WifiFast,
                otaProgress = 0f,
                otaSpeedBytesPerSecond = null,
                otaTransferredBytes = 0L,
                otaTotalBytes =
                    payload.size.toLong()
            )
        }

        addLog(
            address,
            "Fast OTA negociada • canal " +
                "${credentials.channel}."
        )

        fastOta.start(
            credentials = credentials,
            firmware = payload,
            fileName = fileName,
            privateKey = sessionKey
        )
    }

    private fun handleFastOtaUnavailable(
        address: String,
        reason: String
    ) {
        addLog(address, reason)

        if (
            otaTargetAddress == address &&
            pendingFirmware != null
        ) {
            startBleFallback(address)
        } else {
            updateNode(address) {
                it.copy(
                    linkState = LinkState.Ready,
                    statusText =
                        "Canal privado autenticado",
                    otaTransport =
                        OtaTransport.None
                )
            }
        }
    }

    private fun updateNode(
        address: String,
        transform: (NodeState) -> NodeState
    ) {
        val current =
            _state.value.nodes

        val index =
            current.indexOfFirst {
                it.address == address
            }

        val base =
            if (index >= 0) {
                current[index]
            } else {
                NodeState(address = address)
            }

        val updated =
            transform(base)

        val nodes =
            current.toMutableList()

        if (index >= 0) {
            nodes[index] = updated
        } else {
            nodes.add(updated)
        }

        _state.value =
            _state.value.copy(
                nodes = nodes.sortedWith(
                    compareByDescending<NodeState> {
                        it.authenticated
                    }.thenBy {
                        it.displayName
                    }
                )
            )
    }

    private fun nodeByAddress(
        address: String
    ): NodeState? =
        _state.value.nodes.firstOrNull {
            it.address == address
        }

    private fun addLog(
        message: String
    ) {
        val updated = buildList {
            add(message)
            addAll(_state.value.logs)
        }.take(300)

        _state.value =
            _state.value.copy(
                logs = updated
            )
    }

    private fun addLog(
        address: String,
        message: String
    ) {
        val shortAddress =
            address.takeLast(5)

        addLog(
            "[$shortAddress] $message"
        )
    }

    override fun onWifiRequestStarted() {
        val address =
            otaTargetAddress ?: return

        updateNode(address) {
            it.copy(
                linkState =
                    LinkState.ConnectingFastOta,
                statusText =
                    "Aguardando conexão Wi-Fi privada..."
            )
        }

        addLog(
            address,
            "Android recebeu as credenciais temporárias."
        )
    }

    override fun onWifiConnected(ssid: String) {
        val address =
            otaTargetAddress ?: return

        updateNode(address) {
            it.copy(
                linkState = LinkState.Updating,
                statusText =
                    "Canal rápido estabelecido • enviando firmware",
                otaTransport =
                    OtaTransport.WifiFast
            )
        }

        addLog(
            address,
            "Wi-Fi privado conectado."
        )
    }

    override fun onProgress(
        transferred: Long,
        total: Long,
        bytesPerSecond: Long
    ) {
        val address =
            otaTargetAddress ?: return

        updateNode(address) {
            it.copy(
                linkState = LinkState.Updating,
                statusText =
                    "Transferindo firmware por Wi-Fi...",
                otaTransport =
                    OtaTransport.WifiFast,
                otaProgress =
                    if (total <= 0L) 0f
                    else (
                        transferred.toDouble() /
                            total.toDouble()
                        ).toFloat()
                        .coerceIn(0f, 1f),
                otaTransferredBytes =
                    transferred,
                otaTotalBytes = total,
                otaSpeedBytesPerSecond =
                    bytesPerSecond
            )
        }
    }

    override fun onVerifying() {
        val address =
            otaTargetAddress ?: return

        updateNode(address) {
            it.copy(
                linkState = LinkState.Verifying,
                statusText =
                    "Validando SHA-256 + HMAC..."
            )
        }

        addLog(
            address,
            "Transferência concluída; nó validando imagem."
        )
    }

    override fun onSuccess() {
        val address =
            otaTargetAddress ?: return

        updateNode(address) {
            it.copy(
                linkState = LinkState.Ready,
                statusText =
                    "OTA concluída • nó reiniciando",
                otaProgress = 1f
            )
        }

        addLog(
            address,
            "Fast OTA validada com sucesso."
        )

        clearPendingFirmware()
    }

    override fun onFailure(message: String) {
        val address =
            otaTargetAddress ?: return

        sessions[address]
            ?.cancelFastOtaSession()

        updateNode(address) {
            it.copy(
                linkState =
                    if (it.authenticated)
                        LinkState.Ready
                    else
                        LinkState.Error,
                statusText =
                    if (it.authenticated)
                        "Fast OTA interrompida"
                    else
                        "Falha de atualização",
                lastError = message
            )
        }

        addLog(
            address,
            "Fast OTA: $message"
        )
    }

    override fun onFinished() {
        // FastOtaManager já encerra a rede temporária.
    }

    private fun clearPendingFirmware() {
        pendingFirmware = null
        pendingFirmwareName = null
        otaTargetAddress = null
    }

    override fun onCleared() {
        fastOta.cancel(silent = true)
        discovery.close()

        sessions.values.forEach {
            it.close()
        }

        sessions.clear()

        super.onCleared()
    }

    private inner class DiscoveryListener :
        PrivateLinkBleManager.Listener {

        override fun onLinkState(
            state: LinkState,
            text: String
        ) {
            _state.value =
                _state.value.copy(
                    scanning =
                        state == LinkState.Scanning,
                    statusText = text,
                    lastError =
                        if (state == LinkState.Error)
                            text
                        else
                            null
                )
        }

        override fun onDevices(
            devices: List<NearbyDevice>
        ) {
            val connectedAddresses =
                sessions.keys

            _state.value =
                _state.value.copy(
                    devices = devices.filterNot {
                        it.address in connectedAddresses
                    }
                )
        }

        override fun onLog(message: String) {
            addLog("[SCAN] $message")
        }

        override fun onAuthenticated(
            authenticated: Boolean
        ) = Unit

        override fun onTelemetry(
            telemetry: Telemetry
        ) = Unit

        override fun onNodeInfo(
            info: NodeInfo
        ) = Unit

        override fun onOtaProgress(
            progress: Float,
            fileName: String?
        ) = Unit

        override fun onConnectedAddress(
            address: String?
        ) = Unit

        override fun onFastOtaReady(
            credentials: FastOtaCredentials
        ) = Unit

        override fun onFastOtaUnavailable(
            reason: String
        ) = Unit
    }

    private inner class NodeListener(
        private val address: String
    ) : PrivateLinkBleManager.Listener {

        override fun onLinkState(
            state: LinkState,
            text: String
        ) {
            updateNode(address) {
                it.copy(
                    linkState = state,
                    statusText = text,
                    lastError =
                        if (state == LinkState.Error)
                            text
                        else
                            null
                )
            }
        }

        override fun onDevices(
            devices: List<NearbyDevice>
        ) = Unit

        override fun onLog(message: String) {
            addLog(address, message)
        }

        override fun onAuthenticated(
            authenticated: Boolean
        ) {
            updateNode(address) {
                it.copy(
                    authenticated =
                        authenticated
                )
            }
        }

        override fun onTelemetry(
            telemetry: Telemetry
        ) {
            updateNode(address) { old ->
                val mergedInfo =
                    old.info.copy(
                        nodeId =
                            telemetry.nodeId
                                ?: old.info.nodeId,
                        model =
                            telemetry.model
                                ?: old.info.model,
                        role =
                            telemetry.role
                                ?: old.info.role,
                        firmware =
                            if (
                                telemetry.firmware != "-"
                            ) telemetry.firmware
                            else old.info.firmware,
                        flashBytes =
                            telemetry.flashBytes
                                ?: old.info.flashBytes
                    )

                old.copy(
                    telemetry = telemetry,
                    info = mergedInfo
                )
            }
        }

        override fun onNodeInfo(
            info: NodeInfo
        ) {
            updateNode(address) {
                it.copy(
                    info = info
                )
            }
        }

        override fun onOtaProgress(
            progress: Float,
            fileName: String?
        ) {
            val total =
                nodeByAddress(address)
                    ?.otaTotalBytes
                    ?: 0L

            updateNode(address) {
                it.copy(
                    otaProgress =
                        progress.coerceIn(
                            0f,
                            1f
                        ),
                    otaFileName =
                        fileName
                            ?: it.otaFileName,
                    otaTransport =
                        if (
                            it.otaTransport ==
                            OtaTransport.None
                        ) OtaTransport.Ble
                        else it.otaTransport,
                    otaTransferredBytes =
                        (
                            total.toDouble() *
                                progress
                                    .coerceIn(
                                        0f,
                                        1f
                                    )
                            ).toLong()
                )
            }

            if (progress >= 1f) {
                clearPendingFirmware()
            }
        }

        override fun onConnectedAddress(
            connectedAddress: String?
        ) {
            if (connectedAddress == null) {
                updateNode(address) {
                    it.copy(
                        authenticated = false
                    )
                }
            }
        }

        override fun onFastOtaReady(
            credentials: FastOtaCredentials
        ) {
            handleFastOtaReady(
                address,
                credentials
            )
        }

        override fun onFastOtaUnavailable(
            reason: String
        ) {
            handleFastOtaUnavailable(
                address,
                reason
            )
        }
    }
}
