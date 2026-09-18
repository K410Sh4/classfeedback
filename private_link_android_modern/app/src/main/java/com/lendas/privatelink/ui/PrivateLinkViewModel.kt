package com.lendas.privatelink.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lendas.privatelink.ble.PrivateLinkBleManager
import com.lendas.privatelink.core.Crypto
import com.lendas.privatelink.core.LinkState
import com.lendas.privatelink.core.NearbyDevice
import com.lendas.privatelink.core.PrivateLinkUiState
import com.lendas.privatelink.core.Telemetry
import com.lendas.privatelink.security.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrivateLinkViewModel(application: Application) :
    AndroidViewModel(application),
    PrivateLinkBleManager.Listener {

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
        ble.disconnect()
        secureKeyStore.clearKey()
        ble.setPrivateKey(null)

        _state.value = _state.value.copy(
            hasPrivateKey = false,
            authenticated = false,
            connectedAddress = null,
            linkState = LinkState.Idle,
            statusText = "Chave privada removida"
        )

        addLog("Chave privada removida deste aparelho.")
    }

    fun startScan() = ble.startScan()

    fun stopScan() = ble.stopScan()

    fun connect(device: NearbyDevice) = ble.connect(device)

    fun disconnect() = ble.disconnect()

    fun sendCommand(command: String) = ble.sendCommand(command)

    fun abortOta() = ble.abortOta()

    fun startOta(uri: Uri) {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(
                    linkState = LinkState.Updating,
                    statusText = "Lendo firmware...",
                    otaProgress = 0f
                )

                val payload = withContext(Dispatchers.IO) {
                    getApplication<Application>()
                        .contentResolver
                        .openInputStream(uri)
                        ?.use { it.readBytes() }
                        ?: error("Não foi possível abrir o arquivo.")
                }

                val fileName = resolveFileName(uri)

                if (payload.isEmpty()) {
                    error("Firmware vazio.")
                }

                ble.startOta(payload, fileName)
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    linkState =
                        if (_state.value.authenticated) LinkState.Ready
                        else LinkState.Error,
                    statusText = "Falha ao abrir firmware",
                    lastError = error.message
                )

                addLog("OTA: ${error.message}")
            }
        }
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
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
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
        _state.value = _state.value.copy(
            otaProgress = progress.coerceIn(0f, 1f),
            otaFileName = fileName
        )
    }

    override fun onConnectedAddress(address: String?) {
        _state.value = _state.value.copy(connectedAddress = address)
    }

    private fun addLog(message: String) {
        val updated = buildList {
            add(message)
            addAll(_state.value.logs)
        }.take(200)

        _state.value = _state.value.copy(logs = updated)
    }

    override fun onCleared() {
        ble.close()
        super.onCleared()
    }
}
