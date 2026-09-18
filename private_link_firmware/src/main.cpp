#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Update.h>
#include <mbedtls/md.h>
#include <esp_system.h>
#include <WiFi.h>
#include <esp_wifi.h>

#define FW_VERSION "1.2.0"
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
static uint32_t gLastResearchSurvey = 0;
static const uint32_t RESEARCH_SURVEY_INTERVAL_MS = 60000;

struct ResearchStats {
    int wifiApCount = 0;
    int wifiOpenCount = 0;
    int wifiSecureCount = 0;
    int wifiBestRssi = -127;
    int wifiPeakChannel = 0;
    int wifiPeakChannelCount = 0;

    int bleSeenCount = 0;
    int bleBestRssi = -127;

    uint32_t lastSurveyMs = 0;
};

static ResearchStats gResearch;
static uint16_t gWifiChannelCounts[14] = {0};

static const uint16_t FAST_OTA_PORT = 3232;
static const uint32_t FAST_OTA_TIMEOUT_MS = 120000;
static WiFiServer gFastOtaServer(FAST_OTA_PORT);
static bool gFastOtaActive = false;
static uint32_t gFastOtaStartedAt = 0;
static int gFastOtaChannel = 6;
static String gFastOtaSsid;
static String gFastOtaPassword;
static uint8_t gFastOtaToken[32] = {0};

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

static void fillRandomBytes(uint8_t* data, size_t len) {
    if (data == nullptr || len == 0) return;

    for (size_t i = 0; i < len; i += 4) {
        uint32_t randomValue = esp_random();
        size_t amount = min(
            static_cast<size_t>(4),
            len - i
        );

        memcpy(data + i, &randomValue, amount);
    }
}

static void makeChallenge() {
    fillRandomBytes(gChallenge, sizeof(gChallenge));
}

static String randomAlphaNumeric(size_t len) {
    static const char ALPHABET[] =
        "ABCDEFGHJKLMNPQRSTUVWXYZ"
        "abcdefghijkmnopqrstuvwxyz"
        "23456789";

    String out;
    out.reserve(len);

    for (size_t i = 0; i < len; ++i) {
        uint32_t r = esp_random();
        out += ALPHABET[
            r % (sizeof(ALPHABET) - 1)
        ];
    }

    return out;
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

static void runWifiSurvey() {
    if (gOtaActive || gFastOtaActive) return;

    wifi_scan_config_t config = {};
    config.ssid = nullptr;
    config.bssid = nullptr;
    config.channel = 0;
    config.show_hidden = true;
    config.scan_type = WIFI_SCAN_TYPE_PASSIVE;
    config.scan_time.passive = 80;

    esp_err_t err = esp_wifi_scan_start(&config, true);
    if (err != ESP_OK) {
        Serial.printf("WiFi passive survey failed: %d\n", static_cast<int>(err));
        return;
    }

    uint16_t count = 0;
    esp_wifi_scan_get_ap_num(&count);

    gResearch.wifiApCount = static_cast<int>(count);
    gResearch.wifiOpenCount = 0;
    gResearch.wifiSecureCount = 0;
    gResearch.wifiBestRssi = -127;
    gResearch.wifiPeakChannel = 0;
    gResearch.wifiPeakChannelCount = 0;

    int channelCounts[15] = {0};
    memset(gWifiChannelCounts, 0, sizeof(gWifiChannelCounts));

    if (count > 0) {
        wifi_ap_record_t* records =
            static_cast<wifi_ap_record_t*>(calloc(count, sizeof(wifi_ap_record_t)));

        if (records != nullptr) {
            uint16_t fetched = count;

            if (esp_wifi_scan_get_ap_records(&fetched, records) == ESP_OK) {
                gResearch.wifiApCount = static_cast<int>(fetched);

                for (uint16_t i = 0; i < fetched; ++i) {
                    const wifi_ap_record_t& ap = records[i];

                    if (ap.rssi > gResearch.wifiBestRssi) {
                        gResearch.wifiBestRssi = ap.rssi;
                    }

                    if (ap.authmode == WIFI_AUTH_OPEN) {
                        ++gResearch.wifiOpenCount;
                    } else {
                        ++gResearch.wifiSecureCount;
                    }

                    if (ap.primary >= 1 && ap.primary <= 14) {
                        ++channelCounts[ap.primary];
                        ++gWifiChannelCounts[ap.primary - 1];
                    }
                }

                for (int channel = 1; channel <= 14; ++channel) {
                    if (channelCounts[channel] > gResearch.wifiPeakChannelCount) {
                        gResearch.wifiPeakChannelCount = channelCounts[channel];
                        gResearch.wifiPeakChannel = channel;
                    }
                }
            }

            free(records);
        }
    }

    esp_wifi_clear_ap_list();
}

static void runBleSurvey() {
    if (gOtaActive || gFastOtaActive) return;

    NimBLEScan* scan = NimBLEDevice::getScan();

    scan->stop();
    scan->clearResults();
    scan->setActiveScan(false);
    scan->setInterval(120);
    scan->setWindow(60);
    scan->setMaxResults(80);

    NimBLEScanResults results = scan->getResults(2500, false);

    gResearch.bleSeenCount = results.getCount();
    gResearch.bleBestRssi = -127;

    for (int i = 0; i < results.getCount(); ++i) {
        const NimBLEAdvertisedDevice* device = results.getDevice(i);

        if (device != nullptr && device->getRSSI() > gResearch.bleBestRssi) {
            gResearch.bleBestRssi = device->getRSSI();
        }
    }

    scan->clearResults();

    if (gAdvertising != nullptr && !gConnected) {
        gAdvertising->start();
    }
}

static void runResearchSurvey() {
    if (gOtaActive || gFastOtaActive) return;

    runWifiSurvey();
    delay(40);
    runBleSurvey();

    gResearch.lastSurveyMs = millis();
    gLastResearchSurvey = millis();

    Serial.printf(
        "Research survey: wifi=%d open=%d secure=%d best=%d peak_ch=%d ble=%d ble_best=%d\n",
        gResearch.wifiApCount,
        gResearch.wifiOpenCount,
        gResearch.wifiSecureCount,
        gResearch.wifiBestRssi,
        gResearch.wifiPeakChannel,
        gResearch.bleSeenCount,
        gResearch.bleBestRssi
    );
}

static String telemetryText() {
    String text = "TEL|fw=" FW_VERSION;
    text += "|uptime_ms=" + String(millis());
    text += "|heap=" + String(ESP.getFreeHeap());
    text += "|battery_v=na";
    text += "|ota=";
    text += gOtaActive ? "1" : "0";
    text += "|fast_ota=";
    text += gFastOtaActive ? "1" : "0";

    text += "|wifi_ap=" + String(gResearch.wifiApCount);
    text += "|wifi_open=" + String(gResearch.wifiOpenCount);
    text += "|wifi_secure=" + String(gResearch.wifiSecureCount);
    text += "|wifi_best=" + String(gResearch.wifiBestRssi);
    text += "|wifi_peak_ch=" + String(gResearch.wifiPeakChannel);
    text += "|wifi_peak_n=" + String(gResearch.wifiPeakChannelCount);
    text += "|ble_seen=" + String(gResearch.bleSeenCount);
    text += "|ble_best=" + String(gResearch.bleBestRssi);
    text += "|survey_age_ms=" +
        String(gResearch.lastSurveyMs == 0 ? 0 : millis() - gResearch.lastSurveyMs);

    return text;
}

static int chooseFastOtaChannel() {
    static const int candidates[] = {1, 6, 11};

    int bestChannel = 6;
    uint16_t bestCount = UINT16_MAX;

    for (int channel : candidates) {
        uint16_t count = gWifiChannelCounts[channel - 1];

        if (count < bestCount) {
            bestCount = count;
            bestChannel = channel;
        }
    }

    return bestChannel;
}

static void stopFastOtaSession() {
    if (!gFastOtaActive) return;

    gFastOtaServer.stop();
    WiFi.softAPdisconnect(true);
    delay(40);

    WiFi.mode(WIFI_STA);
    WiFi.disconnect(false, false);

    gFastOtaActive = false;
    gFastOtaStartedAt = 0;
    gFastOtaSsid = "";
    gFastOtaPassword = "";
    memset(gFastOtaToken, 0, sizeof(gFastOtaToken));

    gLastResearchSurvey = millis();
}

static void failFastOtaClient(
    WiFiClient& client,
    const String& reason
) {
    Update.abort();
    freeDigests();

    client.print("ERROR|");
    client.print(reason);
    client.print("\n");
    client.flush();

    delay(50);
    client.stop();

    notifyText("FAST_OTA_ERROR|" + reason);
    stopFastOtaSession();
}

static void startFastOtaSession() {
    if (!gAuthenticated) {
        notifyText("ERR|not_authenticated");
        return;
    }

    if (gOtaActive || gFastOtaActive) {
        notifyText("FAST_OTA_ERROR|busy");
        return;
    }

    gFastOtaChannel = chooseFastOtaChannel();

    uint8_t ssidEntropy[4];
    fillRandomBytes(ssidEntropy, sizeof(ssidEntropy));

    gFastOtaSsid =
        "PL-" +
        bytesToHex(ssidEntropy, sizeof(ssidEntropy));

    gFastOtaPassword = randomAlphaNumeric(20);

    fillRandomBytes(
        gFastOtaToken,
        sizeof(gFastOtaToken)
    );

    WiFi.mode(WIFI_AP_STA);

    IPAddress localIp(192, 168, 4, 1);
    IPAddress gateway(192, 168, 4, 1);
    IPAddress subnet(255, 255, 255, 0);

    if (!WiFi.softAPConfig(localIp, gateway, subnet)) {
        notifyText("FAST_OTA_ERROR|ap_config");
        WiFi.mode(WIFI_STA);
        return;
    }

    const bool hiddenSsid = true;

    if (!WiFi.softAP(
            gFastOtaSsid.c_str(),
            gFastOtaPassword.c_str(),
            gFastOtaChannel,
            hiddenSsid,
            1
        )) {
        notifyText("FAST_OTA_ERROR|ap_start");
        WiFi.mode(WIFI_STA);
        return;
    }

    gFastOtaServer.begin();
    gFastOtaServer.setNoDelay(true);

    gFastOtaActive = true;
    gFastOtaStartedAt = millis();

    String response = "FAST_OTA_READY|";
    response += gFastOtaSsid;
    response += "|";
    response += gFastOtaPassword;
    response += "|";
    response += bytesToHex(
        gFastOtaToken,
        sizeof(gFastOtaToken)
    );
    response += "|192.168.4.1|";
    response += String(FAST_OTA_PORT);
    response += "|";
    response += String(gFastOtaChannel);
    response += "|1";

    notifyText(response);

    Serial.printf(
        "Fast OTA ready: channel=%d hidden=1\n",
        gFastOtaChannel
    );
}

static bool parseFastOtaHeader(
    const String& header,
    size_t& imageSize,
    uint8_t expectedSha[32],
    uint8_t expectedHmac[32]
) {
    if (fieldAt(header, '|', 0) != "PL_OTA_V1") {
        return false;
    }

    String sizeText = fieldAt(header, '|', 1);
    String shaText = fieldAt(header, '|', 2);
    String hmacText = fieldAt(header, '|', 3);
    String tokenText = fieldAt(header, '|', 4);

    imageSize =
        static_cast<size_t>(
            strtoull(sizeText.c_str(), nullptr, 10)
        );

    if (imageSize == 0 || imageSize > 0x600000) {
        return false;
    }

    if (!hexToBytes(
            shaText,
            expectedSha,
            32
        )) {
        return false;
    }

    if (!hexToBytes(
            hmacText,
            expectedHmac,
            32
        )) {
        return false;
    }

    uint8_t suppliedToken[32];

    if (!hexToBytes(
            tokenText,
            suppliedToken,
            sizeof(suppliedToken)
        )) {
        return false;
    }

    return constantTimeEqual(
        suppliedToken,
        gFastOtaToken,
        sizeof(gFastOtaToken)
    );
}

static void serviceFastOta() {
    if (!gFastOtaActive) return;

    if (
        millis() - gFastOtaStartedAt >
        FAST_OTA_TIMEOUT_MS
    ) {
        notifyText("FAST_OTA_ERROR|timeout");
        stopFastOtaSession();
        return;
    }

    WiFiClient client = gFastOtaServer.available();

    if (!client) return;

    client.setNoDelay(true);
    client.setTimeout(10);

    String header = client.readStringUntil('\n');
    header.trim();

    size_t imageSize = 0;
    uint8_t expectedSha[32];
    uint8_t expectedHmac[32];

    if (!parseFastOtaHeader(
            header,
            imageSize,
            expectedSha,
            expectedHmac
        )) {
        client.print("ERROR|auth_or_header\n");
        client.flush();
        delay(30);
        client.stop();
        return;
    }

    if (!startDigests()) {
        client.print("ERROR|digest_init\n");
        client.flush();
        client.stop();
        stopFastOtaSession();
        return;
    }

    if (!Update.begin(imageSize, U_FLASH)) {
        freeDigests();
        client.print("ERROR|update_begin\n");
        client.flush();
        client.stop();
        stopFastOtaSession();
        return;
    }

    client.print("READY\n");
    client.flush();

    uint8_t* buffer =
        static_cast<uint8_t*>(malloc(8192));

    if (buffer == nullptr) {
        failFastOtaClient(client, "no_memory");
        return;
    }

    size_t received = 0;
    uint32_t lastDataAt = millis();

    while (
        received < imageSize &&
        client.connected()
    ) {
        int available = client.available();

        if (available <= 0) {
            if (millis() - lastDataAt > 15000) {
                free(buffer);
                failFastOtaClient(
                    client,
                    "data_timeout"
                );
                return;
            }

            delay(1);
            continue;
        }

        size_t wanted = min(
            static_cast<size_t>(available),
            min(
                static_cast<size_t>(8192),
                imageSize - received
            )
        );

        int got = client.read(buffer, wanted);

        if (got <= 0) {
            delay(1);
            continue;
        }

        lastDataAt = millis();

        size_t written =
            Update.write(buffer, got);

        if (written != static_cast<size_t>(got)) {
            free(buffer);
            failFastOtaClient(
                client,
                "write_failed"
            );
            return;
        }

        mbedtls_md_update(
            &gShaCtx,
            buffer,
            got
        );

        mbedtls_md_hmac_update(
            &gHmacCtx,
            buffer,
            got
        );

        received += got;
    }

    free(buffer);

    if (received != imageSize) {
        failFastOtaClient(
            client,
            "size_mismatch"
        );
        return;
    }

    uint8_t actualSha[32];
    uint8_t actualHmac[32];

    if (mbedtls_md_finish(
            &gShaCtx,
            actualSha
        ) != 0) {
        failFastOtaClient(client, "sha_finish");
        return;
    }

    if (mbedtls_md_hmac_finish(
            &gHmacCtx,
            actualHmac
        ) != 0) {
        failFastOtaClient(client, "hmac_finish");
        return;
    }

    freeDigests();

    if (!constantTimeEqual(
            actualSha,
            expectedSha,
            32
        )) {
        failFastOtaClient(client, "sha_mismatch");
        return;
    }

    if (!constantTimeEqual(
            actualHmac,
            expectedHmac,
            32
        )) {
        failFastOtaClient(client, "hmac_mismatch");
        return;
    }

    if (!Update.end(true)) {
        failFastOtaClient(client, "update_end");
        return;
    }

    client.print("OK|" FW_VERSION "\n");
    client.flush();

    notifyText(
        "FAST_OTA_OK|" +
        String(received)
    );

    delay(350);
    client.stop();

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
        notifyText(telemetryText());
        return;
    }

    if (message == "REBOOT") {
        notifyText("OK|reboot");
        delay(300);
        ESP.restart();
        return;
    }

    if (message == "FAST_OTA_BEGIN") {
        startFastOtaSession();
        return;
    }

    if (message == "FAST_OTA_CANCEL") {
        if (gFastOtaActive) {
            stopFastOtaSession();
        }
        notifyText("FAST_OTA_STOPPED");
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
            NIMBLE_PROPERTY::WRITE |
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
            NIMBLE_PROPERTY::WRITE |
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

    WiFi.mode(WIFI_STA);
    WiFi.disconnect(false, true);
    delay(120);

    Serial.println();
    Serial.println("LENDAS S3 PrivateLink Research");
    Serial.println("Firmware: " FW_VERSION);
    Serial.println("Target: ESP32-S3 N16R8");
    Serial.println("Advertising iniciado.");
    Serial.println("Research mode: passive Wi-Fi + passive BLE survey.");
    Serial.println("Fast OTA: secure temporary Wi-Fi transport enabled.");
}

void loop() {
    serviceFastOta();

    if (
        !gOtaActive &&
        !gFastOtaActive &&
        (
            gResearch.lastSurveyMs == 0 ||
            millis() - gLastResearchSurvey >= RESEARCH_SURVEY_INTERVAL_MS
        )
    ) {
        runResearchSurvey();
    }

    if (
        gConnected &&
        gAuthenticated &&
        !gOtaActive &&
        millis() - gLastTelemetry >= 5000
    ) {
        gLastTelemetry = millis();
        notifyText(telemetryText());
    }

    delay(20);
}
