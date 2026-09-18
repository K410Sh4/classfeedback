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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.CircularProgressIndicator
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
    Research("Pesquisa", Icons.Rounded.Science),
    Update("Atualizar", Icons.Rounded.SystemUpdateAlt),
    Console("Console", Icons.Rounded.Terminal),
    Settings("Ajustes", Icons.Rounded.Settings)
}

@Composable
private fun PrivateLinkRoot(vm: PrivateLinkViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher =
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
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                vm.startOta(uri)
            }
        }

    val requestScan: () -> Unit = {
        val permissions = requiredPermissions()
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) !=
                PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            vm.startScan()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    AnimatedContent(
        targetState = state.hasPrivateKey,
        label = "private-key-gate"
    ) { hasKey ->
        if (!hasKey) {
            KeySetupScreen(
                onSave = { vm.savePrivateKey(it) }
            )
        } else {
            ModernPrivateLinkApp(
                state = state,
                onScan = requestScan,
                onConnect = vm::connect,
                onDisconnect = vm::disconnect,
                onCommand = vm::sendCommand,
                onPickFirmware = {
                    firmwarePicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onAbortOta = vm::abortOta,
                onClearKey = vm::clearPrivateKey
            )
        }
    }
}

private fun requiredPermissions(): List<String> =
    if (Build.VERSION.SDK_INT >= 31) {
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

@Composable
private fun KeySetupScreen(
    onSave: (String) -> Result<Unit>
) {
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

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
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(56.dp)
                )

                Spacer(Modifier.height(20.dp))

                Text(
                    "PrivateLink",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    "Configure o vínculo criptográfico com seu ESP32-S3.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyLarge
                )

                Spacer(Modifier.height(28.dp))

                ModernCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "Chave privada HMAC",
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "Armazenada com Android Keystore",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    OutlinedTextField(
                        value = key,
                        onValueChange = {
                            key = it.trim().take(64)
                            error = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("64 caracteres hexadecimais") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii
                        ),
                        singleLine = true,
                        isError = error != null,
                        supportingText = {
                            Text(error ?: "${key.length}/64")
                        }
                    )

                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = {
                            val result = onSave(key)
                            error = result.exceptionOrNull()?.message
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = key.length == 64
                    ) {
                        Text("Proteger e continuar")
                    }
                }

                Spacer(Modifier.height(18.dp))

                Text(
                    "Passkey BLE do projeto: ${Protocol.PAIRING_PASSKEY}",
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
    onDisconnect: () -> Unit,
    onCommand: (String) -> Unit,
    onPickFirmware: () -> Unit,
    onAbortOta: () -> Unit,
    onClearKey: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(AppTab.Home) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "LENDAS S3",
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "PrivateLink 2.0",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    if (state.connectedAddress != null) {
                        IconButton(onClick = onDisconnect) {
                            Icon(
                                Icons.Rounded.LinkOff,
                                contentDescription = "Desconectar"
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = {
                            Icon(tab.icon, contentDescription = tab.label)
                        },
                        label = {
                            Text(
                                tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (selectedTab) {
                AppTab.Home -> HomeScreen(
                    state,
                    onScan,
                    onConnect,
                    onCommand
                )

                AppTab.Research -> ResearchScreen(state.telemetry)

                AppTab.Update -> UpdateScreen(
                    state,
                    onPickFirmware,
                    onAbortOta
                )

                AppTab.Console -> ConsoleScreen(state.logs)

                AppTab.Settings -> SettingsScreen(
                    state,
                    onClearKey
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    state: PrivateLinkUiState,
    onScan: () -> Unit,
    onConnect: (NearbyDevice) -> Unit,
    onCommand: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))
            ConnectionHero(state, onScan)
        }

        if (!state.authenticated && state.devices.isNotEmpty()) {
            item {
                SectionTitle(
                    "Dispositivos próximos",
                    "Apenas PrivateLink dentro da faixa configurada"
                )
            }

            items(
                items = state.devices,
                key = { it.address }
            ) { device ->
                DeviceCard(device, onConnect)
            }
        }

        item {
            SectionTitle(
                "Sistema",
                "Estado atual do ESP32-S3"
            )

            val t = state.telemetry

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Memory,
                    label = "Firmware",
                    value = t.firmware
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Timer,
                    label = "Uptime",
                    value = formatUptime(t.uptimeMs)
                )
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.Storage,
                    label = "Heap",
                    value = formatHeap(t.heap)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Rounded.SignalCellularAlt,
                    label = "Bateria",
                    value =
                        if (t.batteryVolts == "na") "Não configurada"
                        else "${t.batteryVolts} V"
                )
            }
        }

        item {
            SectionTitle(
                "Ações rápidas",
                "Comandos disponíveis no canal autenticado"
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onCommand("PING") },
                    enabled = state.authenticated
                ) {
                    Text("PING")
                }

                FilledTonalButton(
                    onClick = { onCommand("STATUS") },
                    enabled = state.authenticated
                ) {
                    Icon(
                        Icons.Rounded.Refresh,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Atualizar status")
                }

                OutlinedButton(
                    onClick = { onCommand("REBOOT") },
                    enabled = state.authenticated
                ) {
                    Text("Reiniciar S3")
                }
            }

            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ConnectionHero(
    state: PrivateLinkUiState,
    onScan: () -> Unit
) {
    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusColor = when (state.linkState) {
                LinkState.Ready -> Color(0xFF5EEAD4)
                LinkState.Error -> MaterialTheme.colorScheme.error
                LinkState.Updating -> Color(0xFFA78BFA)
                else -> MaterialTheme.colorScheme.secondary
            }

            Box(
                modifier = Modifier
                    .size(46.dp)
                    .background(
                        statusColor.copy(alpha = 0.14f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector =
                        if (state.authenticated) Icons.Rounded.CheckCircle
                        else Icons.Rounded.Bluetooth,
                    contentDescription = null,
                    tint = statusColor
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    state.statusText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )

                val detail = when {
                    state.authenticated ->
                        state.connectedAddress ?: "Canal seguro ativo"
                    state.connectedAddress != null ->
                        state.connectedAddress
                    else ->
                        "ESP32-S3 N16R8 • BLE seguro"
                }

                Text(
                    detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.linkState == LinkState.Scanning ||
            state.linkState == LinkState.Connecting ||
            state.linkState == LinkState.Authenticating ||
            state.linkState == LinkState.Pairing
        ) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth()
            )
        } else if (!state.authenticated) {
            Button(
                onClick = onScan,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Rounded.BluetoothSearching,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text("Buscar meu ESP32-S3")
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("HMAC autenticado") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                AssistChip(
                    onClick = {},
                    label = { Text("BLE seguro") }
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: NearbyDevice,
    onConnect: (NearbyDevice) -> Unit
) {
    ModernCard {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )

            Spacer(Modifier.width(12.dp))

            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    "ESP32-S3 PrivateLink",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${device.address} • ${device.rssi} dBm",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = { onConnect(device) }
            ) {
                Text("Conectar")
            }
        }
    }
}

@Composable
private fun ResearchScreen(
    telemetry: Telemetry
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))

            ModernCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.Science,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(34.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Observatório de rádio",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Levantamento passivo de Wi‑Fi e BLE",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                AssistChip(
                    onClick = {},
                    label = { Text("PASSIVO • sem transmissão ofensiva") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.Security,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                )
            }
        }

        if (telemetry.wifiAp == null && telemetry.bleSeen == null) {
            item {
                EmptyResearchCard()
            }
        } else {
            item {
                SectionTitle(
                    "Wi‑Fi",
                    "Ambiente 2,4 GHz observado pelo ESP32-S3"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        Modifier.weight(1f),
                        Icons.Rounded.Wifi,
                        "APs",
                        telemetry.wifiAp?.toString() ?: "-"
                    )
                    MetricCard(
                        Modifier.weight(1f),
                        Icons.Rounded.Lock,
                        "Seguras",
                        telemetry.wifiSecure?.toString() ?: "-"
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        Modifier.weight(1f),
                        Icons.Rounded.Info,
                        "Abertas",
                        telemetry.wifiOpen?.toString() ?: "-"
                    )
                    MetricCard(
                        Modifier.weight(1f),
                        Icons.Rounded.SignalCellularAlt,
                        "Melhor RSSI",
                        telemetry.wifiBest?.let { "$it dBm" } ?: "-"
                    )
                }

                Spacer(Modifier.height(10.dp))

                ModernCard {
                    Text(
                        "Canal com maior presença",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        telemetry.wifiPeakChannel?.let {
                            "Canal $it • ${telemetry.wifiPeakCount ?: 0} APs"
                        } ?: "-",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item {
                SectionTitle(
                    "Bluetooth Low Energy",
                    "Advertising observado passivamente"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        Modifier.weight(1f),
                        Icons.Rounded.Bluetooth,
                        "Anunciantes",
                        telemetry.bleSeen?.toString() ?: "-"
                    )
                    MetricCard(
                        Modifier.weight(1f),
                        Icons.Rounded.SignalCellularAlt,
                        "Melhor RSSI",
                        telemetry.bleBest?.let { "$it dBm" } ?: "-"
                    )
                }
            }

            item {
                ModernCard {
                    Text(
                        "Último levantamento",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatAge(telemetry.surveyAgeMs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun EmptyResearchCard() {
    ModernCard {
        Icon(
            Icons.Rounded.Science,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(38.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Aguardando telemetria Research",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            "A tela já está preparada para firmware 1.1.0+ com survey passivo de Wi‑Fi e BLE.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun UpdateScreen(
    state: PrivateLinkUiState,
    onPickFirmware: () -> Unit,
    onAbortOta: () -> Unit
) {
    val updating = state.linkState == LinkState.Updating

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))

            ModernCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Rounded.SystemUpdateAlt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(38.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Atualização OTA",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "Atualize sem cabo USB",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (state.otaFileName != null) {
                    Text(
                        state.otaFileName,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(8.dp))
                }

                LinearProgressIndicator(
                    progress = { state.otaProgress },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    String.format(
                        Locale.US,
                        "%.1f%%",
                        state.otaProgress * 100f
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))

                if (updating) {
                    OutlinedButton(
                        onClick = onAbortOta,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cancelar atualização")
                    }
                } else {
                    Button(
                        onClick = onPickFirmware,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = state.authenticated
                    ) {
                        Icon(
                            Icons.Rounded.UploadFile,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Selecionar firmware .bin")
                    }
                }
            }
        }

        item {
            SectionTitle(
                "Validação",
                "Proteções aplicadas antes do reboot"
            )

            ModernCard {
                SecurityRow(
                    Icons.Rounded.Security,
                    "HMAC-SHA256",
                    "Autenticidade do firmware"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                SecurityRow(
                    Icons.Rounded.CheckCircle,
                    "SHA-256",
                    "Integridade da imagem"
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                SecurityRow(
                    Icons.Rounded.Memory,
                    "OTA dual-slot",
                    "Novo app é gravado no slot inativo"
                )
            }
        }

        item {
            Text(
                "Use somente o .bin de aplicação para OTA. Imagens FULL/merged continuam sendo destinadas ao Auto Flasher por USB.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ConsoleScreen(logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        ModernCard {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        "Console técnico",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "${logs.size} eventos recentes",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF070C10)
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { line ->
                    Text(
                        line,
                        color = Color(0xFFC5D4DB),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingsScreen(
    state: PrivateLinkUiState,
    onClearKey: () -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(4.dp))

            SectionTitle(
                "Segurança",
                "Identidade e credenciais deste telefone"
            )

            ModernCard {
                SecurityRow(
                    Icons.Rounded.VpnKey,
                    "Chave HMAC",
                    "Protegida pelo Android Keystore"
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                SecurityRow(
                    Icons.Rounded.Bluetooth,
                    "Pareamento BLE",
                    "Passkey ${Protocol.PAIRING_PASSKEY}"
                )

                Spacer(Modifier.height(16.dp))

                OutlinedButton(
                    onClick = onClearKey,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Rounded.DeleteForever,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Trocar chave privada")
                }
            }
        }

        item {
            SectionTitle(
                "Plataforma",
                "Base organizada para expansões futuras"
            )

            ModernCard {
                Text(
                    "PrivateLink Mobile 2.0",
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Arquitetura separada em protocolo, BLE, segurança, estado e interface. Novos módulos podem entrar sem transformar o app em uma única tela monolítica.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(14.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = {},
                        label = { Text("N16R8") }
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text("BLE") }
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text("OTA") }
                    )
                    AssistChip(
                        onClick = {},
                        label = { Text("Research") }
                    )
                }
            }
        }

        item {
            SectionTitle(
                "Sessão",
                "Estado de segurança atual"
            )

            ModernCard {
                val ok = state.authenticated
                SecurityRow(
                    if (ok) Icons.Rounded.CheckCircle
                    else Icons.Rounded.ErrorOutline,
                    if (ok) "Autenticado" else "Desconectado",
                    state.connectedAddress ?: "Nenhum endereço ativo"
                )
            }

            Spacer(Modifier.height(28.dp))
        }
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
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ModernCard(
    content: @Composable Column.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
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
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatUptime(ms: Long?): String {
    if (ms == null) return "-"
    val seconds = ms / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60

    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "${seconds}s"
    }
}

private fun formatHeap(bytes: Long?): String {
    if (bytes == null) return "-"
    return String.format(Locale.US, "%.0f KB", bytes / 1024.0)
}

private fun formatAge(ms: Long?): String {
    if (ms == null) return "-"
    if (ms < 1000) return "agora"
    val seconds = ms / 1000
    return if (seconds < 60) {
        "há ${seconds}s"
    } else {
        "há ${seconds / 60} min"
    }
}
