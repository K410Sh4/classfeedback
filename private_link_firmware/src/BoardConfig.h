#pragma once

#include <Arduino.h>

/*
 * ESPhub LabTest V1 board profiles.
 *
 * These are intentionally bounded load profiles. They are designed to create
 * repeatable, measurable Wi-Fi traffic for an isolated lab without raw frame
 * injection, spoofing, deauthentication, jamming or unbounded flooding.
 */

#define PL_LAB_MAX_PACKET_BYTES 1024

#if CONFIG_IDF_TARGET_ESP32S3

#define PL_FW_VERSION "1.5.0"
#define PL_MODEL "ESP32-S3-N16R8"
#define PL_BOARD "ESPhub-S3"
#define PL_ROLE "PRIMARY_NODE"
#define PL_CAPS "BLE,WIFI,RESEARCH,FAST_OTA,LABTEST_V1"
#define PL_MAX_APP_SIZE 0x600000
#define PL_LAB_DEFAULT_PORT 41003

#define PL_LAB_LEVEL1_PPS 25
#define PL_LAB_LEVEL1_BYTES 256
#define PL_LAB_LEVEL2_PPS 125
#define PL_LAB_LEVEL2_BYTES 512
#define PL_LAB_LEVEL3_PPS 300
#define PL_LAB_LEVEL3_BYTES 1024

#elif CONFIG_IDF_TARGET_ESP32C6

#define PL_FW_VERSION "1.1.0-c6"
#define PL_MODEL "nanoESP32-C6-V1711"
#define PL_BOARD "nanoESP32-C6-V1.0"
#define PL_ROLE "RADIO_NODE"
#define PL_CAPS "BLE,WIFI,RESEARCH,FAST_OTA,LABTEST_V1,IEEE802154_READY"
#define PL_MAX_APP_SIZE 0x1B0000
#define PL_LAB_DEFAULT_PORT 41002

#define PL_LAB_LEVEL1_PPS 25
#define PL_LAB_LEVEL1_BYTES 256
#define PL_LAB_LEVEL2_PPS 100
#define PL_LAB_LEVEL2_BYTES 512
#define PL_LAB_LEVEL3_PPS 250
#define PL_LAB_LEVEL3_BYTES 1024

#elif CONFIG_IDF_TARGET_ESP32

#define PL_FW_VERSION "1.0.0-esp32"
#define PL_MODEL "ESP32-DevKit-V1"
#define PL_BOARD "ESPhub-ESP32"
#define PL_ROLE "LEGACY_LAB_NODE"
#define PL_CAPS "BLE,WIFI,RESEARCH,FAST_OTA,LABTEST_V1"
#define PL_MAX_APP_SIZE 0x180000
#define PL_LAB_DEFAULT_PORT 41001

#define PL_LAB_LEVEL1_PPS 25
#define PL_LAB_LEVEL1_BYTES 256
#define PL_LAB_LEVEL2_PPS 75
#define PL_LAB_LEVEL2_BYTES 512
#define PL_LAB_LEVEL3_PPS 150
#define PL_LAB_LEVEL3_BYTES 1024

#else
#error "Unsupported target for ESPhub Multi-Node firmware"
#endif
