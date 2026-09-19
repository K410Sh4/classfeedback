#pragma once

#include <Arduino.h>

/**
 * ESPhub Wireless Tools V1
 *
 * Passive Wi-Fi discovery and 802.11 monitor telemetry for an isolated lab.
 * This module does not transmit raw 802.11 management/control frames.
 *
 * Features:
 *  - asynchronous Wi-Fi AP scan;
 *  - fixed-channel or passive channel-hopping monitor;
 *  - management/control/data counters;
 *  - beacon/probe/auth/assoc/deauth/disassoc observation counters;
 *  - passive EAPOL 4-way handshake phase counters (M1..M4 metadata only);
 *  - RSSI and frame-rate telemetry.
 */
namespace WirelessTools {

bool requestScan(String& error);

bool startMonitor(
    uint8_t channel,
    uint32_t durationSeconds,
    const String& bssid,
    String& error
);

void stopMonitor(const char* reason);

void service();

bool isBusy();

bool isMonitoring();

String statusText();

bool pollNotification(String& out);

}  // namespace WirelessTools
