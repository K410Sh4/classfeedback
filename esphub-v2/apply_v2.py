from pathlib import Path

def edit(name, old, new, expected=1):
    p=Path(name); s=p.read_text()
    assert s.count(old)==expected, (name,old[:60],s.count(old))
    p.write_text(s.replace(old,new))

def write(name,body):
    p=Path(name); p.parent.mkdir(parents=True,exist_ok=True); p.write_text(body)

fw='firmware/src/main.cpp'
edit(fw,'void sendTelemetry() {','''void sendTelemetry() {
    if (sequence >= 0xfffffff0UL) {
        if (server && activeConn != kNoConnection) server->disconnect(activeConn);
        return;
    }''')
edit(fw,'    NimBLEDevice::init(DEVICE_NAME);','''    NimBLEDevice::init(DEVICE_NAME);
    NimBLEDevice::setMTU(185);''')
radar='firmware/src/presence_radar.cpp'
edit(radar,'    config.dump_ack_en = false;\n','',2)
edit(radar,'    printApCredentials();\n#else','    Serial.println("[PresenceRadar] Use RADAR AP over physical USB to retrieve credentials.");\n#else')
ini='firmware/platformio.ini'
edit(ini,'board_upload.flash_size = 16MB','board_upload.flash_size = 16MB\nboard_build.flash_size = 16MB')
edit(ini,'board = esp32-c6-devkitm-1','board = esp32-c6-devkitm-1\nboard_build.partitions = partitions_4MB_c6.csv')
write('firmware/partitions_4MB_c6.csv','''# C6 4 MB, two 1.625 MiB OTA slots
# Name, Type, SubType, Offset, Size, Flags
nvs, data, nvs, 0x9000, 0x5000,
otadata, data, ota, 0xe000, 0x2000,
app0, app, ota_0, 0x10000, 0x1A0000,
app1, app, ota_1, 0x1B0000, 0x1A0000,
spiffs, data, spiffs, 0x350000, 0xA0000,
''')
ble='android/app/src/main/java/com/esphub/app/ble/BleHubManager.kt'
edit(ble,'import android.os.ParcelUuid','import android.os.ParcelUuid\nimport android.os.Handler\nimport android.os.Looper')
edit(ble,'        private var closed = false','''        private var closed = false
        private var mtuReady = false
        private var lastAcceptedSequence = -1L
        private val handler = Handler(Looper.getMainLooper())''')
edit(ble,'''            closed = true
            try { gatt?.disconnect()''','''            closed = true
            sessionKey?.fill(0)
            sessionKey = null
            lastAcceptedSequence = -1L
            try { gatt?.disconnect()''')
edit(ble,'''                        listener.onNodeState(node, "Conectado; descobrindo serviço…")
                        gatt.discoverServices()''','''                        listener.onNodeState(node, "Conectado; negociando MTU…")
                        mtuReady = false
                        if (!gatt.requestMtu(185)) fail("Falha ao negociar MTU BLE.")
                        else handler.postDelayed({
                            if (!closed && !mtuReady) fail("MTU BLE não negociado em 8 s.")
                        }, 8000L)''')
anchor='''            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {'''
edit(ble,anchor,'''            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (closed || mtuReady) return
                if (status != BluetoothGatt.GATT_SUCCESS || mtu < 64) {
                    fail("MTU insuficiente para o desafio BLE de 49 bytes: $mtu")
                    return
                }
                mtuReady = true
                listener.onNodeState(node, "MTU $mtu • descobrindo serviços…")
                if (!gatt.discoverServices()) fail("Não iniciou descoberta GATT.")
            }

'''+anchor)
edit(ble,'''                    } else {
                        listener.onTelemetry(node, telemetry)
                    }
''','''                    } else if (telemetry.sequence <= lastAcceptedSequence) {
                        listener.onError(node, "Telemetria repetida ou fora de ordem descartada.")
                    } else {
                        lastAcceptedSequence = telemetry.sequence
                        listener.onTelemetry(node, telemetry)
                    }
''')
app='android/app/src/main/java/com/esphub/app/MainActivity.kt'
edit(app,'    private lateinit var radarDevkit: TextView','''    private lateinit var radarDevkit: TextView
    private lateinit var radarMap: RadarLinkView''')
edit(app,'''        panel.addView(TextView(this).apply {
            text = "Calibre 20 s com o ambiente vazio.''','''        radarMap = RadarLinkView(this)
        panel.addView(radarMap, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(220)))
        panel.addView(TextView(this).apply {
            text = "Mapa de enlaces, NÃO coordenadas de pessoa. Calibre 20 s com o ambiente vazio.''')
edit(app,'        radarDevkit.text = "DevKit: ${view.devkit}"','''        radarDevkit.text = "DevKit: ${view.devkit}"
        radarMap.update(view.status, view.c6, view.devkit)''')
gradle='android/app/build.gradle.kts'
edit(gradle,'versionCode = 2','versionCode = 3')
edit(gradle,'versionName = "2.0.0-presence"','versionName = "2.1.0-presence"')
write('android/app/src/main/java/com/esphub/app/RadarLinkView.kt',r'''package com.esphub.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.esphub.app.protocol.PresenceFusion

/** Two RF paths, not a human position. */
internal class RadarLinkView(context: Context) : View(context) {
    private val pen = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scoreC6 = 0f
    private var scoreDev = 0f
    private var validC6 = false
    private var validDev = false
    private var label = "Aguardando sinais"

    fun update(status: PresenceFusion.Status, c6: String, dev: String) {
        scoreC6 = Regex("índice (\\d+)").find(c6)?.groupValues?.get(1)
            ?.toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f
        scoreDev = Regex("índice (\\d+)").find(dev)?.groupValues?.get(1)
            ?.toFloatOrNull()?.coerceIn(0f, 100f) ?: 0f
        validC6 = !c6.startsWith("Sem dados")
        validDev = !dev.startsWith("Sem dados")
        label = when(status) {
            PresenceFusion.Status.Change -> "Alteração RF detectada"
            PresenceFusion.Status.Quiet -> "Leituras próximas da referência"
            PresenceFusion.Status.Calibrating -> "Calibrando CSI"
            PresenceFusion.Status.Fault -> "Falha de medição"
            else -> "Aguardando dados"
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val sx = w * .50f; val sy = h * .16f
        val cx = w * .18f; val dx = w * .82f; val y = h * .76f
        fun link(tx: Float, score: Float, valid: Boolean) {
            pen.style = Paint.Style.STROKE
            pen.strokeWidth = (4f + score / 100f * 13f) * resources.displayMetrics.density
            pen.color = if (!valid) Color.DKGRAY else Color.rgb(
                (60 + score * 1.8f).toInt().coerceAtMost(240),
                (210 - score * 1.1f).toInt().coerceAtLeast(80), 110)
            canvas.drawLine(sx, sy, tx, y, pen)
            pen.style = Paint.Style.FILL
        }
        link(cx,scoreC6,validC6)
        link(dx,scoreDev,validDev)
        pen.color = Color.rgb(96,165,250)
        val radius = 7f * resources.displayMetrics.density
        for ((x,yy) in listOf(sx to sy,cx to y,dx to y)) canvas.drawCircle(x,yy,radius,pen)
        pen.color=Color.WHITE
        pen.textSize=12f*resources.displayMetrics.scaledDensity
        canvas.drawText("S3",sx-8f,sy-16f,pen)
        canvas.drawText("C6",cx-8f,y+25f,pen)
        canvas.drawText("ESP32",dx-15f,y+25f,pen)
        pen.textSize=11f*resources.displayMetrics.scaledDensity
        canvas.drawText(label,15f,h-11f,pen)
    }
}
''')
print("PresenceRadar V2 patch applied")
