#pragma once

#include <Arduino.h>

/**
 * ESPhub LabTest V1
 *
 * Bounded, non-destructive Wi-Fi load generator for an isolated lab.
 *
 * Safety properties:
 *  - target must be a private IPv4 address;
 *  - target must be on the same subnet after Wi-Fi association;
 *  - gateway, local IP, subnet network and broadcast addresses are refused;
 *  - destination ports below 1024 are refused;
 *  - duration is capped at 60 seconds;
 *  - packet rate and size are fixed by the board profile;
 *  - no catch-up bursts if the scheduler is delayed;
 *  - loss of the BLE control session must call stop().
 */
namespace LabTest {

bool setWifiCredentialsBase64(
    const String& ssidB64,
    const String& passwordB64,
    String& error
);

bool configure(
    const String& targetIpv4,
    uint16_t port,
    uint8_t level,
    uint32_t durationSeconds,
    String& error
);

bool start(
    const String& nodeId,
    String& error
);

void stop(const char* reason);

void service();

bool isActive();

bool isBusy();

String statusText();

bool pollNotification(String& out);

}  // namespace LabTest
