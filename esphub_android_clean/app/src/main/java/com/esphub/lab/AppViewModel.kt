package com.esphub.lab

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Locale

class AppViewModel(
    application: Application
) : AndroidViewModel(application),
    BleManager.Listener {

    private val crypto =
        CryptoStore(application)

    private val _state =
        MutableStateFlow(
            AppState(
                hasMasterKey =
                    crypto.hasMasterKey(),
                lastCrash =
                    ESPhubApplication
                        .consumeLastCrash(
                            application
                        )
            )
        )

    val state:
        StateFlow<AppState> =
        _state.asStateFlow()

    private val ble =
        BleManager(
            context = application,
            masterKeyProvider = {
                crypto.loadMasterKey()
            },
            listener = this
        )

    fun hasBlePermissions(): Boolean =
        ble.hasPermissions()

    fun bluetoothEnabled(): Boolean =
        ble.bluetoothEnabled()

    fun generateKeyHex(): String =
        crypto.generateMasterKeyHex()

    fun saveMasterKey(
        hex: String
    ): Result<Unit> =
        runCatching {
            crypto.saveMasterKeyHex(
                hex
            )

            _state.update {
                it.copy(
                    hasMasterKey = true
                )
            }

            addLog(
                "Chave mestre armazenada no Android Keystore."
            )
        }

    fun clearMasterKey() {
        ble.disconnectAll()
        crypto.clear()

        _state.update {
            AppState(
                hasMasterKey = false,
                logs =
                    listOf(
                        "Chave mestre removida."
                    )
            )
        }
    }

    fun startScan() {
        ble.startScan()
    }

    fun stopScan() {
        ble.stopScan()
    }

    fun connect(
        node: DiscoveredNode
    ) {
        selectNode(
            node.address
        )
        ble.connect(node)
    }

    fun disconnect(
        address: String
    ) {
        ble.disconnect(address)
    }

    fun selectNode(
        address: String
    ) {
        _state.update {
            it.copy(
                selectedAddress =
                    normalize(
                        address
                    )
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
    ): Result<Unit> =
        runCatching {
            val key =
                normalize(address)

            val node =
                _state.value
                    .nodes[key]
                    ?: error(
                        "Nó não encontrado."
                    )

            require(
                node.authenticated
            ) {
                "Autentique o nó antes de iniciar."
            }

            require(
                "LABTEST_V1" in
                    node.capabilities
            ) {
                "O firmware deste nó não anuncia LABTEST_V1."
            }

            require(
                ssid.isNotBlank() &&
                    ssid.length <= 32
            ) {
                "SSID inválido."
            }

            require(
                password.isEmpty() ||
                    password.length in
                    8..63
            ) {
                "Senha Wi-Fi deve ter 8–63 caracteres ou ficar vazia."
            }

            require(
                level in 1..3
            ) {
                "Nível deve ser 1, 2 ou 3."
            }

            require(
                durationSeconds in
                    5..60
            ) {
                "Duração permitida: 5–60 segundos."
            }

            requirePrivateIpv4(
                targetIpv4
            )

            ble.startLab(
                address = key,
                ssid = ssid,
                password = password,
                targetIpv4 =
                    targetIpv4,
                level = level,
                durationSeconds =
                    durationSeconds
            )

            addLog(
                "LabTest solicitado em " +
                    node.displayName +
                    " • nível " +
                    level +
                    " • " +
                    durationSeconds +
                    "s."
            )
        }

    fun startLabAll(
        ssid: String,
        password: String,
        targetIpv4: String,
        level: Int,
        durationSeconds: Int
    ): Result<Unit> =
        runCatching {
            val nodes =
                _state.value
                    .nodes
                    .values
                    .filter {
                        it.authenticated &&
                            "LABTEST_V1" in
                            it.capabilities
                    }

            require(
                nodes.isNotEmpty()
            ) {
                "Nenhum nó LabTest autenticado."
            }

            nodes.forEach {
                node ->
                startLab(
                    address =
                        node.address,
                    ssid = ssid,
                    password =
                        password,
                    targetIpv4 =
                        targetIpv4,
                    level = level,
                    durationSeconds =
                        durationSeconds
                ).getOrThrow()
            }
        }

    fun stopLab(
        address: String
    ) {
        ble.stopLab(address)
    }

    fun stopAllLabs() {
        _state.value.nodes
            .values
            .filter {
                it.lab.active
            }
            .forEach {
                ble.stopLab(
                    it.address
                )
            }
    }

    fun requestLabStatus(
        address: String
    ) {
        ble.requestLabStatus(
            address
        )
    }

    fun clearLogs() {
        _state.update {
            it.copy(
                logs =
                    listOf(
                        "Console limpo."
                    ),
                lastCrash = null
            )
        }
    }

    override fun onCleared() {
        ble.close()
        super.onCleared()
    }

    override fun onScanning(
        scanning: Boolean
    ) {
        _state.update {
            it.copy(
                scanning = scanning
            )
        }
    }

    override fun onDiscovered(
        nodes: List<DiscoveredNode>
    ) {
        val knownSessionAddresses =
            _state.value
                .nodes
                .keys

        val unique =
            nodes
                .distinctBy {
                    normalize(
                        it.address
                    )
                }
                .filterNot {
                    normalize(
                        it.address
                    ) in knownSessionAddresses
                }

        _state.update {
            it.copy(
                discovered = unique
            )
        }
    }

    override fun onNodeChanged(
        node: NodeState
    ) {
        val key =
            normalize(
                node.address
            )

        _state.update {
            current ->
            val nodes =
                current.nodes
                    .toMutableMap()

            nodes[key] =
                node.copy(
                    address = key
                )

            current.copy(
                nodes = nodes.toMap(),
                selectedAddress =
                    current.selectedAddress
                        ?: key,
                discovered =
                    current.discovered
                        .filterNot {
                            normalize(
                                it.address
                            ) == key
                        }
            )
        }
    }

    override fun onLog(
        message: String
    ) {
        addLog(message)
    }

    private fun addLog(
        message: String
    ) {
        _state.update {
            it.copy(
                logs =
                    buildList {
                        add(message)
                        addAll(
                            it.logs
                        )
                    }
                        .take(300)
            )
        }
    }

    private fun normalize(
        address: String
    ): String =
        address.trim()
            .uppercase(
                Locale.US
            )

    private fun requirePrivateIpv4(
        text: String
    ) {
        val parts =
            text.split(".")

        require(
            parts.size == 4
        ) {
            "Informe um IPv4 privado válido."
        }

        val octets =
            parts.map {
                it.toIntOrNull()
                    ?: throw IllegalArgumentException(
                        "Informe um IPv4 privado válido."
                    )
            }

        require(
            octets.all {
                it in 0..255
            }
        ) {
            "Informe um IPv4 privado válido."
        }

        val privateAddress =
            octets[0] == 10 ||
                (
                    octets[0] == 172 &&
                        octets[1] in
                        16..31
                    ) ||
                (
                    octets[0] == 192 &&
                        octets[1] == 168
                    )

        require(
            privateAddress
        ) {
            "O PC coletor precisa usar IPv4 privado RFC1918."
        }
    }
}
