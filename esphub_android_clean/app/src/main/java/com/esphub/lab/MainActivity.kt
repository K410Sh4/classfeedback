@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.esphub.lab

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                ESPhubApp()
            }
        }
    }
}

private enum class AppTab(val title: String) {
    Nodes("Nós"),
    Lab("Lab"),
    Console("Console")
}

@Composable
private fun ESPhubApp(
    vm: AppViewModel = viewModel()
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            if (vm.hasBlePermissions()) {
                vm.startScan()
            }
        }

    fun requestPermissions() {
        val required =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }

        permissionLauncher.launch(required)
    }

    if (!state.hasMasterKey) {
        KeySetupScreen(
            generate = vm::generateKeyHex,
            save = vm::saveMasterKey
        )
        return
    }

    MainScreen(
        state = state,
        hasPermissions = vm.hasBlePermissions(),
        bluetoothEnabled = vm.bluetoothEnabled(),
        requestPermissions = ::requestPermissions,
        startScan = {
            if (vm.hasBlePermissions()) {
                vm.startScan()
            } else {
                requestPermissions()
            }
        },
        stopScan = vm::stopScan,
        connect = vm::connect,
        disconnect = vm::disconnect,
        selectNode = vm::selectNode,
        startLab = vm::startLab,
        startLabAll = vm::startLabAll,
        stopLab = vm::stopLab,
        stopAllLabs = vm::stopAllLabs,
        requestLabStatus = vm::requestLabStatus,
        clearLogs = vm::clearLogs,
        clearMasterKey = vm::clearMasterKey
    )
}

@Composable
private fun KeySetupScreen(
    generate: () -> String,
    save: (String) -> Result<Unit>
) {
    var keyText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(30.dp))
            Icon(
                Icons.Rounded.Hub,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "ESPhub",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Controlador limpo para ESP32, C6 e S3 • LabTest V1",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null
                    )
                    Spacer(Modifier.size(10.dp))
                    Text(
                        "Nova instalação independente",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(10.dp))

                Text(
                    "O novo ESPhub usa outro applicationId e não herda banco, sessões ou cache do app antigo. " +
                        "A chave mestre fica protegida pelo Android Keystore.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = keyText,
                    onValueChange = {
                        keyText = it
                            .filter { c ->
                                c.isDigit() ||
                                    c.lowercaseChar() in 'a'..'f'
                            }
                            .take(64)
                        error = null
                    },
                    label = { Text("Chave mestre • 64 hex") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    )
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        keyText = generate()
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Gerar nova chave")
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = {
                        error = save(keyText)
                            .exceptionOrNull()
                            ?.message
                    },
                    enabled = keyText.length == 64,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Salvar e iniciar")
                }
            }
        }

        item {
            Text(
                "Se os três ESPs foram apagados antes do FULL, gere uma chave nova. " +
                    "Se preservaram a credencial antiga, use a mesma chave mestre anterior.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MainScreen(
    state: AppState,
    hasPermissions: Boolean,
    bluetoothEnabled: Boolean,
    requestPermissions: () -> Unit,
    startScan: () -> Unit,
    stopScan: () -> Unit,
    connect: (DiscoveredNode) -> Unit,
    disconnect: (String) -> Unit,
    selectNode: (String) -> Unit,
    startLab: (String, String, String, String, Int, Int) -> Result<Unit>,
    startLabAll: (String, String, String, Int, Int) -> Result<Unit>,
    stopLab: (String) -> Unit,
    stopAllLabs: () -> Unit,
    requestLabStatus: (String) -> Unit,
    clearLogs: () -> Unit,
    clearMasterKey: () -> Unit
) {
    var tab by remember { mutableStateOf(AppTab.Nodes) }
    var showReset by remember { mutableStateOf(false) }

    if (showReset) {
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Apagar chave local?") },
            text = {
                Text(
                    "Isso desconecta os nós e remove a chave mestre somente desta nova instalação."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showReset = false
                        clearMasterKey()
                    }
                ) {
                    Text("Apagar")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showReset = false }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "ESPhub",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Lab Controller 1.0.0",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { showReset = true }
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Apagar chave local"
                    )
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = {
                            Icon(
                                when (item) {
                                    AppTab.Nodes -> Icons.Rounded.Bluetooth
                                    AppTab.Lab -> Icons.Rounded.Science
                                    AppTab.Console -> Icons.Rounded.Code
                                },
                                contentDescription = item.title
                            )
                        },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { padding ->
        when (tab) {
            AppTab.Nodes ->
                NodesScreen(
                    state = state,
                    hasPermissions = hasPermissions,
                    bluetoothEnabled = bluetoothEnabled,
                    requestPermissions = requestPermissions,
                    startScan = startScan,
                    stopScan = stopScan,
                    connect = connect,
                    disconnect = disconnect,
                    selectNode = selectNode,
                    modifier = Modifier.padding(padding)
                )

            AppTab.Lab ->
                LabScreen(
                    state = state,
                    selectNode = selectNode,
                    startLab = startLab,
                    startLabAll = startLabAll,
                    stopLab = stopLab,
                    stopAllLabs = stopAllLabs,
                    requestLabStatus = requestLabStatus,
                    modifier = Modifier.padding(padding)
                )

            AppTab.Console ->
                ConsoleScreen(
                    state = state,
                    clearLogs = clearLogs,
                    modifier = Modifier.padding(padding)
                )
        }
    }
}

@Composable
private fun NodesScreen(
    state: AppState,
    hasPermissions: Boolean,
    bluetoothEnabled: Boolean,
    requestPermissions: () -> Unit,
    startScan: () -> Unit,
    stopScan: () -> Unit,
    connect: (DiscoveredNode) -> Unit,
    disconnect: (String) -> Unit,
    selectNode: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val nodes = state.nodes.values.sortedWith(
        compareByDescending<NodeState> { it.authenticated }
            .thenBy { it.displayName }
    )

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle(
                "Conexões",
                "ESP32 DevKit V1 • nanoESP32-C6 • ESP32-S3"
            )

            Spacer(Modifier.height(10.dp))

            when {
                !hasPermissions ->
                    Button(
                        onClick = requestPermissions,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Conceder permissão Bluetooth")
                    }

                !bluetoothEnabled ->
                    AppCard {
                        Text("Ative o Bluetooth do Android.")
                    }

                else ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = if (state.scanning) stopScan else startScan,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (state.scanning) "Parar busca"
                                else "Buscar nós"
                            )
                        }

                        FilledTonalButton(onClick = startScan) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null
                            )
                        }
                    }
            }
        }

        if (state.discovered.isNotEmpty()) {
            item {
                SectionTitle(
                    "Encontrados",
                    "Serviço ESPhub detectado"
                )
            }

            items(
                items = state.discovered,
                key = { "discovered:" + it.address.uppercase() }
            ) { device ->
                AppCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                device.address,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                device.rssi.toString() + " dBm",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Button(onClick = { connect(device) }) {
                            Text("Conectar")
                        }
                    }
                }
            }
        }

        if (nodes.isNotEmpty()) {
            item {
                SectionTitle(
                    "Sessões",
                    nodes.count { it.authenticated }.toString() +
                        " autenticada(s)"
                )
            }

            items(
                items = nodes,
                key = { "session:" + it.address.uppercase() }
            ) { node ->
                NodeCard(
                    node = node,
                    selected = state.selectedAddress == node.address,
                    onSelect = { selectNode(node.address) },
                    onDisconnect = { disconnect(node.address) }
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun NodeCard(
    node: NodeState,
    selected: Boolean,
    onSelect: () -> Unit,
    onDisconnect: () -> Unit
) {
    AppCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (node.authenticated) Icons.Rounded.CheckCircle
                else Icons.Rounded.Hub,
                contentDescription = null,
                tint =
                    if (node.authenticated) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.displayName,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    node.address,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    node.status,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (node.firmware != null || node.role != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(
                    node.firmware,
                    node.role
                ).joinToString(" • "),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = onSelect,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (selected) "Selecionado" else "Selecionar")
            }

            OutlinedButton(
                onClick = onDisconnect,
                modifier = Modifier.weight(1f)
            ) {
                Text("Desconectar")
            }
        }
    }
}

@Composable
private fun LabScreen(
    state: AppState,
    selectNode: (String) -> Unit,
    startLab: (String, String, String, String, Int, Int) -> Result<Unit>,
    startLabAll: (String, String, String, Int, Int) -> Result<Unit>,
    stopLab: (String) -> Unit,
    stopAllLabs: () -> Unit,
    requestLabStatus: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var durationText by remember { mutableStateOf("20") }
    var level by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }

    val labNodes = state.nodes.values
        .filter {
            it.authenticated &&
                "LABTEST_V1" in it.capabilities
        }
        .sortedBy { it.displayName }

    val selected = state.selectedNode?.takeIf {
        it.authenticated &&
            "LABTEST_V1" in it.capabilities
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SectionTitle(
                "LabTest V1",
                "UDP unicast limitado para um PC coletor da LAN privada"
            )
            Spacer(Modifier.height(10.dp))
            AppCard {
                Text(
                    "Segurança do teste",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "IPv4 privado na mesma sub-rede • gateway e broadcast bloqueados • " +
                        "5–60 s • 3 níveis • parada se o controle BLE for perdido.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionTitle(
                "Nós",
                labNodes.size.toString() + " nó(s) LabTest autenticado(s)"
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                labNodes.forEach { node ->
                    AssistChip(
                        onClick = { selectNode(node.address) },
                        label = { Text(node.displayName) },
                        leadingIcon = {
                            if (selected?.address == node.address) {
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        item {
            AppCard {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = {
                        ssid = it.take(32)
                        error = null
                    },
                    label = { Text("SSID do laboratório") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it.take(63)
                        error = null
                    },
                    label = { Text("Senha Wi-Fi") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = target,
                    onValueChange = {
                        target = it
                            .filter { c -> c.isDigit() || c == '.' }
                            .take(15)
                        error = null
                    },
                    label = { Text("IPv4 do PC coletor") },
                    placeholder = { Text("192.168.1.50") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = durationText,
                    onValueChange = {
                        durationText = it
                            .filter(Char::isDigit)
                            .take(2)
                        error = null
                    },
                    label = { Text("Duração • 5–60 s") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Nível de carga",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    (1..3).forEach { option ->
                        AssistChip(
                            onClick = { level = option },
                            label = { Text("Nível " + option) },
                            leadingIcon = {
                                if (level == option) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(14.dp))

                Button(
                    onClick = {
                        val node = selected

                        error =
                            if (node == null) {
                                "Selecione um nó."
                            } else {
                                startLab(
                                    node.address,
                                    ssid,
                                    password,
                                    target,
                                    level,
                                    durationText.toIntOrNull() ?: 0
                                )
                                    .exceptionOrNull()
                                    ?.message
                            }
                    },
                    enabled = selected != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar no nó selecionado")
                }

                Spacer(Modifier.height(8.dp))

                FilledTonalButton(
                    onClick = {
                        error = startLabAll(
                            ssid,
                            password,
                            target,
                            level,
                            durationText.toIntOrNull() ?: 0
                        )
                            .exceptionOrNull()
                            ?.message
                    },
                    enabled = labNodes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Iniciar em todos os nós")
                }

                Spacer(Modifier.height(8.dp))

                OutlinedButton(
                    onClick = stopAllLabs,
                    enabled = labNodes.any { it.lab.active },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.StopCircle,
                        contentDescription = null
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("PARAR TODOS")
                }
            }
        }

        if (labNodes.isNotEmpty()) {
            item {
                SectionTitle(
                    "Telemetria",
                    "DevKit 41001 • C6 41002 • S3 41003"
                )
            }

            items(
                items = labNodes,
                key = { "lab:" + it.address.uppercase() }
            ) { node ->
                LabNodeCard(
                    node = node,
                    stop = { stopLab(node.address) },
                    refresh = {
                        requestLabStatus(node.address)
                    }
                )
            }
        }

        item {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun LabNodeCard(
    node: NodeState,
    stop: () -> Unit,
    refresh: () -> Unit
) {
    val lab = node.lab

    AppCard {
        Text(
            node.displayName,
            fontWeight = FontWeight.Bold
        )

        Text(
            "UDP/" + node.labDefaultPort + " • " + node.address,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(10.dp))

        Text("Estado: " + lab.state)
        Text("Nível: " + lab.level)
        Text(
            "PPS perfil/real: " +
                (lab.profilePps?.toString() ?: "-") +
                " / " +
                (lab.actualPps?.toString() ?: "-")
        )
        Text(
            "Pacote: " +
                (lab.packetBytes?.toString() ?: "-") +
                " bytes"
        )
        Text(
            "TX: " +
                lab.txPackets +
                " pacotes • " +
                formatBytes(lab.txBytes)
        )
        Text(
            "Tempo: " +
                (lab.elapsedMs / 1000L) +
                "s"
        )
        Text(
            "RSSI: " +
                (lab.rssi?.let { it.toString() + " dBm" } ?: "-")
        )

        lab.reason?.let {
            Text(
                "Motivo: " + it,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = refresh,
                modifier = Modifier.weight(1f)
            ) {
                Text("Status")
            }

            OutlinedButton(
                onClick = stop,
                enabled = lab.active,
                modifier = Modifier.weight(1f)
            ) {
                Text("Parar")
            }
        }
    }
}

@Composable
private fun ConsoleScreen(
    state: AppState,
    clearLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle(
                    "Console",
                    state.logs.size.toString() + " evento(s)",
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = clearLogs) {
                    Text("Limpar")
                }
            }
        }

        state.lastCrash?.let { crash ->
            item {
                AppCard {
                    Text(
                        "Crash anterior",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        crash,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        items(
            items = state.logs.withIndex().toList(),
            key = { item -> item.index }
        ) { indexed ->
            Text(
                indexed.value,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            content = content
        )
    }
}

private fun formatBytes(value: Long): String {
    return when {
        value >= 1024L * 1024L ->
            String.format(
                "%.2f MB",
                value.toDouble() /
                    (1024.0 * 1024.0)
            )

        value >= 1024L ->
            String.format(
                "%.1f KB",
                value.toDouble() / 1024.0
            )

        else ->
            value.toString() + " B"
    }
}
