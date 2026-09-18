package com.lendas.privatelink.ble

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lendas.privatelink.core.Protocol
import java.util.ArrayDeque
import java.util.UUID

/**
 * Low-level BLE session controller.
 *
 * All state transitions and Android BLE API calls are serialized on the main
 * looper. Every GATT connection attempt receives a monotonically increasing
 * generation id; callbacks from stale GATT objects are ignored.
 */
internal class BleSessionController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onStatus(text: String)
        fun onLog(message: String)
        fun onAddress(address: String?)
        fun onTransportReady()
        fun onNotification(value: ByteArray)
        fun onDisconnected()
        fun onFatalError(message: String)
        fun onWriteFailure(channel: Channel, status: Int)
    }

    enum class Channel {
        CONTROL,
        OTA
    }

    private enum class Phase {
        IDLE,
        CONNECTING,
        BONDING,
        MTU,
        DISCOVERING,
        SUBSCRIBING,
        TRANSPORT_READY,
        DISCONNECTING,
        ERROR
    }

    private data class WriteTask(
        val channel: Channel,
        val payload: ByteArray,
        val onSuccess: (() -> Unit)? = null,
        var attempts: Int = 0
    )

    private val main = Handler(Looper.getMainLooper())
    private var phase = Phase.IDLE
    private var closed = false
    private var receiverRegistered = false
    private var manualDisconnect = false

    private var generation = 0
    private var activeGatt: BluetoothGatt? = null
    private var pendingDevice: BluetoothDevice? = null
    private var connected = false

    private var controlChar: BluetoothGattCharacteristic? = null
    private var responseChar: BluetoothGattCharacteristic? = null
    private var otaChar: BluetoothGattCharacteristic? = null

    private val writes = ArrayDeque<WriteTask>()
    private var writeInFlight = false

    init {
        registerBondReceiver()
    }

    fun connect(device: BluetoothDevice) = onMain {
        if (closed) return@onMain
        if (!hasBlePermissions()) {
            fatal("Permissões Bluetooth ausentes")
            return@onMain
        }

        manualDisconnect = false
        pendingDevice = device
        clearTransportState(closeGatt = true)

        val nextGeneration = ++generation
        openGatt(device, nextGeneration)
    }

    fun disconnect() = onMain {
        manualDisconnect = true
        ++generation
        phase = Phase.DISCONNECTING
        clearWriteQueue()
        clearTransportState(closeGatt = true)
        pendingDevice = null
        phase = Phase.IDLE
        listener.onAddress(null)
        listener.onDisconnected()
        listener.onStatus("Nó ESPhub desconectado")
    }

    fun close() = onMain {
        if (closed) return@onMain
        closed = true
        manualDisconnect = true
        ++generation
        clearWriteQueue()
        clearTransportState(closeGatt = true)
        pendingDevice = null
        unregisterBondReceiver()
    }

    fun writeControl(
        payload: ByteArray,
        onSuccess: (() -> Unit)? = null
    ) = onMain {
        enqueueWrite(Channel.CONTROL, payload, onSuccess)
    }

    fun writeOta(
        payload: ByteArray,
        onSuccess: (() -> Unit)? = null
    ) = onMain {
        enqueueWrite(Channel.OTA, payload, onSuccess)
    }

    private fun registerBondReceiver() {
        if (receiverRegistered) return

        runCatching {
            ContextCompat.registerReceiver(
                context,
                bondReceiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
            receiverRegistered = true
        }.onFailure { error ->
            listener.onLog(
                "Falha ao registrar receiver BLE: " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
        }
    }

    private fun unregisterBondReceiver() {
        if (!receiverRegistered) return
        runCatching {
            context.unregisterReceiver(bondReceiver)
        }
        receiverRegistered = false
    }

    private fun openGatt(
        device: BluetoothDevice,
        expectedGeneration: Int
    ) {
        if (!isGenerationCurrent(expectedGeneration)) return

        phase = Phase.CONNECTING
        listener.onStatus("Conectando ao nó ESPhub...")
        listener.onAddress(device.address)
        listener.onLog(
            "GATT[" + expectedGeneration + "] conectando em " + device.address
        )

        val callback = createGattCallback(expectedGeneration)

        val result = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
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
        }

        result.onSuccess { created ->
            if (!isGenerationCurrent(expectedGeneration)) {
                runCatching { created?.close() }
                return@onSuccess
            }

            if (created == null) {
                fatal("Android não criou a sessão GATT")
                return@onSuccess
            }

            activeGatt = created
        }.onFailure { error ->
            fatal(
                "Falha ao abrir conexão BLE: " +
                    error.javaClass.simpleName
            )
            listener.onLog(
                "connectGatt: " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
        }
    }

    private fun beginBonding(
        gatt: BluetoothGatt,
        expectedGeneration: Int
    ) {
        if (!isCurrent(expectedGeneration, gatt)) return

        val device = gatt.device
        val bondState = safeValue(
            "bondState",
            BluetoothDevice.BOND_NONE
        ) {
            device.bondState
        }

        if (bondState == BluetoothDevice.BOND_BONDED) {
            startMtuNegotiation(gatt, expectedGeneration)
            return
        }

        phase = Phase.BONDING
        listener.onStatus(
            "Pareando • confirme o código " + Protocol.PAIRING_PASSKEY
        )
        listener.onLog("Iniciando pareamento seguro.")

        val started = safeValue(
            "createBond",
            false
        ) {
            when (device.bondState) {
                BluetoothDevice.BOND_BONDED,
                BluetoothDevice.BOND_BONDING -> true
                else -> device.createBond()
            }
        }

        if (!started) {
            fatal("Android não iniciou o pareamento BLE")
            return
        }

        main.postDelayed({
            if (
                isGenerationCurrent(expectedGeneration) &&
                phase == Phase.BONDING
            ) {
                val state = safeValue(
                    "bondState-timeout",
                    BluetoothDevice.BOND_NONE
                ) {
                    device.bondState
                }

                if (state != BluetoothDevice.BOND_BONDED) {
                    fatal("Pareamento BLE expirou")
                }
            }
        }, 20_000)
    }

    private fun startMtuNegotiation(
        gatt: BluetoothGatt,
        expectedGeneration: Int
    ) {
        if (!isCurrent(expectedGeneration, gatt)) return

        phase = Phase.MTU
        listener.onStatus("Pareado • negociando MTU...")

        safeCall("requestConnectionPriority") {
            gatt.requestConnectionPriority(
                BluetoothGatt.CONNECTION_PRIORITY_HIGH
            )
        }

        val requested = safeValue(
            "requestMtu",
            false
        ) {
            gatt.requestMtu(247)
        }

        if (!requested) {
            listener.onLog("requestMtu não iniciou; seguindo com MTU padrão.")
            main.post {
                startServiceDiscovery(gatt, expectedGeneration)
            }
            return
        }

        main.postDelayed({
            if (
                isCurrent(expectedGeneration, gatt) &&
                phase == Phase.MTU
            ) {
                listener.onLog(
                    "Timeout de MTU; seguindo para discovery sem bloquear a sessão."
                )
                startServiceDiscovery(gatt, expectedGeneration)
            }
        }, 2_500)
    }

    private fun startServiceDiscovery(
        gatt: BluetoothGatt,
        expectedGeneration: Int
    ) {
        if (!isCurrent(expectedGeneration, gatt)) return
        if (phase == Phase.DISCOVERING || phase == Phase.SUBSCRIBING) return

        phase = Phase.DISCOVERING
        listener.onStatus("Descobrindo serviços BLE...")

        val started = safeValue(
            "discoverServices",
            false
        ) {
            gatt.discoverServices()
        }

        if (!started) {
            fatal("Não foi possível iniciar a descoberta de serviços BLE")
        }
    }

    private fun configureNotifications(
        gatt: BluetoothGatt,
        expectedGeneration: Int
    ) {
        if (!isCurrent(expectedGeneration, gatt)) return

        val service = safeValue(
            "getService",
            null
        ) {
            gatt.getService(Protocol.SERVICE_UUID)
        }

        if (service == null) {
            fatal("Serviço ESPhub não encontrado")
            return
        }

        val control = service.getCharacteristic(Protocol.CONTROL_UUID)
        val response = service.getCharacteristic(Protocol.RESPONSE_UUID)
        val ota = service.getCharacteristic(Protocol.OTA_UUID)

        if (control == null || response == null || ota == null) {
            fatal("Characteristics ESPhub incompletas")
            return
        }

        controlChar = control
        responseChar = response
        otaChar = ota
        phase = Phase.SUBSCRIBING
        listener.onStatus("Ativando canal de notificações...")

        val localEnabled = safeValue(
            "setCharacteristicNotification",
            false
        ) {
            gatt.setCharacteristicNotification(response, true)
        }

        if (!localEnabled) {
            fatal("Android recusou notificações BLE")
            return
        }

        val cccd = response.getDescriptor(Protocol.CCCD_UUID)
        if (cccd == null) {
            fatal("CCCD de notificações não encontrado")
            return
        }

        val started = safeValue(
            "writeDescriptor(CCCD)",
            false
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeDescriptor(
                    cccd,
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value =
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        if (!started) {
            fatal("Não foi possível habilitar notificações BLE")
            return
        }

        main.postDelayed({
            if (
                isCurrent(expectedGeneration, gatt) &&
                phase == Phase.SUBSCRIBING
            ) {
                fatal("Timeout habilitando notificações BLE")
            }
        }, 5_000)
    }

    private fun markTransportReady(
        gatt: BluetoothGatt,
        expectedGeneration: Int
    ) {
        if (!isCurrent(expectedGeneration, gatt)) return
        if (phase == Phase.TRANSPORT_READY) return

        phase = Phase.TRANSPORT_READY
        listener.onStatus("Canal BLE pronto • autenticando...")
        listener.onTransportReady()
    }

    private fun enqueueWrite(
        channel: Channel,
        payload: ByteArray,
        onSuccess: (() -> Unit)?
    ) {
        if (closed || !connected || phase != Phase.TRANSPORT_READY) {
            listener.onLog(
                "Write ignorado: transporte BLE ainda não está pronto."
            )
            return
        }

        writes.addLast(
            WriteTask(
                channel = channel,
                payload = payload.copyOf(),
                onSuccess = onSuccess
            )
        )
        pumpWrites()
    }

    private fun pumpWrites() {
        if (writeInFlight || writes.isEmpty()) return

        val gatt = activeGatt ?: return
        if (!connected || phase != Phase.TRANSPORT_READY) return

        val task = writes.first()
        val characteristic =
            when (task.channel) {
                Channel.CONTROL -> controlChar
                Channel.OTA -> otaChar
            }

        if (characteristic == null) {
            writes.removeFirst()
            listener.onWriteFailure(task.channel, -2)
            return
        }

        writeInFlight = true

        val status = safeValue(
            "writeCharacteristic(" + task.channel.name + ")",
            -1
        ) {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(
                    characteristic,
                    task.payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType =
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                characteristic.value = task.payload
                @Suppress("DEPRECATION")
                if (gatt.writeCharacteristic(characteristic)) 0 else -1
            }
        }

        val started =
            if (Build.VERSION.SDK_INT >= 33) {
                status == BluetoothStatusCodes.SUCCESS
            } else {
                status == 0
            }

        if (!started) {
            writeInFlight = false
            retryOrFailWrite(task, status)
        }
    }

    private fun retryOrFailWrite(
        task: WriteTask,
        status: Int
    ) {
        task.attempts += 1
        listener.onLog(
            "Write " + task.channel.name +
                " recusado. status=" + status +
                " tentativa=" + task.attempts +
                "/" + Protocol.MAX_WRITE_RETRIES
        )

        if (task.attempts >= Protocol.MAX_WRITE_RETRIES) {
            if (writes.isNotEmpty() && writes.first() === task) {
                writes.removeFirst()
            }
            listener.onWriteFailure(task.channel, status)
            pumpWrites()
            return
        }

        val currentGeneration = generation
        main.postDelayed({
            if (
                isGenerationCurrent(currentGeneration) &&
                connected &&
                phase == Phase.TRANSPORT_READY
            ) {
                pumpWrites()
            }
        }, (180L * task.attempts).coerceAtMost(1_800L))
    }

    private fun onWriteComplete(
        status: Int,
        characteristicUuid: UUID
    ) {
        val task = if (writes.isEmpty()) null else writes.first()
        if (task == null) {
            writeInFlight = false
            return
        }

        val expectedUuid =
            when (task.channel) {
                Channel.CONTROL -> Protocol.CONTROL_UUID
                Channel.OTA -> Protocol.OTA_UUID
            }

        if (characteristicUuid != expectedUuid) {
            listener.onLog(
                "Callback de write inesperado ignorado: " + characteristicUuid
            )
            return
        }

        writeInFlight = false

        if (status == BluetoothGatt.GATT_SUCCESS) {
            writes.removeFirst()
            task.onSuccess?.invoke()
            pumpWrites()
        } else {
            retryOrFailWrite(task, status)
        }
    }

    private fun clearWriteQueue() {
        writes.clear()
        writeInFlight = false
    }

    private fun clearTransportState(closeGatt: Boolean) {
        connected = false
        controlChar = null
        responseChar = null
        otaChar = null
        clearWriteQueue()

        val old = activeGatt
        activeGatt = null

        if (closeGatt && old != null) {
            safeCall("gatt.disconnect") {
                old.disconnect()
            }
            safeCall("gatt.close") {
                old.close()
            }
        }
    }

    private fun reconnectAfterBond(
        device: BluetoothDevice
    ) {
        clearTransportState(closeGatt = true)
        val nextGeneration = ++generation
        openGatt(device, nextGeneration)
    }

    private fun createGattCallback(
        expectedGeneration: Int
    ): BluetoothGattCallback {
        return object : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int
            ) = onMain {
                if (!isCurrent(expectedGeneration, gatt)) {
                    listener.onLog(
                        "Callback GATT obsoleto descartado [gen=" +
                            expectedGeneration + "]."
                    )
                    safeCall("staleGatt.close") {
                        gatt.close()
                    }
                    return@onMain
                }

                if (
                    status == BluetoothGatt.GATT_SUCCESS &&
                    newState == BluetoothProfile.STATE_CONNECTED
                ) {
                    connected = true
                    listener.onAddress(gatt.device.address)
                    listener.onLog(
                        "BLE conectado [gen=" + expectedGeneration + "]."
                    )
                    beginBonding(gatt, expectedGeneration)
                    return@onMain
                }

                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    listener.onLog(
                        "BLE desconectado. status=" + status +
                            " phase=" + phase.name
                    )

                    val device = gatt.device
                    if (phase == Phase.BONDING && !manualDisconnect) {
                        val bondState = safeValue(
                            "bondState-disconnect",
                            BluetoothDevice.BOND_NONE
                        ) {
                            device.bondState
                        }

                        if (bondState == BluetoothDevice.BOND_BONDING) {
                            listener.onStatus("Pareamento em andamento...")
                            safeCall("bondingGatt.close") {
                                gatt.close()
                            }
                            if (activeGatt === gatt) {
                                activeGatt = null
                            }
                            return@onMain
                        }

                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            reconnectAfterBond(device)
                            return@onMain
                        }
                    }

                    if (!manualDisconnect) {
                        fatal(
                            "BLE desconectou durante " + phase.name +
                                " • status " + status
                        )
                    } else {
                        phase = Phase.IDLE
                        listener.onDisconnected()
                    }
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int
            ) = onMain {
                if (!isCurrent(expectedGeneration, gatt)) return@onMain
                listener.onLog(
                    "MTU=" + mtu + " status=" + status
                )
                if (phase == Phase.MTU) {
                    startServiceDiscovery(gatt, expectedGeneration)
                }
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int
            ) = onMain {
                if (!isCurrent(expectedGeneration, gatt)) return@onMain

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    fatal("Falha ao descobrir serviços BLE • " + status)
                    return@onMain
                }

                configureNotifications(gatt, expectedGeneration)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) = onMain {
                if (!isCurrent(expectedGeneration, gatt)) return@onMain
                if (descriptor.uuid != Protocol.CCCD_UUID) return@onMain

                listener.onLog(
                    "CCCD status=" + status
                )

                if (status == BluetoothGatt.GATT_SUCCESS) {
                    markTransportReady(gatt, expectedGeneration)
                } else {
                    fatal("Falha ao habilitar notificações BLE • " + status)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) = onMain {
                if (!isCurrent(expectedGeneration, gatt)) return@onMain
                if (characteristic.uuid == Protocol.RESPONSE_UUID) {
                    listener.onNotification(value.copyOf())
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) = onMain {
                if (Build.VERSION.SDK_INT >= 33) return@onMain
                if (!isCurrent(expectedGeneration, gatt)) return@onMain
                if (characteristic.uuid == Protocol.RESPONSE_UUID) {
                    listener.onNotification(
                        characteristic.value?.copyOf() ?: return@onMain
                    )
                }
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) = onMain {
                if (!isCurrent(expectedGeneration, gatt)) return@onMain
                onWriteComplete(status, characteristic.uuid)
            }
        }
    }

    private val bondReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                receiverContext: Context?,
                intent: Intent?
            ) {
                onMain {
                    handleBondIntent(intent)
                }
            }
        }

    private fun handleBondIntent(intent: Intent?) {
        if (closed) return
        if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return

        val device = runCatching {
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE
                )
            }
        }.getOrNull() ?: return

        val pending = pendingDevice ?: return
        if (device.address != pending.address) return

        val bondState = intent.getIntExtra(
            BluetoothDevice.EXTRA_BOND_STATE,
            BluetoothDevice.ERROR
        )

        when (bondState) {
            BluetoothDevice.BOND_BONDING -> {
                if (phase == Phase.BONDING) {
                    listener.onStatus("Aguardando confirmação do pareamento...")
                    listener.onLog("Android informou BOND_BONDING.")
                }
            }

            BluetoothDevice.BOND_BONDED -> {
                listener.onLog("Pareamento concluído.")

                val currentGatt = activeGatt
                if (
                    connected &&
                    currentGatt != null &&
                    currentGatt.device.address == device.address
                ) {
                    startMtuNegotiation(currentGatt, generation)
                } else if (!manualDisconnect) {
                    reconnectAfterBond(device)
                }
            }

            BluetoothDevice.BOND_NONE -> {
                if (phase == Phase.BONDING && !manualDisconnect) {
                    fatal("Pareamento cancelado ou rejeitado")
                }
            }
        }
    }

    private fun fatal(message: String) {
        if (closed) return
        phase = Phase.ERROR
        listener.onStatus(message)
        listener.onFatalError(message)
        listener.onLog("BLE ERROR: " + message)

        clearWriteQueue()

        val old = activeGatt
        activeGatt = null
        connected = false

        if (old != null) {
            safeCall("fatal.disconnect") {
                old.disconnect()
            }
            safeCall("fatal.close") {
                old.close()
            }
        }
    }

    private fun isGenerationCurrent(
        expectedGeneration: Int
    ): Boolean =
        !closed && expectedGeneration == generation

    private fun isCurrent(
        expectedGeneration: Int,
        gatt: BluetoothGatt
    ): Boolean =
        isGenerationCurrent(expectedGeneration) &&
            activeGatt === gatt

    private fun hasBlePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= 31) {
            context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runGuarded(block)
        } else {
            main.post {
                runGuarded(block)
            }
        }
    }

    private fun runGuarded(block: () -> Unit) {
        runCatching(block).onFailure { error ->
            listener.onLog(
                "BLE exception contida: " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
            if (!closed) {
                fatal(
                    "Falha interna BLE: " +
                        error.javaClass.simpleName
                )
            }
        }
    }

    private fun safeCall(
        name: String,
        block: () -> Unit
    ) {
        runCatching(block).onFailure { error ->
            listener.onLog(
                name + ": " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
        }
    }

    private fun <T> safeValue(
        name: String,
        fallback: T,
        block: () -> T
    ): T =
        runCatching(block).onFailure { error ->
            listener.onLog(
                name + ": " +
                    error.javaClass.simpleName + ": " +
                    (error.message ?: "")
            )
        }.getOrDefault(fallback)
}
