# ESPhub LabTest V1 — Protocol and Safety Contract

## Purpose

LabTest V1 is a bounded Wi-Fi resilience test designed for an isolated,
authorized laboratory. It sends ordinary unicast UDP traffic from an ESPhub
node to a PC collector on the same private LAN. Wireshark can capture the
traffic on the collector.

It deliberately does **not** implement raw-frame injection, deauthentication,
jamming, MAC spoofing, SYN flooding, DHCP starvation or unbounded packet
generation.

## BLE control commands

All commands below are accepted only after the existing encrypted BLE session
has been authenticated.

### 1. Wi-Fi credentials

```
LAB_WIFI|<ssid_base64>|<password_base64>
```

Response:

```
LAB_WIFI_OK
```

or:

```
LAB_ERROR|reason=<reason>
```

The password may be empty for an open laboratory SSID.

### 2. Configure the test

```
LAB_CONFIG|<collector_ipv4>|<port>|<level>|<duration_seconds>
```

Constraints enforced in firmware:

- collector IPv4 must be RFC1918 private;
- collector must be on the same subnet after association;
- gateway address is rejected;
- node local address is rejected;
- subnet network and broadcast addresses are rejected;
- destination port must be 1024..65535;
- level must be 1, 2 or 3;
- duration must be 5..60 seconds.

### 3. Start / stop / inspect

```
LAB_START
LAB_STOP
LAB_STATUS
```

Loss of the authenticated BLE control session stops an active test.

## Notifications

Typical messages:

```
LAB_CONNECTING|ssid_configured=1
LAB_STARTED|node_id=...|state=RUNNING|level=...|target=...|port=...|profile_pps=...|packet_bytes=...|tx_packets=...|tx_bytes=...|actual_pps=...|elapsed_ms=...|rssi=...
LAB_TEL|...
LAB_STOPPED|...|reason=duration_complete
LAB_ERROR|reason=...
```

## Default Wireshark ports

- ESP32 DevKit V1: UDP/41001
- nanoESP32-C6: UDP/41002
- ESP32-S3: UDP/41003

Display filter:

```
udp.port == 41001 || udp.port == 41002 || udp.port == 41003
```

## Board load profiles

These profiles are intentionally bounded and versioned with LabTest V1.

| Board | Level 1 | Level 2 | Level 3 |
|---|---|---|---|
| ESP32 DevKit V1 | 25 pps × 256 B | 75 pps × 512 B | 150 pps × 1024 B |
| nanoESP32-C6 | 25 pps × 256 B | 100 pps × 512 B | 250 pps × 1024 B |
| ESP32-S3 | 25 pps × 256 B | 125 pps × 512 B | 300 pps × 1024 B |

The scheduler never tries to catch up missed packets with a burst. If the MCU
is late, the next interval begins from the current time.

## UDP payload

Each datagram begins with an ASCII header:

```
ESPHUB_LAB_V1|node=<node_id>|level=<1..3>|seq=<n>|tx_ms=<millis>|
```

The rest is deterministic padding to the configured packet size. This makes
Wireshark inspection and sequence-gap analysis straightforward.
