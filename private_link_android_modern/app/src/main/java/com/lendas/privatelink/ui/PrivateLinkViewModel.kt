package com.lendas.privatelink.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lendas.privatelink.ble.PrivateLinkBleManager
import com.lendas.privatelink.core.Crypto
import com.lendas.privatelink.core.FastOtaCredentials
import com.lendas.privatelink.core.LinkState
import com.lendas.privatelink.core.NearbyDevice
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
    PrivateLinkBleManager.Listener,
    FastOtaManager.Listener {

    private val secureKeyStore = SecureKeyStore(application)

    private val _state = MutableStateFlow(
        PrivateLinkUiState(
            hasPrivateKey = secureKeyStore.hasKey()
        )
    )

    val state: StateFlow<PrivateLinkUiState> = _state.asStateFlow()

    private val ble = PrivateLinkBleManager(application, this).also {
        it.setPrivateKey(secureKeyStore.loadKey())
    }

    private val fastOta = FastOtaManager(application, this)

    private var pendingFirmware: ByteArray? = null
    private var pendingFirmwareName: String? = null

    fun savePrivateKey(hex: String): Result<Unit> {
        return runCatching {
            require(hex.matches(Regex("(?i)[0-9a-f]{64}"))) {
                "Use uma chave hexadecimal de 64 caracteres."
            }

            secureKeyStore.saveKey(hex)
            ble.setPrivateKey(Crypto.hexToBytes(hex))

            _state.value = _state.value.copy(
                hasPrivateKey = true,
                lastError = null
            )

            addLog("Chave privada protegida pelo Android Keystore.")
        }
    }

    fun clearPrivateKey() {
        fastOta.cancel(silent = true)
        ble.disconnect()
        secureKeyStore.clearKey()
        ble.setPrivateKey(null)
        clearPendingFirmware()

        _state.value = _state.value.copy(
            hasPrivateKey = false,
            authenticated = false,
            connectedAddress = null,
            linkState = LinkState.Idle,
            statusText = "Chave privada removida",
            otaTransport = OtaTransport.None
        )

        addLog("Chave privada removida deste aparelho.")
    }

    fun startScan() = ble.startScan()

    fun stopScan() = ble.stopScan()

    fun connect(device: NearbyDevice) = ble.connect(device)

    fun disconnect() {
        fastOta.cancel(silent = true)
        ble.disconnect()
    }

    fun sendCommand(command: String) = ble.sendCommand(command)

    fun abortOta() {
        when (_state.value.otaTransport) {
            OtaTransport.WifiFast -> {
                fastOta.cancel(silent = true)
                ble.cancelFastOtaSession()
                clearPendingFirmware()

                _state.value = _state.value.copy(
                    linkState =
                        if (_state.value.authenticated) LinkState.Ready
                        else LinkState.Idle,
                    statusText =
                        if (_state.value.authenticated)
                            "Canal privado autenticado"
                        else
                            "Atualização cancelada",
                    otaTransport = OtaTransport.None,
                    otaProgress = 0f,
                    otaSpeedBytesPerSecond = null,
                    otaTransferredBytes = 0L,
                    otaTotalBytes = 0L
                )

                addLog("Fast OTA cancelada pelo usuário.")
            }

            OtaTransport.Ble -> {
                ble.abortOta()
                clearPendingFirmware()
            }

            OtaTransport.None -> Unit
        }
    }

    fun startSmartOta(uri: Uri) {
        viewModelScope.launch {
            try {
                require(_state.value.authenticated) {
                    "Conecte e autentique o ESP32-S3 primeiro."
                }

                _state.value = _state.value.copy(
                    linkState = LinkState.PreparingFastOta,
                    statusText = "Lendo e validando firmware...",
                    otaProgress = 0f,
                    otaTransport = OtaTransport.None,
                    otaSpeedBytesPerSecond = null,
                    otaTransferredBytes = 0L,
                    otaTotalBytes = 0L,
                    lastError = null
                )

                val payload = withContext(Dispatchers.IO) {
                    getApplication<Application>()
                        .contentResolver
                        .openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: error("Não foi possível abrir o arquivo.")
                }

                val fileName = resolveFileName(uri)

                require(payload.isNotEmpty()) {
                    "Firmware vazio."
                }

                pendingFirmware = payload
                pendingFirmwareName = fileName

                _state.value = _state.value.copy(
                    otaFileName = fileName,
                    otaTotalBytes = payload.size.toLong(),
                    statusText = "Negociando canal de atualização rápida..."
                )

                addLog(
                    "Firmware preparado: $fileName • ${payload.size} bytes."
                )

                ble.requestFastOta()
            } catch (error: Exception) {
                clearPendingFirmware()

                _state.value = _state.value.copy(
                    linkState =
                        if (_state.value.authenticated) LinkState.Ready
                        else LinkState.Error,
                    statusText = "Falha ao preparar firmware",
                    otaTransport = OtaTransport.None,
                    lastError = error.message
                )

                addLog("OTA: ${error.message}")
            }
        }
    }

    fun startBleFallback() {
        val payload = pendingFirmware ?: return
        val fileName = pendingFirmwareName ?: "firmware.bin"

        _state.value = _state.value.copy(
            linkState = LinkState.Updating,
            statusText = "Atualizando por BLE compatível...",
            otaTransport = OtaTransport.Ble,
            otaProgress = 0f,
            otaSpeedBytesPerSecond = null,
            otaTransferredBytes = 0L,
            otaTotalBytes = payload.size.toLong()
        )

        addLog("Usando transporte BLE compatível.")
        ble.startOta(payload, fileName)
    }

    private suspend fun resolveFileName(uri: Uri): String =
        withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver

            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME
                    )

                    if (index >= 0) {
                        return@withContext cursor.getString(index)
                    }
                }
            }

            "firmware.bin"
        }

    override fun onLinkState(state: LinkState, text: String) {
        _state.value = _state.value.copy(
            linkState = state,
            statusText = text,
            lastError = if (state == LinkState.Error) text else null
        )
    }

    override fun onDevices(devices: List<NearbyDevice>) {
        _state.value = _state.value.copy(devices = devices)
    }

    override fun onLog(message: String) {
        addLog(message)
    }

    override fun onAuthenticated(authenticated: Boolean) {
        _state.value = _state.value.copy(authenticated = authenticated)
    }

    override fun onTelemetry(telemetry: Telemetry) {
        _state.value = _state.value.copy(telemetry = telemetry)
    }

    override fun onOtaProgress(progress: Float, fileName: String?) {
        val total = _state.value.otaTotalBytes
        val transferred =
            (total.toDouble() * progress.coerceIn(0f, 1f)).toLong()

        _state.value = _state.value.copy(
            otaProgress = progress.coerceIn(0f, 1f),
            otaFileName = fileName ?: _state.value.otaFileName,
            otaTransport =
                if (_state.value.otaTransport == OtaTransport.None)
                    OtaTransport.Ble
                else
                    _state.value.otaTransport,
            otaTransferredBytes = transferred
        )

        if (progress >= 1f) {
            clearPendingFirmware()
        }
    }

    override fun onConnectedAddress(address: String?) {
        _state.value = _state.value.copy(connectedAddress = address)
    }

    override fun onFastOtaReady(credentials: FastOtaCredentials) {
        val payload = pendingFirmware
        val fileName = pendingFirmwareName
        val key = secureKeyStore.loadKey()

        if (
            payload == null ||
            fileName == null ||
            key == null
        ) {
            ble.cancelFastOtaSession()
            onFastOtaUnavailable(
                "Dados da atualização rápida não estão disponíveis."
            )
            return
        }

        _state.value = _state.value.copy(
            linkState = LinkState.ConnectingFastOta,
            statusText = "Conectando ao Wi‑Fi privado temporário...",
            otaTransport = OtaTransport.WifiFast,
            otaProgress = 0f,
            otaSpeedBytesPerSecond = null,
            otaTransferredBytes = 0L,
            otaTotalBytes = payload.size.toLong()
        )

        addLog(
            "Fast OTA negociada pelo BLE • canal ${credentials.channel}."
        )

        fastOta.start(
            credentials = credentials,
            firmware = payload,
            fileName = fileName,
            privateKey = key
        )
    }

    override fun onFastOtaUnavailable(reason: String) {
        addLog(reason)

        if (pendingFirmware != null) {
            startBleFallback()
        } else {
            _state.value = _state.value.copy(
                linkState = LinkState.Ready,
                statusText = "Canal privado autenticado",
                otaTransport = OtaTransport.None
            )
        }
    }

    override fun onWifiRequestStarted() {
        _state.value = _state.value.copy(
            linkState = LinkState.ConnectingFastOta,
            statusText = "Aguardando conexão Wi‑Fi privada..."
        )

        addLog(
            "Android recebeu SSID/senha pelo BLE. Nenhuma senha precisa ser digitada."
        )
    }

    override fun onWifiConnected(ssid: String) {
        _state.value = _state.value.copy(
            linkState = LinkState.Updating,
            statusText = "Canal rápido estabelecido • enviando firmware",
            otaTransport = OtaTransport.WifiFast
        )

        addLog("Wi‑Fi privado conectado: sessão temporária ativa.")
    }

    override fun onProgress(
        transferred: Long,
        total: Long,
        bytesPerSecond: Long
    ) {
        _state.value = _state.value.copy(
            linkState = LinkState.Updating,
            statusText = "Transferindo firmware por Wi‑Fi...",
            otaTransport = OtaTransport.WifiFast,
            otaProgress =
                if (total <= 0L) 0f
                else (transferred.toDouble() / total.toDouble())
                    .toFloat()
                    .coerceIn(0f, 1f),
            otaTransferredBytes = transferred,
            otaTotalBytes = total,
            otaSpeedBytesPerSecond = bytesPerSecond
        )
    }

    override fun onVerifying() {
        _state.value = _state.value.copy(
            linkState = LinkState.Verifying,
            statusText = "Validando SHA‑256 + HMAC no ESP32-S3..."
        )

        addLog("Transferência concluída; ESP32 validando imagem.")
    }

    override fun onSuccess() {
        _state.value = _state.value.copy(
            linkState = LinkState.Ready,
            statusText = "OTA concluída • ESP32-S3 reiniciando",
            otaProgress = 1f
        )

        addLog("Fast OTA validada com sucesso.")
        clearPendingFirmware()
    }

    override fun onFailure(message: String) {
        ble.cancelFastOtaSession()

        _state.value = _state.value.copy(
            linkState =
                if (_state.value.authenticated) LinkState.Ready
                else LinkState.Error,
            statusText =
                if (_state.value.authenticated)
                    "Fast OTA interrompida"
                else
                    "Falha de atualização",
            lastError = message
        )

        addLog("Fast OTA: $message")
    }

    override fun onFinished() {
        // O BLE continua como canal de controle. Não há recursos persistentes
        // da rede temporária para limpar aqui; FastOtaManager já os libera.
    }

    private fun clearPendingFirmware() {
        pendingFirmware = null
        pendingFirmwareName = null
    }

    private fun addLog(message: String) {
        val updated = buildList {
            add(message)
            addAll(_state.value.logs)
        }.take(200)

        _state.value = _state.value.copy(logs = updated)
    }

    override fun onCleared() {
        fastOta.cancel(silent = true)
        ble.close()
        super.onCleared()
    }
}
