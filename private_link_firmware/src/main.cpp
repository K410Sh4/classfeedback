#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Update.h>
#include <mbedtls/md.h>
#include <esp_system.h>

#define FW_VERSION "1.0.0"
#define BLE_PASSKEY 496110

static const char* SERVICE_UUID =
    "621dd260-3266-4eba-afd4-3c2141905dc1";
static const char* CONTROL_UUID =
    "ffe335b5-7234-4023-ba02-2248ed84cd1b";
static const char* RESPONSE_UUID =
    "65adba79-fcc7-41ef-a2e5-8faee3247b40";
static const char* OTA_UUID =
    "119cde0a-c330-4ee8-89f2-98daa95897ad";

/*
 * BUILD PLACEHOLDER ONLY.
 * After CI compilation ChatGPT patches these exact 32 bytes in the .bin
 * with the user's private HMAC key. The real key is never committed.
 */
__attribute__((used))
static const uint8_t APP_AUTH_KEY[32] = {
    0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x11, 0x22, 0x33,
    0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB,
    0xCC, 0xDD, 0xEE, 0xFF, 0x10, 0x32, 0x54, 0x76,
    0x98, 0xBA, 0xDC, 0xFE, 0x13, 0x57, 0x9B, 0xDF
};

static NimBLEServer* gServer = nullptr;
static NimBLECharacteristic* gResponse = nullptr;
static NimBLEAdvertising* gAdvertising = nullptr;

static bool gConnected = false;
static bool gAuthenticated = false;
static uint8_t gChallenge[16];
static uint32_t gLastTelemetry = 0;

static bool gOtaActive = false;
static size_t gOtaSize = 0;
static size_t gOtaReceived = 0;
static uint8_t gExpectedSha[32];
static uint8_t gExpectedHmac[32];

static mbedtls_md_context_t gShaCtx;
static mbedtls_md_context_t gHmacCtx;
static bool gDigestReady = false;

static int hexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + c - 'a';
    if (c >= 'A' && c <= 'F') return 10 + c - 'A';
    return -1;
}

static bool hexToBytes(const String& text, uint8_t* out, size_t outLen) {
    if (text.length() != outLen * 2) return false;

    for (size_t i = 0; i < outLen; ++i) {
        int hi = hexNibble(text[i * 2]);
        int lo = hexNibble(text[i * 2 + 1]);

        if (hi < 0 || lo < 0) return false;
        out[i] = static_cast<uint8_t>((hi << 4) | lo);
    }

    return true;
}

static String bytesToHex(const uint8_t* data, size_t len) {
    static const char* HEX_CHARS = "0123456789abcdef";
    String out;
    out.reserve(len * 2);

    for (size_t i = 0; i < len; ++i) {
        out += HEX_CHARS[(data[i] >> 4) & 0x0F];
        out += HEX_CHARS[data[i] & 0x0F];
    }

    return out;
}

static bool constantTimeEqual(
    const uint8_t* a,
    const uint8_t* b,
    size_t len
) {
    uint8_t diff = 0;

    for (size_t i = 0; i < len; ++i) {
        diff |= a[i] ^ b[i];
    }

    return diff == 0;
}

static String fieldAt(const String& input, char delim, int wanted) {
    int begin = 0;
    int current = 0;

    for (int i = 0; i <= static_cast<int>(input.length()); ++i) {
        if (i == static_cast<int>(input.length()) || input[i] == delim) {
            if (current == wanted) {
                return input.substring(begin, i);
            }

            ++current;
            begin = i + 1;
        }
    }

    return "";
}

static void notifyText(const String& text) {
    if (!gConnected || gResponse == nullptr) return;

    gResponse->setValue(
        reinterpret_cast<const uint8_t*>(text.c_str()),
        text.length()
    );

    gResponse->notify();
}

static void makeChallenge() {
    for (size_t i = 0; i < sizeof(gChallenge); i += 4) {
        uint32_t randomValue = esp_random();
        size_t amount = min(
            static_cast<size_t>(4),
            sizeof(gChallenge) - i
        );

        memcpy(gChallenge + i, &randomValue, amount);
    }
}

static void hmacSha256(
    const uint8_t* data,
    size_t len,
    uint8_t output[32]
) {
    const mbedtls_md_info_t* info =
        mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);

    mbedtls_md_hmac(
        info,
        APP_AUTH_KEY,
        sizeof(APP_AUTH_KEY),
        data,
        len,
        output
    );
}

static void freeDigests() {
    if (!gDigestReady) return;

    mbedtls_md_free(&gShaCtx);
    mbedtls_md_free(&gHmacCtx);
    gDigestReady = false;
}

static bool startDigests() {
    freeDigests();

    const mbedtls_md_info_t* info =
        mbedtls_md_info_from_type(MBEDTLS_MD_SHA256);

    if (info == nullptr) return false;

    mbedtls_md_init(&gShaCtx);
    mbedtls_md_init(&gHmacCtx);

    if (mbedtls_md_setup(&gShaCtx, info, 0) != 0) {
        freeDigests();
        return false;
    }

    if (mbedtls_md_setup(&gHmacCtx, info, 1) != 0) {
        freeDigests();
        return false;
    }

    if (mbedtls_md_starts(&gShaCtx) != 0) {
        freeDigests();
        return false;
    }

    if (mbedtls_md_hmac_starts(
            &gHmacCtx,
            APP_AUTH_KEY,
            sizeof(APP_AUTH_KEY)
        ) != 0) {
        freeDigests();
        return false;
    }

    gDigestReady = true;
    return true;
}

static void abortOta(const String& reason) {
    if (gOtaActive) {
        Update.abort();
    }

    freeDigests();

    gOtaActive = false;
    gOtaSize = 0;
    gOtaReceived = 0;

    notifyText("OTA_ERROR|" + reason);
}

static void beginOta(const String& message) {
    if (!gAuthenticated) {
        notifyText("ERR|not_authenticated");
        return;
    }

    if (gOtaActive) {
        abortOta("already_active");
        return;
    }

    String sizeText = fieldAt(message, '|', 1);
    String shaText = fieldAt(message, '|', 2);
    String hmacText = fieldAt(message, '|', 3);

    size_t imageSize =
        static_cast<size_t>(strtoull(sizeText.c_str(), nullptr, 10));

    if (imageSize == 0 || imageSize > 0x600000) {
        notifyText("OTA_ERROR|invalid_size");
        return;
    }

    if (!hexToBytes(shaText, gExpectedSha, sizeof(gExpectedSha))) {
        notifyText("OTA_ERROR|invalid_sha");
        return;
    }

    if (!hexToBytes(hmacText, gExpectedHmac, sizeof(gExpectedHmac))) {
        notifyText("OTA_ERROR|invalid_hmac");
        return;
    }

    if (!startDigests()) {
        notifyText("OTA_ERROR|digest_init");
        return;
    }

    if (!Update.begin(imageSize, U_FLASH)) {
        freeDigests();
        notifyText("OTA_ERROR|update_begin");
        return;
    }

    gOtaActive = true;
    gOtaSize = imageSize;
    gOtaReceived = 0;

    notifyText("OTA_READY|" + String(imageSize));
}

static void receiveOtaData(const uint8_t* data, size_t len) {
    if (!gAuthenticated || !gOtaActive || data == nullptr || len == 0) {
        return;
    }

    if (gOtaReceived + len > gOtaSize) {
        abortOta("overflow");
        return;
    }

    size_t written = Update.write(const_cast<uint8_t*>(data), len);

    if (written != len) {
        abortOta("write_failed");
        return;
    }

    mbedtls_md_update(&gShaCtx, data, len);
    mbedtls_md_hmac_update(&gHmacCtx, data, len);

    gOtaReceived += len;

    if (
        (gOtaReceived % 32768) < len ||
        gOtaReceived == gOtaSize
    ) {
        notifyText(
            "OTA_PROGRESS|" +
            String(gOtaReceived) +
            "|" +
            String(gOtaSize)
        );
    }
}

static void finishOta() {
    if (!gAuthenticated || !gOtaActive) {
        notifyText("OTA_ERROR|not_active");
        return;
    }

    if (gOtaReceived != gOtaSize) {
        abortOta("size_mismatch");
        return;
    }

    uint8_t actualSha[32];
    uint8_t actualHmac[32];

    if (mbedtls_md_finish(&gShaCtx, actualSha) != 0) {
        abortOta("sha_finish");
        return;
    }

    if (mbedtls_md_hmac_finish(&gHmacCtx, actualHmac) != 0) {
        abortOta("hmac_finish");
        return;
    }

    freeDigests();

    if (!constantTimeEqual(actualSha, gExpectedSha, 32)) {
        abortOta("sha_mismatch");
        return;
    }

    if (!constantTimeEqual(actualHmac, gExpectedHmac, 32)) {
        abortOta("hmac_mismatch");
        return;
    }

    if (!Update.end(true)) {
        abortOta("update_end");
        return;
    }

    gOtaActive = false;

    notifyText("OTA_OK|firmware.bin|rebooting");

    delay(700);
    ESP.restart();
}

static void processControl(const String& message) {
    if (message == "HELLO") {
        makeChallenge();
        gAuthenticated = false;

        notifyText(
            "HELLO|" +
            bytesToHex(gChallenge, sizeof(gChallenge))
        );

        return;
    }

    if (message.startsWith("AUTH|")) {
        String suppliedText = fieldAt(message, '|', 1);
        uint8_t supplied[32];

        if (!hexToBytes(suppliedText, supplied, sizeof(supplied))) {
            notifyText("AUTH_FAIL|format");
            return;
        }

        uint8_t expected[32];

        hmacSha256(
            gChallenge,
            sizeof(gChallenge),
            expected
        );

        if (!constantTimeEqual(supplied, expected, sizeof(expected))) {
            notifyText("AUTH_FAIL|hmac");
            return;
        }

        gAuthenticated = true;
        notifyText("AUTH_OK|" FW_VERSION);
        return;
    }

    if (!gAuthenticated) {
        notifyText("ERR|not_authenticated");
        return;
    }

    if (message == "PING") {
        notifyText("PONG|" + String(millis()));
        return;
    }

    if (message == "STATUS") {
        String text = "TEL|fw=" FW_VERSION;
        text += "|uptime_ms=" + String(millis());
        text += "|heap=" + String(ESP.getFreeHeap());
        text += "|battery_v=na";
        text += "|ota=";
        text += gOtaActive ? "1" : "0";

        notifyText(text);
        return;
    }

    if (message == "REBOOT") {
        notifyText("OK|reboot");
        delay(300);
        ESP.restart();
        return;
    }

    if (message.startsWith("OTA_BEGIN|")) {
        beginOta(message);
        return;
    }

    if (message == "OTA_END") {
        finishOta();
        return;
    }

    if (message == "OTA_ABORT") {
        abortOta("cancelled_by_app");
        return;
    }

    notifyText("ERR|unknown_command");
}

class ControlCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(
        NimBLECharacteristic* characteristic,
        NimBLEConnInfo& connInfo
    ) override {
        std::string raw = characteristic->getValue();
        String text;

        text.reserve(raw.size());

        for (char c : raw) {
            text += c;
        }

        processControl(text);
    }
};

class OtaCallbacks : public NimBLECharacteristicCallbacks {
    void onWrite(
        NimBLECharacteristic* characteristic,
        NimBLEConnInfo& connInfo
    ) override {
        std::string raw = characteristic->getValue();

        receiveOtaData(
            reinterpret_cast<const uint8_t*>(raw.data()),
            raw.size()
        );
    }
};

class ServerCallbacks : public NimBLEServerCallbacks {
    uint32_t onPassKeyDisplay() override {
        return BLE_PASSKEY;
    }

    void onConnect(
        NimBLEServer* server,
        NimBLEConnInfo& connInfo
    ) override {
        gConnected = true;
        gAuthenticated = false;

        server->updateConnParams(
            connInfo.getConnHandle(),
            24,
            48,
            0,
            400
        );
    }

    void onAuthenticationComplete(
        NimBLEConnInfo& connInfo
    ) override {
        if (!connInfo.isEncrypted() || !connInfo.isAuthenticated()) {
            if (gServer != nullptr) {
                gServer->disconnect(connInfo);
            }
        }
    }

    void onDisconnect(
        NimBLEServer* server,
        NimBLEConnInfo& connInfo,
        int reason
    ) override {
        gConnected = false;
        gAuthenticated = false;

        if (gOtaActive) {
            Update.abort();
            freeDigests();
            gOtaActive = false;
            gOtaSize = 0;
            gOtaReceived = 0;
        }

        delay(40);

        if (gAdvertising != nullptr) {
            gAdvertising->start();
        }
    }
};

void setup() {
    Serial.begin(115200);
    delay(250);

    NimBLEDevice::init("");

    NimBLEDevice::setSecurityAuth(
        true,
        true,
        true
    );

    NimBLEDevice::setSecurityIOCap(
        BLE_HS_IO_DISPLAY_ONLY
    );

    NimBLEDevice::setSecurityPasskey(
        BLE_PASSKEY
    );

    NimBLEDevice::setMTU(247);

    gServer = NimBLEDevice::createServer();
    gServer->setCallbacks(new ServerCallbacks());
    gServer->advertiseOnDisconnect(false);

    NimBLEService* service =
        gServer->createService(SERVICE_UUID);

    NimBLECharacteristic* control =
        service->createCharacteristic(
            CONTROL_UUID,
            NIMBLE_PROPERTY::WRITE_AUTHEN,
            512
        );

    control->setCallbacks(new ControlCallbacks());

    gResponse =
        service->createCharacteristic(
            RESPONSE_UUID,
            NIMBLE_PROPERTY::READ_AUTHEN |
            NIMBLE_PROPERTY::NOTIFY,
            512
        );

    NimBLECharacteristic* ota =
        service->createCharacteristic(
            OTA_UUID,
            NIMBLE_PROPERTY::WRITE_AUTHEN,
            512
        );

    ota->setCallbacks(new OtaCallbacks());

    gServer->start();

    gAdvertising = NimBLEDevice::getAdvertising();
    gAdvertising->addServiceUUID(SERVICE_UUID);
    gAdvertising->setMinInterval(1280);
    gAdvertising->setMaxInterval(1920);
    gAdvertising->start();

    Serial.println();
    Serial.println("LENDAS S3 PrivateLink");
    Serial.println("Firmware: " FW_VERSION);
    Serial.println("Target: ESP32-S3 N16R8");
    Serial.println("Advertising iniciado.");
}

void loop() {
    if (
        gConnected &&
        gAuthenticated &&
        !gOtaActive &&
        millis() - gLastTelemetry >= 5000
    ) {
        gLastTelemetry = millis();

        String text = "TEL|fw=" FW_VERSION;
        text += "|uptime_ms=" + String(millis());
        text += "|heap=" + String(ESP.getFreeHeap());
        text += "|battery_v=na";
        text += "|ota=0";

        notifyText(text);
    }

    delay(20);
}
