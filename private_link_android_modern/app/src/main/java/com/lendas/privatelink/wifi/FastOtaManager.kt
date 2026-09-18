package com.lendas.privatelink.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import com.lendas.privatelink.core.Crypto
import com.lendas.privatelink.core.FastOtaCredentials
import com.lendas.privatelink.core.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class FastOtaManager(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onWifiRequestStarted()
        fun onWifiConnected(ssid: String)
        fun onProgress(
            transferred: Long,
            total: Long,
            bytesPerSecond: Long
        )
        fun onVerifying()
        fun onSuccess()
        fun onFailure(message: String)
        fun onFinished()
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var worker: Thread? = null
    private val cancelled = AtomicBoolean(false)

    @Synchronized
    fun start(
        credentials: FastOtaCredentials,
        firmware: ByteArray,
        fileName: String,
        privateKey: ByteArray
    ) {
        cancel(silent = true)
        cancelled.set(false)

        val specifierBuilder = WifiNetworkSpecifier.Builder()
            .setSsid(credentials.ssid)
            .setWpa2Passphrase(credentials.password)

        if (credentials.hidden) {
            specifierBuilder.setIsHiddenSsid(true)
        }

        if (Build.VERSION.SDK_INT >= 34 && credentials.channel in 1..13) {
            // 2.4 GHz center frequency: 2412 + 5*(channel-1)
            val frequencyMhz = 2412 + 5 * (credentials.channel - 1)
            specifierBuilder.setPreferredChannelsFrequenciesMhz(
                intArrayOf(frequencyMhz)
            )
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (cancelled.get()) return
                listener.onWifiConnected(credentials.ssid)

                worker = Thread {
                    transfer(
                        network = network,
                        credentials = credentials,
                        firmware = firmware,
                        fileName = fileName,
                        privateKey = privateKey
                    )
                }.also {
                    it.name = "PrivateLink-FastOTA"
                    it.start()
                }
            }

            override fun onUnavailable() {
                if (cancelled.get()) return
                listener.onFailure(
                    "Android não conseguiu abrir o canal Wi‑Fi privado."
                )
                finish()
            }

            override fun onLost(network: Network) {
                if (cancelled.get()) return
                if (worker?.isAlive == true) {
                    listener.onFailure(
                        "Canal Wi‑Fi privado foi perdido durante a atualização."
                    )
                    cancel(silent = true)
                }
            }
        }

        networkCallback = callback
        listener.onWifiRequestStarted()

        try {
            connectivityManager.requestNetwork(
                request,
                callback,
                Protocol.FAST_OTA_CONNECT_TIMEOUT_MS.toInt()
            )
        } catch (error: Exception) {
            listener.onFailure(
                "Falha ao solicitar rede Wi‑Fi privada: ${error.message}"
            )
            finish()
        }
    }

    @Synchronized
    fun cancel(silent: Boolean = false) {
        cancelled.set(true)

        runCatching {
            worker?.interrupt()
        }

        worker = null

        networkCallback?.let { callback ->
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }

        networkCallback = null

        if (!silent) {
            listener.onFailure("Atualização rápida cancelada.")
        }

        listener.onFinished()
    }

    private fun transfer(
        network: Network,
        credentials: FastOtaCredentials,
        firmware: ByteArray,
        fileName: String,
        privateKey: ByteArray
    ) {
        var socket: Socket? = null

        try {
            if (cancelled.get()) return

            val shaHex = Crypto.bytesToHex(Crypto.sha256(firmware))
            val hmacHex = Crypto.bytesToHex(
                Crypto.hmacSha256(privateKey, firmware)
            )

            socket = network.socketFactory.createSocket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 15_000
            socket.sendBufferSize = 256 * 1024
            socket.receiveBufferSize = 64 * 1024

            socket.connect(
                InetSocketAddress(credentials.host, credentials.port),
                8_000
            )

            val input = BufferedInputStream(
                socket.getInputStream(),
                64 * 1024
            )
            val output = BufferedOutputStream(
                socket.getOutputStream(),
                256 * 1024
            )

            val safeName = fileName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .take(96)

            val header =
                "PL_OTA_V1|${firmware.size}|$shaHex|$hmacHex|" +
                    "${credentials.tokenHex}|$safeName\n"

            output.write(header.toByteArray(StandardCharsets.US_ASCII))
            output.flush()

            val ready = readAsciiLine(input, 256)

            if (ready != "READY") {
                throw IllegalStateException(
                    "ESP32 recusou a sessão rápida: $ready"
                )
            }

            val startedNs = System.nanoTime()
            var transferred = 0
            var lastReportNs = startedNs
            var lastReportBytes = 0

            val chunkSize = 64 * 1024

            while (transferred < firmware.size) {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    throw InterruptedException("cancelled")
                }

                val size = minOf(
                    chunkSize,
                    firmware.size - transferred
                )

                output.write(firmware, transferred, size)
                transferred += size

                val nowNs = System.nanoTime()

                if (
                    transferred == firmware.size ||
                    nowNs - lastReportNs >= 200_000_000L
                ) {
                    output.flush()

                    val elapsedNs = max(
                        1L,
                        nowNs - lastReportNs
                    )

                    val bytesSince =
                        transferred - lastReportBytes

                    val speed = (
                        bytesSince.toDouble() *
                            1_000_000_000.0 /
                            elapsedNs.toDouble()
                        ).toLong()

                    listener.onProgress(
                        transferred.toLong(),
                        firmware.size.toLong(),
                        speed
                    )

                    lastReportNs = nowNs
                    lastReportBytes = transferred
                }
            }

            output.flush()
            listener.onVerifying()

            val result = readAsciiLine(input, 256)

            if (!result.startsWith("OK")) {
                throw IllegalStateException(
                    "Validação do ESP32 falhou: $result"
                )
            }

            listener.onProgress(
                firmware.size.toLong(),
                firmware.size.toLong(),
                averageSpeed(
                    firmware.size.toLong(),
                    System.nanoTime() - startedNs
                )
            )

            listener.onSuccess()
        } catch (error: InterruptedException) {
            if (!cancelled.get()) {
                listener.onFailure("Transferência Wi‑Fi interrompida.")
            }
        } catch (error: Exception) {
            if (!cancelled.get()) {
                listener.onFailure(
                    error.message ?: "Falha na atualização Wi‑Fi."
                )
            }
        } finally {
            runCatching { socket?.close() }
            finish()
        }
    }

    @Synchronized
    private fun finish() {
        networkCallback?.let { callback ->
            runCatching {
                connectivityManager.unregisterNetworkCallback(callback)
            }
        }

        networkCallback = null
        worker = null
        listener.onFinished()
    }

    private fun readAsciiLine(
        input: BufferedInputStream,
        maxBytes: Int
    ): String {
        val data = ByteArray(maxBytes)
        var count = 0

        while (count < maxBytes) {
            val value = input.read()

            if (value < 0) {
                throw IllegalStateException("Conexão fechada pelo ESP32.")
            }

            if (value == '\n'.code) {
                break
            }

            if (value != '\r'.code) {
                data[count++] = value.toByte()
            }
        }

        if (count == maxBytes) {
            throw IllegalStateException("Resposta Wi‑Fi inválida.")
        }

        return String(
            data,
            0,
            count,
            StandardCharsets.US_ASCII
        )
    }

    private fun averageSpeed(
        bytes: Long,
        elapsedNs: Long
    ): Long {
        if (elapsedNs <= 0) return 0

        return (
            bytes.toDouble() *
                1_000_000_000.0 /
                elapsedNs.toDouble()
            ).toLong()
    }
}
