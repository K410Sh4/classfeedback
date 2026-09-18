@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.lendas.privatelink

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothSearching
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lendas.privatelink.core.LinkState
import com.lendas.privatelink.core.NearbyDevice
import com.lendas.privatelink.core.NodeState
import com.lendas.privatelink.core.OtaTransport
import com.lendas.privatelink.core.PrivateLinkUiState
import com.lendas.privatelink.core.Protocol
import com.lendas.privatelink.core.Telemetry
import com.lendas.privatelink.ui.PrivateLinkViewModel
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PrivateLinkTheme {
                val vm: PrivateLinkViewModel = viewModel()
                PrivateLinkRoot(vm)
            }
        }
    }
}

private val AppColors = darkColorScheme(
    primary = Color(0xFF5EEAD4),
    onPrimary = Color(0xFF05201C),
    secondary = Color(0xFF60A5FA),
    tertiary = Color(0xFFA78BFA),
    background = Color(0xFF081016),
    surface = Color(0xFF0F1820),
    surfaceVariant = Color(0xFF16222B),
    onSurface = Color(0xFFE8F0F4),
    onSurfaceVariant = Color(0xFFA8B8C1),
    outline = Color(0xFF334550),
    error = Color(0xFFFF6B7A)
)

@Composable
private fun PrivateLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        content = content
    )
}

private enum class AppTab(
    val label: String,
    val icon: ImageVector
) {
    Home("Início", Icons.Rounded.Dashboard),
    Nodes("Nós", Icons.Rounded.Memory),
    Research("Pesquisa", Icons.Rounded.Science),
    Update("Atualizar", Icons.Rounded.SystemUpdateAlt),
    Console("Console", Icons.Rounded.Terminal)
}

@Composable
private fun PrivateLinkRoot(vm: PrivateLinkViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val blePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (grants.values.all { it }) {
                vm.startScan()
            }
        }

    val firmwarePicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            val selected = state.selectedNode
            if (uri != null && selected != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                vm.startSmartOta(
                    uri,
                    selected.address
                )
            }
        }

    val wifiPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            firmwarePicker.launch(
                arrayOf(
                    "application/octet-stream",
                    "*/*"
                )
            )
        }

    val requestScan: () -> Unit = {
        val missing =
            requiredBlePermissions().filter {
                ContextCompat.checkSelfPermission(
                    context,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            vm.startScan()
        } else {
            blePermissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    val launchFirmwarePicker: () -> Unit = {
        val missing =
            requiredFastOtaPermissions().filter {
                ContextCompat.checkSelfPermission(
                    context,
                    it
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missing.isEmpty()) {
            firmwarePicker.launch(
                arrayOf(
                    "application/octet-stream",
                    "*/*"
                )
            )
        } else {
            wifiPermissionLauncher.launch(
                missing.toTypedArray()
            )
        }
    }

    AnimatedContent(
        targetState = state.hasPrivateKey,
        label = "private-key-gate"
    ) { hasKey ->
        if (!hasKey) {
            KeySetupScreen(
                onSave = vm::savePrivateKey
            )
        } else {
            ModernPrivateLinkApp(
                state = state,
                onScan = requestScan,
                onConnect = vm::connect,
                onSelectNode = vm::selectNode,
                onDisconnect = vm::disconnect,
                onDisconnectAll = vm::disconnectAll,
                onCommand = vm::sendCommand,
                onPickFirmware = launchFirmwarePicker,
                onAbortOta = vm::abortOta,
                onClearKey = vm::clearPrivateKey
            )
        }
    }
}

private fun requiredBlePermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

private fun requiredFastOtaPermissions(): List<String> =
    when {
        Build.VERSION.SDK_INT >= 33 ->
            listOf(
                Manifest.permission.NEARBY_WIFI_DEVICES
            )

        Build.VERSION.SDK_INT >= 29 ->
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION
            )

        else ->
            emptyList()
    }

@Composable
private fun KeySetupScreen(
    onSave: (String) -> Result<Unit>
) {
    var key by remember {
        mutableStateOf("")
    }

    var error by remember {
        mutableStateOf<String?>(null)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 22.dp),
            verticalArrangement = Arrangement.Center
        ) {
            item {
                Icon(
                    Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )

                Spacer(
                    Modifier.height(20.dp)
                )

                Text(
                    "ESPhub",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Uma chave mestre no Android. Cada ESP32 novo recebe uma chave derivada independente.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(
                    Modifier.height(28.dp)
                )

                ModernCard {
                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it.trim().take(64)
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = {
                            Text(
                                "Chave mestre • 64 caracteres hex"
                            )
                        },
                        visualTransformation =
                            PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType =
                                    KeyboardType.Ascii
                            ),
                        singleLine = true,
                        isError = error != null,
                        supportingText = {
                            Text(
                                error
                                    ?: "${key.length}/64"
                            )
                        }
                    )

                    Spacer(
                        Modifier.height(12.dp)
                    )

                    Button(
                        onClick = {
                            error =
                                onSave(key)
                                    .exceptionOrNull()
                                    ?.message
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = key.length == 64
                    ) {
                        Text(
                            "Proteger e continuar"
                        )
                    }
                }

                Spacer(
                    Modifier.height(16.dp)
                )

                Text(
                    "Passkey BLE: ${Protocol.PAIRING_PASSKEY}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModernPrivateLinkApp(
    state: PrivateLinkUiState,
    onScan: () -> Unit,
    onConnect: (NearbyDevice) -> Unit,
    onSelectNode: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onDisconnectAll: () -> Unit,
    onCommand: (String, String) -> Unit,
    onPickFirmware: () -> Unit,
    onAbortOta: (String) -> Unit,
    onClearKey: () -> Unit
) {
    var selectedTab by remember {
        mutableStateOf(AppTab.Home)
    }

    var showSettings by remember {
        mutableStateOf(false)
    }

    Scaffold(
        containerColor =
            MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment =
                            Alignment.CenterHorizontally
                    ) {
                        Text(
                            "ESPhub",
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            "Multi-Node ${BuildConfig.VERSION_NAME}",
                            fontSize = 11.sp,
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    if (showSettings) {
                        IconButton(
                            onClick = {
                                showSettings = false
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Close,
                                contentDescription = "Fechar ajustes"
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showSettings =
                                !showSettings
                        }
                    ) {
                        Icon(
                            Icons.Rounded.Settings,
                            contentDescription = "Ajustes"
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar {
                    AppTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected =
                                selectedTab == tab,
                            onClick = {
                                selectedTab = tab
                            },
                            icon = {
                                Icon(
                                    tab.icon,
                                    contentDescription =
                                        tab.label
                                )
                            },
                            label = {
                                Text(
                                    tab.label,
                                    maxLines = 1,
                                    overflow =
                                        TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (showSettings) {
                SettingsScreen(
                    state = state,
                    onDisconnectAll =
                        onDisconnectAll,
                    onClearKey =
                        onClearKey
                )
            } else {
                when (selectedTab) {
                    AppTab.Home ->
                        HomeScreen(
                            state = state,
                            onScan = onScan,
                            onSelectNode =
                                onSelectNode,
                            onCommand =
                                onCommand
                        )

                    AppTab.Nodes ->
                        NodesScreen(
                            state = state,
                            onScan = onScan,
                            onConnect = onConnect,
                            onSelectNode =
                                onSelectNode,
                            onDisconnect =
                                onDisconnect
                        )

                    AppTab.Research ->
                        ResearchScreen(
                            state = state,
                            onSelectNode =
                                onSelectNode
                        )

                    AppTab.Update ->
                        UpdateScreen(
                            state = state,
                            onSelectNode =
                                onSelectNode,
                            onPickFirmware =
                                onPickFirmware,
                            onAbortOta =
                                onAbortOta
                        )

                    AppTab.Console ->
                        ConsoleScreen(
                            state.logs
                        )
                }
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: PrivateLinkUiState,
    onScan: () -> Unit,
    onSelectNode: (String) -> Unit,
    onCommand: (String, String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(
                Modifier.height(4.dp)
            )

            ModernCard {
                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    val active =
                        state.authenticatedCount

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                (
                                    if (active > 0)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.secondary
                                    ).copy(alpha = 0.14f),
                                CircleShape
                            ),
                        contentAlignment =
                            Alignment.Center
                    ) {
                        Icon(
                            if (active > 0)
                                Icons.Rounded.CheckCircle
                            else
                                Icons.Rounded.BluetoothSearching,
                            contentDescription = null,
                            tint =
                                if (active > 0)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(
                        Modifier.width(14.dp)
                    )

                    Column(
                        modifier =
                            Modifier.weight(1f)
                    ) {
                        Text(
                            if (active == 0)
                                "Nenhum nó autenticado"
                            else
                                "$active nó(s) online",
                            style =
                                MaterialTheme.typography.titleLarge,
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            "S3 + C6 podem enviar telemetria simultaneamente",
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant,
                            style =
                                MaterialTheme.typography.bodySmall
                        )
                    }
                }

                Spacer(
                    Modifier.height(16.dp)
                )

                Button(
                    onClick = onScan,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.BluetoothSearching,
                        contentDescription = null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        if (state.scanning)
                            "Buscando..."
                        else
                            "Buscar nós ESPhub"
                    )
                }
            }
        }

        if (state.nodes.isNotEmpty()) {
            item {
                SectionTitle(
                    "Nós conectados",
                    "Cada dispositivo mantém sua própria sessão segura"
                )
            }

            items(
                state.nodes,
                key = { it.address }
            ) { node ->
                NodeSummaryCard(
                    node = node,
                    selected =
                        state.selectedNode?.address ==
                            node.address,
                    onClick = {
                        onSelectNode(
                            node.address
                        )
                    }
                )
            }
        }

        state.selectedNode?.let { node ->
            if (node.authenticated) {
                item {
                    SectionTitle(
                        "Ações rápidas",
                        node.displayName
                    )

                    FlowRow(
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {
                        FilledTonalButton(
                            onClick = {
                                onCommand(
                                    node.address,
                                    "PING"
                                )
                            }
                        ) {
                            Text("PING")
                        }

                        FilledTonalButton(
                            onClick = {
                                onCommand(
                                    node.address,
                                    "STATUS"
                                )
                            }
                        ) {
                            Icon(
                                Icons.Rounded.Refresh,
                                contentDescription = null
                            )

                            Spacer(
                                Modifier.width(6.dp)
                            )

                            Text(
                                "Atualizar status"
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onCommand(
                                    node.address,
                                    "REBOOT"
                                )
                            }
                        ) {
                            Text(
                                "Reiniciar nó"
                            )
                        }
                    }

                    Spacer(
                        Modifier.height(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun NodesScreen(
    state: PrivateLinkUiState,
    onScan: () -> Unit,
    onConnect: (NearbyDevice) -> Unit,
    onSelectNode: (String) -> Unit,
    onDisconnect: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(
                Modifier.height(4.dp)
            )

            SectionTitle(
                "Dispositivos",
                "Gerencie S3 e C6 independentemente"
            )

            Button(
                onClick = onScan,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Rounded.BluetoothSearching,
                    contentDescription = null
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    if (state.scanning)
                        "Buscando PrivateLink..."
                    else
                        "Procurar outro nó ESPhub"
                )
            }
        }

        if (state.devices.isNotEmpty()) {
            item {
                SectionTitle(
                    "Encontrados",
                    "Serviço ESPhub detectado"
                )
            }

            items(
                state.devices,
                key = { it.address }
            ) { device ->
                ModernCard {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Bluetooth,
                            contentDescription = null,
                            tint =
                                MaterialTheme.colorScheme.secondary
                        )

                        Spacer(
                            Modifier.width(12.dp)
                        )

                        Column(
                            modifier =
                                Modifier.weight(1f)
                        ) {
                            Text(
                                "ESPhub Node",
                                fontWeight =
                                    FontWeight.SemiBold
                            )

                            Text(
                                "${device.address} • ${device.rssi} dBm",
                                color =
                                    MaterialTheme.colorScheme
                                        .onSurfaceVariant,
                                style =
                                    MaterialTheme.typography.bodySmall
                            )
                        }

                        Button(
                            onClick = {
                                onConnect(device)
                            }
                        ) {
                            Text("Conectar")
                        }
                    }
                }
            }
        }

        if (state.nodes.isNotEmpty()) {
            item {
                SectionTitle(
                    "Sessões",
                    "${state.authenticatedCount} autenticada(s)"
                )
            }

            items(
                state.nodes,
                key = { it.address }
            ) { node ->
                ModernCard {
                    NodeHeader(node)

                    Spacer(
                        Modifier.height(14.dp)
                    )

                    FlowRow(
                        horizontalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            ),
                        verticalArrangement =
                            Arrangement.spacedBy(
                                8.dp
                            )
                    ) {
                        AssistChip(
                            onClick = {
                                onSelectNode(
                                    node.address
                                )
                            },
                            label = {
                                Text(
                                    if (
                                        state.selectedNode?.address ==
                                        node.address
                                    ) "Selecionado"
                                    else "Selecionar"
                                )
                            }
                        )

                        if (
                            node.info.role != null
                        ) {
                            AssistChip(
                                onClick = {},
                                label = {
                                    Text(
                                        humanRole(
                                            node.info.role
                                        )
                                    )
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                onDisconnect(
                                    node.address
                                )
                            }
                        ) {
                            Icon(
                                Icons.Rounded.LinkOff,
                                contentDescription = null
                            )

                            Spacer(
                                Modifier.width(6.dp)
                            )

                            Text("Desconectar")
                        }
                    }
                }
            }
        }

        item {
            Spacer(
                Modifier.height(24.dp)
            )
        }
    }
}

@Composable
private fun ResearchScreen(
    state: PrivateLinkUiState,
    onSelectNode: (String) -> Unit
) {
    val researchNodes =
        state.nodes.filter {
            it.authenticated
        }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(
                Modifier.height(4.dp)
            )

            ModernCard {
                Row(
                    verticalAlignment =
                        Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Science,
                        contentDescription = null,
                        tint =
                            MaterialTheme.colorScheme.tertiary,
                        modifier =
                            Modifier.size(36.dp)
                    )

                    Spacer(
                        Modifier.width(12.dp)
                    )

                    Column {
                        Text(
                            "Observatório Multi-Node",
                            style =
                                MaterialTheme.typography.titleLarge,
                            fontWeight =
                                FontWeight.SemiBold
                        )

                        Text(
                            "Medições passivas recebidas de cada nó",
                            color =
                                MaterialTheme.colorScheme
                                    .onSurfaceVariant
                        )
                    }
                }

                Spacer(
                    Modifier.height(12.dp)
                )

                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            "PASSIVO • sem transmissão ofensiva"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            modifier =
                                Modifier.size(17.dp)
                        )
                    }
                )
            }
        }

        if (researchNodes.isEmpty()) {
            item {
                ModernCard {
                    Text(
                        "Aguardando nós autenticados",
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Text(
                        "Conecte o S3 e/ou C6 para receber as medições.",
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        }

        items(
            researchNodes,
            key = { it.address }
        ) { node ->
            ResearchNodeCard(
                node = node,
                selected =
                    state.selectedNode?.address ==
                        node.address,
                onSelect = {
                    onSelectNode(
                        node.address
                    )
                }
            )
        }

        item {
            Spacer(
                Modifier.height(24.dp)
            )
        }
    }
}

@Composable
private fun UpdateScreen(
    state: PrivateLinkUiState,
    onSelectNode: (String) -> Unit,
    onPickFirmware: () -> Unit,
    onAbortOta: (String) -> Unit
) {
    val selected =
        state.selectedNode

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(
                Modifier.height(4.dp)
            )

            SectionTitle(
                "Atualização por nó",
                "O app impede misturar firmware C6 e S3"
            )

            if (state.nodes.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        ),
                    verticalArrangement =
                        Arrangement.spacedBy(
                            8.dp
                        )
                ) {
                    state.nodes.forEach { node ->
                        AssistChip(
                            onClick = {
                                onSelectNode(
                                    node.address
                                )
                            },
                            label = {
                                Text(
                                    node.displayName
                                )
                            },
                            leadingIcon = {
                                if (
                                    selected?.address ==
                                    node.address
                                ) {
                                    Icon(
                                        Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier =
                                            Modifier.size(
                                                17.dp
                                            )
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }

        if (selected == null) {
            item {
                ModernCard {
                    Text(
                        "Selecione um nó",
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Text(
                        "Conecte um ESP32-S3 ou nanoESP32-C6 antes de escolher o firmware.",
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant
                    )
                }
            }
        } else {
            item {
                OtaCard(
                    node = selected,
                    onPickFirmware =
                        onPickFirmware,
                    onAbort = {
                        onAbortOta(
                            selected.address
                        )
                    }
                )
            }

            item {
                ModernCard {
                    SecurityRow(
                        Icons.Rounded.Bluetooth,
                        "Controle por BLE",
                        "Sessão autenticada do nó selecionado"
                    )

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 12.dp
                            )
                    )

                    SecurityRow(
                        Icons.Rounded.Wifi,
                        "Fast OTA",
                        "Wi-Fi temporário apenas durante a transferência"
                    )

                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                vertical = 12.dp
                            )
                    )

                    SecurityRow(
                        Icons.Rounded.Security,
                        "SHA-256 + HMAC",
                        "Integridade e autenticidade verificadas no nó"
                    )
                }
            }
        }

        item {
            Spacer(
                Modifier.height(24.dp)
            )
        }
    }
}

@Composable
private fun OtaCard(
    node: NodeState,
    onPickFirmware: () -> Unit,
    onAbort: () -> Unit
) {
    val updating =
        node.linkState in setOf(
            LinkState.PreparingFastOta,
            LinkState.ConnectingFastOta,
            LinkState.Updating,
            LinkState.Verifying
        )

    val transport =
        when (node.otaTransport) {
            OtaTransport.WifiFast ->
                "Wi-Fi privado rápido"

            OtaTransport.Ble ->
                "BLE compatível"

            OtaTransport.None ->
                "Automático"
        }

    ModernCard {
        NodeHeader(node)

        Spacer(
            Modifier.height(16.dp)
        )

        AssistChip(
            onClick = {},
            label = {
                Text(transport)
            },
            leadingIcon = {
                Icon(
                    if (
                        node.otaTransport ==
                        OtaTransport.Ble
                    ) Icons.Rounded.Bluetooth
                    else Icons.Rounded.Wifi,
                    contentDescription = null,
                    modifier =
                        Modifier.size(17.dp)
                )
            }
        )

        Spacer(
            Modifier.height(14.dp)
        )

        node.otaFileName?.let {
            Text(
                it,
                fontWeight =
                    FontWeight.Medium,
                maxLines = 1,
                overflow =
                    TextOverflow.Ellipsis
            )

            Spacer(
                Modifier.height(8.dp)
            )
        }

        LinearProgressIndicator(
            progress = {
                node.otaProgress
            },
            modifier =
                Modifier.fillMaxWidth()
        )

        Spacer(
            Modifier.height(8.dp)
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween
        ) {
            Text(
                String.format(
                    Locale.US,
                    "%.1f%%",
                    node.otaProgress * 100f
                ),
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant
            )

            node.otaSpeedBytesPerSecond?.let {
                Text(
                    formatTransferSpeed(it),
                    color =
                        MaterialTheme.colorScheme.primary,
                    fontWeight =
                        FontWeight.SemiBold
                )
            }
        }

        if (node.otaTotalBytes > 0L) {
            Text(
                "${formatBytes(node.otaTransferredBytes)} / " +
                    formatBytes(node.otaTotalBytes),
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                style =
                    MaterialTheme.typography.bodySmall
            )
        }

        Spacer(
            Modifier.height(14.dp)
        )

        if (updating) {
            OutlinedButton(
                onClick = onAbort,
                modifier =
                    Modifier.fillMaxWidth()
            ) {
                Text(
                    "Cancelar atualização"
                )
            }
        } else {
            Button(
                onClick = onPickFirmware,
                modifier =
                    Modifier.fillMaxWidth(),
                enabled = node.authenticated
            ) {
                Icon(
                    Icons.Rounded.UploadFile,
                    contentDescription = null
                )

                Spacer(
                    Modifier.width(8.dp)
                )

                Text(
                    "Selecionar firmware"
                )
            }
        }
    }
}

@Composable
private fun ConsoleScreen(
    logs: List<String>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(
            Modifier.height(4.dp)
        )

        ModernCard {
            Row(
                verticalAlignment =
                    Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint =
                        MaterialTheme.colorScheme.secondary
                )

                Spacer(
                    Modifier.width(10.dp)
                )

                Column {
                    Text(
                        "Console Multi-Node",
                        fontWeight =
                            FontWeight.SemiBold
                    )

                    Text(
                        "${logs.size} eventos recentes",
                        color =
                            MaterialTheme.colorScheme
                                .onSurfaceVariant,
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(
            Modifier.height(12.dp)
        )

        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor =
                        Color(0xFF070C10)
                ),
            shape =
                RoundedCornerShape(18.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement =
                    Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { line ->
                    Text(
                        line,
                        color =
                            Color(0xFFC5D4DB),
                        fontFamily =
                            FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(
            Modifier.height(16.dp)
        )
    }
}

@Composable
private fun SettingsScreen(
    state: PrivateLinkUiState,
    onDisconnectAll: () -> Unit,
    onClearKey: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement =
            Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(
                Modifier.height(4.dp)
            )

            SectionTitle(
                "Segurança",
                "Identidade local e isolamento dos nós"
            )

            ModernCard {
                SecurityRow(
                    Icons.Rounded.VpnKey,
                    "Chave mestre",
                    "Protegida pelo Android Keystore"
                )

                HorizontalDivider(
                    modifier =
                        Modifier.padding(
                            vertical = 12.dp
                        )
                )

                SecurityRow(
                    Icons.Rounded.Security,
                    "Chaves por nó",
                    "Derivadas por HMAC a partir do node_id"
                )

                HorizontalDivider(
                    modifier =
                        Modifier.padding(
                            vertical = 12.dp
                        )
                )

                SecurityRow(
                    Icons.Rounded.Bluetooth,
                    "Pareamento BLE",
                    "Passkey ${Protocol.PAIRING_PASSKEY}"
                )
            }
        }

        item {
            SectionTitle(
                "Sessões",
                "${state.authenticatedCount} nó(s) autenticado(s)"
            )

            ModernCard {
                OutlinedButton(
                    onClick =
                        onDisconnectAll,
                    modifier =
                        Modifier.fillMaxWidth(),
                    enabled =
                        state.nodes.isNotEmpty()
                ) {
                    Icon(
                        Icons.Rounded.LinkOff,
                        contentDescription = null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        "Desconectar todos"
                    )
                }

                Spacer(
                    Modifier.height(10.dp)
                )

                OutlinedButton(
                    onClick =
                        onClearKey,
                    modifier =
                        Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        contentDescription = null
                    )

                    Spacer(
                        Modifier.width(8.dp)
                    )

                    Text(
                        "Trocar chave mestre"
                    )
                }
            }
        }

        item {
            ModernCard {
                Text(
                    "ESPhub ${BuildConfig.VERSION_NAME}",
                    fontWeight =
                        FontWeight.SemiBold
                )

                Spacer(
                    Modifier.height(6.dp)
                )

                Text(
                    "Arquitetura Multi-Node: conexões BLE independentes, telemetria simultânea, chaves por nó e OTA direcionada por hardware.",
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant
                )
            }

            Spacer(
                Modifier.height(24.dp)
            )
        }
    }
}

@Composable
private fun NodeSummaryCard(
    node: NodeState,
    selected: Boolean,
    onClick: () -> Unit
) {
    ModernCard {
        NodeHeader(node)

        Spacer(
            Modifier.height(12.dp)
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.SpaceBetween,
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            FlowRow(
                horizontalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = onClick,
                    label = {
                        Text(
                            if (selected)
                                "Selecionado"
                            else
                                "Selecionar"
                        )
                    }
                )

                if (node.authenticated) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                "Seguro"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier =
                                    Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }

            Text(
                node.info.firmware,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun NodeHeader(
    node: NodeState
) {
    Row(
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        val ok =
            node.authenticated

        Box(
            modifier = Modifier
                .size(44.dp)
                .background(
                    (
                        if (ok)
                            MaterialTheme.colorScheme.primary
                        else if (
                            node.linkState ==
                            LinkState.Error
                        )
                            MaterialTheme.colorScheme.error
                        else
                            MaterialTheme.colorScheme.secondary
                        ).copy(
                            alpha = 0.14f
                        ),
                    CircleShape
                ),
            contentAlignment =
                Alignment.Center
        ) {
            Icon(
                if (ok)
                    Icons.Rounded.CheckCircle
                else if (
                    node.linkState ==
                    LinkState.Error
                )
                    Icons.Rounded.ErrorOutline
                else
                    Icons.Rounded.Memory,
                contentDescription = null,
                tint =
                    if (ok)
                        MaterialTheme.colorScheme.primary
                    else if (
                        node.linkState ==
                        LinkState.Error
                    )
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.secondary
            )
        }

        Spacer(
            Modifier.width(12.dp)
        )

        Column(
            modifier =
                Modifier.weight(1f)
        ) {
            Text(
                node.displayName,
                fontWeight =
                    FontWeight.SemiBold,
                style =
                    MaterialTheme.typography.titleMedium
            )

            Text(
                node.statusText,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                style =
                    MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow =
                    TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ResearchNodeCard(
    node: NodeState,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val t =
        node.telemetry

    ModernCard {
        Row(
            verticalAlignment =
                Alignment.CenterVertically
        ) {
            Column(
                modifier =
                    Modifier.weight(1f)
            ) {
                Text(
                    node.displayName,
                    fontWeight =
                        FontWeight.SemiBold,
                    style =
                        MaterialTheme.typography.titleMedium
                )

                Text(
                    node.info.nodeId
                        ?: node.address,
                    color =
                        MaterialTheme.colorScheme
                            .onSurfaceVariant,
                    style =
                        MaterialTheme.typography.bodySmall
                )
            }

            AssistChip(
                onClick = onSelect,
                label = {
                    Text(
                        if (selected)
                            "Selecionado"
                        else
                            "Selecionar"
                    )
                }
            )
        }

        Spacer(
            Modifier.height(14.dp)
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                Modifier.weight(1f),
                Icons.Rounded.Wifi,
                "Wi-Fi APs",
                t.wifiAp?.toString()
                    ?: "-"
            )

            MetricCard(
                Modifier.weight(1f),
                Icons.Rounded.Bluetooth,
                "BLE vistos",
                t.bleSeen?.toString()
                    ?: "-"
            )
        }

        Spacer(
            Modifier.height(10.dp)
        )

        Row(
            modifier =
                Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                Modifier.weight(1f),
                Icons.Rounded.SignalCellularAlt,
                "Wi-Fi RSSI",
                t.wifiBest?.let {
                    "$it dBm"
                } ?: "-"
            )

            MetricCard(
                Modifier.weight(1f),
                Icons.Rounded.SignalCellularAlt,
                "BLE RSSI",
                t.bleBest?.let {
                    "$it dBm"
                } ?: "-"
            )
        }

        Spacer(
            Modifier.height(10.dp)
        )

        Text(
            t.wifiPeakChannel?.let {
                "Canal Wi-Fi mais ocupado: $it • ${t.wifiPeakCount ?: 0} APs"
            } ?: "Aguardando survey passivo",
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun SectionTitle(
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.padding(
            top = 8.dp,
            bottom = 8.dp
        )
    ) {
        Text(
            title,
            style =
                MaterialTheme.typography.titleLarge,
            fontWeight =
                FontWeight.SemiBold
        )

        Text(
            subtitle,
            color =
                MaterialTheme.colorScheme
                    .onSurfaceVariant,
            style =
                MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ModernCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier =
            Modifier.fillMaxWidth(),
        shape =
            RoundedCornerShape(22.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme.surface
            )
    ) {
        Column(
            modifier =
                Modifier.padding(18.dp),
            content = content
        )
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier,
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = modifier,
        shape =
            RoundedCornerShape(18.dp),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    MaterialTheme.colorScheme
                        .surfaceVariant
            )
    ) {
        Column(
            modifier =
                Modifier.padding(14.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint =
                    MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.size(21.dp)
            )

            Spacer(
                Modifier.height(10.dp)
            )

            Text(
                label,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                style =
                    MaterialTheme.typography.labelMedium
            )

            Text(
                value,
                style =
                    MaterialTheme.typography.titleMedium,
                fontWeight =
                    FontWeight.SemiBold,
                maxLines = 2,
                overflow =
                    TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SecurityRow(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Row(
        verticalAlignment =
            Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint =
                MaterialTheme.colorScheme.primary
        )

        Spacer(
            Modifier.width(12.dp)
        )

        Column {
            Text(
                title,
                fontWeight =
                    FontWeight.SemiBold
            )

            Text(
                subtitle,
                color =
                    MaterialTheme.colorScheme
                        .onSurfaceVariant,
                style =
                    MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun humanRole(
    role: String
): String =
    when (role) {
        "PRIMARY_NODE" ->
            "Primary"
        "RADIO_NODE" ->
            "Radio"
        else ->
            role
    }

private fun formatTransferSpeed(
    bytesPerSecond: Long
): String =
    when {
        bytesPerSecond >=
            1024L * 1024L ->
            String.format(
                Locale.US,
                "%.2f MB/s",
                bytesPerSecond /
                    (1024.0 * 1024.0)
            )

        bytesPerSecond >=
            1024L ->
            String.format(
                Locale.US,
                "%.0f KB/s",
                bytesPerSecond /
                    1024.0
            )

        else ->
            "$bytesPerSecond B/s"
    }

private fun formatBytes(
    bytes: Long
): String =
    when {
        bytes >=
            1024L * 1024L ->
            String.format(
                Locale.US,
                "%.2f MB",
                bytes /
                    (1024.0 * 1024.0)
            )

        bytes >=
            1024L ->
            String.format(
                Locale.US,
                "%.0f KB",
                bytes /
                    1024.0
            )

        else ->
            "$bytes B"
    }
