package com.lendas.privatelink.core

import java.util.UUID

object Protocol {
    val SERVICE_UUID: UUID = UUID.fromString("621dd260-3266-4eba-afd4-3c2141905dc1")
    val CONTROL_UUID: UUID = UUID.fromString("ffe335b5-7234-4023-ba02-2248ed84cd1b")
    val RESPONSE_UUID: UUID = UUID.fromString("65adba79-fcc7-41ef-a2e5-8faee3247b40")
    val OTA_UUID: UUID = UUID.fromString("119cde0a-c330-4ee8-89f2-98daa95897ad")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PAIRING_PASSKEY = 496110
    const val MIN_RSSI = -78
    const val OTA_CHUNK = 180
    const val MAX_WRITE_RETRIES = 8
}
