# LENDAS S3 PrivateLink Android

Native Android client for the LENDAS ESP32-S3 N16R8 PrivateLink firmware.

The private HMAC key is intentionally NOT committed to this public repository.
On first launch, paste the 64-character key from PROJECT_PRIVATE_KEYS.txt.
The app encrypts it locally with Android Keystore.

Features:
- BLE scan filtered by the project service UUID
- RSSI proximity filter
- system BLE bonding + passkey
- HMAC-SHA256 application authentication
- telemetry and commands
- authenticated OTA .bin upload
- SHA-256 + HMAC firmware validation
