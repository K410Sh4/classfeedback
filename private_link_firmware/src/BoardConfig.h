#pragma once

#include <Arduino.h>

#if CONFIG_IDF_TARGET_ESP32S3

#define PL_FW_VERSION "1.4.0"
#define PL_MODEL "ESP32-S3-N16R8"
#define PL_BOARD "PrivateLink-S3"
#define PL_ROLE "PRIMARY_NODE"
#define PL_CAPS "BLE,WIFI,RESEARCH,FAST_OTA"
#define PL_MAX_APP_SIZE 0x600000

#elif CONFIG_IDF_TARGET_ESP32C6

#define PL_FW_VERSION "1.0.0-c6"
#define PL_MODEL "nanoESP32-C6-V1711"
#define PL_BOARD "nanoESP32-C6-V1.0"
#define PL_ROLE "RADIO_NODE"
#define PL_CAPS "BLE,WIFI,RESEARCH,FAST_OTA,IEEE802154_READY"
#define PL_MAX_APP_SIZE 0x180000

#else
#error "Unsupported target for PrivateLink Multi-Node firmware"
#endif
