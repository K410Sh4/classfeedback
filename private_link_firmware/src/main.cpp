#include <Arduino.h>
#include <NimBLEDevice.h>
#include <Update.h>
#include <Preferences.h>
#include <mbedtls/md.h>
#include <esp_system.h>
#include <WiFi.h>
#include <esp_wifi.h>
#include "BoardConfig.h"
#include "LabTest.h"

#define BLE_PASSKEY 496110

static const char* SERVICE_UUID =
    "621dd260-3266-4eba-afd4-3c2141905dc1";
static const char* CONTROL_UUID =
    "ffe335b5-7234-4023-ba02-2248ed84cd1b";
static const char* RESPONSE_UUID =
    "65adba79-fcc7-41ef-a2e5-8faee3247b40";
static const char* OTA_UUID =
    "119cde0a-c330-4ee8-89f2-98daa95897ad";

static const char* PREF_NAMESPACE = "privatelink";
static const char* PREF_HMAC_KEY = "hmac";
static const char* PREF_KEY_VERSION = "keyver";

static NimBLEServer* gServer = nullptr;
static NimBLECharacteristic* gResponse = nullptr;
static NimBLEAdvertising* gAdvertising = nullptr;

static bool gConnected = false;
static bool gLinkEncrypted = false;
static bool gAuthenticated = false;
static String gNodeId;

static uint8_t gAppAuthKey[32] = {0};
static bool gProvisioned = false;
static uint8_t gKeyVersion = 0;
static uint8_t gChallenge[16] = {0};
static uint8_t gProvisionNonce[16] = {0};
static uint32_t gProvisionWindowDeadline = 0;

static uint32_t gLastTelemetry = 0;
static uint32_t gNextResearchAt = 0;
static const uint32_t RESEARCH_INTERVAL_MS = 60000;
static const uint32_t RESEARCH_IDLE_DELAY_MS = 12000;
static const uint32_t PROVISION_WINDOW_MS = 120000;

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
static uint8_t gExpectedSha[32] = {0};
static uint8_t gExpectedHmac[32] = {0};

static mbedtls_md_context_t gShaCtx;
static mbedtls_md_context_t gHmacCtx;
static bool gDigestReady = false;

static int hexNibble(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + c - 'a';
    if (c >= 'A' && c <= 'F') return 10 + c - 'A';
    return -1;
}

static bool hexToBytes(
    const String& text,
    uint8_t* out,
    size_t outLen
) {
    if (out == nullptr || text.length() != outLen * 2) {
        return false;
    }

    for (size_t i = 0; i < outLen; ++i) {
        int hi = hexNibble(text[i * 2]);
        int lo = hexNibble(text[i * 2 + 1]);

        if (hi < 0 || lo < 0) return false;

        out[i] =
            static_cast<uint8_t>(
                (hi << 4) | lo
            );
    }

    return true;
}

static String bytesToHex(
    const uint8_t* data,
    size_t len
) {
    static const char* HEX_CHARS =
        "0123456789abcdef";

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

static String fieldAt(
    const String& input,
    char delim,
    int wanted
) {
    int begin = 0;
    int current = 0;

    for (
        int i = 0;
        i <= static_cast<int>(input.length());
        ++i
    ) {
        if (
            i == static_cast<int>(input.length()) ||
            input[i] == delim
        ) {
            if (current == wanted) {
                return input.substring(begin, i);
            }

            ++current;
            begin = i + 1;
        }
    }

    return "";
}

static void fillRandomBytes(
    uint8_t* data,
    size_t len
) {
    if (data == nullptr || len == 0) return;

    for (size_t i = 0; i < len; i += 4) {
        uint32_t randomValue = esp_random();

        size_t amount = min(
            static_cast<size_t>(4),
            len - i
        );

        memcpy(
            data + i,
            &randomValue,
            amount
        );
    }
}

static String randomAlphaNumeric(size_t len) {
    static const char ALPHABET[] =
        "ABCDEFGHJKLMNPQRSTUVWXYZ"
        "abcdefghijkmnopqrstuvwxyz"
        "23456789";

    String out;
    out.reserve(len);

    for (size_t i = 0; i < len; ++i) {
        out += ALPHABET[
            esp_random() %
            (sizeof(ALPHABET) - 1)
        ];
    }

    return out;
}

static String nodeIdFromEfuse() {
    uint64_t mac = ESP.getEfuseMac();
    char buffer[20];

    snprintf(
        buffer,
        sizeof(buffer),
        "%02X%02X%02X%02X%02X%02X",
        static_cast<unsigned>((mac >> 40) & 0xFF),
        static_cast<unsigned>((mac >> 32) & 0xFF),
        static_cast<unsigned>((mac >> 24) & 0xFF),
        static_cast<unsigned>((mac >> 16) & 0xFF),
        static_cast<unsigned>((mac >> 8) & 0xFF),
        static_cast<unsigned>(mac & 0xFF)
    );

    return String(buffer);
}

static String deviceInfoText() {
    String text = "INFO";
    text += "|node_id=" + gNodeId;
    text += "|model=" PL_MODEL;
    text += "|board=" PL_BOARD;
    text += "|role=" PL_ROLE;
    text += "|fw=" PL_FW_VERSION;
    text += "|flash_bytes=" + String(ESP.getFlashChipSize());
    text += "|caps=" PL_CAPS;
    return text;
}

static void notifyText(const String& text) {
    if (
        !gConnected ||
        gResponse == nullptr
    ) {
        return;
    }

    gResponse->setValue(
        reinterpret_cast<const uint8_t*>(
            text.c_str()
        ),
        text.length()
    );

    gResponse->notify();
}

static void hmacSha256WithKey(
    const uint8_t key[32],
    const uint8_t* data,
    size_t len,
    uint8_t output[32]
) {
    const mbedtls_md_info_t* info =
        mbedtls_md_info_from_type(
            MBEDTLS_MD_SHA256
        );

    if (info == nullptr) {
        memset(output, 0, 32);
        return;
    }

    mbedtls_md_hmac(
        info,
        key,
        32,
        data,
        len,
        output
    );
}

static void hmacSha256(
    const uint8_t* data,
    size_t len,
    uint8_t output[32]
) {
    if (!gProvisioned) {
        memset(output, 0, 32);
        return;
    }

    hmacSha256WithKey(
        gAppAuthKey,
        data,
        len,
        output
    );
}

static bool loadProvisionedKey() {
    Preferences prefs;

    if (!prefs.begin(
            PREF_NAMESPACE,
            true
        )) {
        return false;
    }

    size_t len =
        prefs.getBytesLength(
            PREF_HMAC_KEY
        );

    if (len != sizeof(gAppAuthKey)) {
        prefs.end();
        memset(
            gAppAuthKey,
            0,
            sizeof(gAppAuthKey)
        );
        return false;
    }

    size_t read =
        prefs.getBytes(
            PREF_HMAC_KEY,
            gAppAuthKey,
            sizeof(gAppAuthKey)
        );

    gKeyVersion =
        prefs.getUChar(
            PREF_KEY_VERSION,
            0
        );

    prefs.end();

    return read == sizeof(gAppAuthKey);
}

static bool storeProvisionedKey(
    const uint8_t key[32],
    uint8_t keyVersion = 1
) {
    Preferences prefs;

    if (!prefs.begin(
            PREF_NAMESPACE,
            false
        )) {
        return false;
    }

    size_t written =
        prefs.putBytes(
            PREF_HMAC_KEY,
            key,
            32
        );

    bool versionWritten =
        prefs.putUChar(
            PREF_KEY_VERSION,
            keyVersion
        ) == 1;

    prefs.end();

    if (
        written != 32 ||
        !versionWritten
    ) {
        return false;
    }

    memcpy(
        gAppAuthKey,
        key,
        32
    );

    gProvisioned = true;
    gKeyVersion = keyVersion;
    return true;
}

static void freeDigests() {
    if (!gDigestReady) return;

    mbedtls_md_free(&gShaCtx);
    mbedtls_md_free(&gHmacCtx);
    gDigestReady = false;
}

static bool startDigests() {
    if (!gProvisioned) {
        return false;
    }

    freeDigests();

    const mbedtls_md_info_t* info =
        mbedtls_md_info_from_type(
            MBEDTLS_MD_SHA256
        );

    if (info == nullptr) return false;

    mbedtls_md_init(&gShaCtx);
    mbedtls_md_init(&gHmacCtx);

    if (
        mbedtls_md_setup(
            &gShaCtx,
            info,
            0
        ) != 0
    ) {
        freeDigests();
        return false;
    }

    if (
        mbedtls_md_setup(
            &gHmacCtx,
            info,
            1
        ) != 0
    ) {
        freeDigests();
        return false;
    }

    if (
        mbedtls_md_starts(
            &gShaCtx
        ) != 0
    ) {
        freeDigests();
        return false;
    }

    if (
        mbedtls_md_hmac_starts(
            &gHmacCtx,
            gAppAuthKey,
            sizeof(gAppAuthKey)
        ) != 0
    ) {
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

    notifyText(
        "OTA_ERROR|" + reason
    );
}

static void beginOta(
    const String& message
) {
    if (
        !gAuthenticated ||
        !gProvisioned
    ) {
        notifyText(
            "ERR|not_authenticated"
        );
        return;
    }

    if (gOtaActive) {
        abortOta("already_active");
        return;
    }

    String sizeText =
        fieldAt(message, '|', 1);

    String shaText =
        fieldAt(message, '|', 2);

    String hmacText =
        fieldAt(message, '|', 3);

    size_t imageSize =
        static_cast<size_t>(
            strtoull(
                sizeText.c_str(),
                nullptr,
                10
            )
        );

    if (
        imageSize == 0 ||
        imageSize > PL_MAX_APP_SIZE
    ) {
        notifyText(
            "OTA_ERROR|invalid_size"
        );
        return;
    }

    if (
        !hexToBytes(
            shaText,
            gExpectedSha,
            sizeof(gExpectedSha)
        )
    ) {
        notifyText(
            "OTA_ERROR|invalid_sha"
        );
        return;
    }

    if (
        !hexToBytes(
            hmacText,
            gExpectedHmac,
            sizeof(gExpectedHmac)
        )
    ) {
        notifyText(
            "OTA_ERROR|invalid_hmac"
        );
        return;
    }

    if (!startDigests()) {
        notifyText(
            "OTA_ERROR|digest_init"
        );
        return;
    }

    if (
        !Update.begin(
            imageSize,
            U_FLASH
        )
    ) {
        freeDigests();

        notifyText(
            "OTA_ERROR|update_begin"
        );
        return;
    }

    gOtaActive = true;
    gOtaSize = imageSize;
    gOtaReceived = 0;

    notifyText(
        "OTA_READY|" +
        String(imageSize)
    );
}

static void receiveOtaData(
    const uint8_t* data,
    size_t len
) {
    if (
        !gAuthenticated ||
        !gProvisioned ||
        !gOtaActive ||
        data == nullptr ||
        len == 0
    ) {
        return;
    }

    if (
        gOtaReceived + len >
        gOtaSize
    ) {
        abortOta("overflow");
        return;
    }

    size_t written =
        Update.write(
            const_cast<uint8_t*>(data),
            len
        );

    if (written != len) {
        abortOta("write_failed");
        return;
    }

    mbedtls_md_update(
        &gShaCtx,
        data,
        len
    );

    mbedtls_md_hmac_update(
        &gHmacCtx,
        data,
        len
    );

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
    if (
        !gAuthenticated ||
        !gOtaActive
    ) {
        notifyText(
            "OTA_ERROR|not_active"
        );
        return;
    }

    if (
        gOtaReceived !=
        gOtaSize
    ) {
        abortOta("size_mismatch");
        return;
    }

    uint8_t actualSha[32];
    uint8_t actualHmac[32];

    if (
        mbedtls_md_finish(
            &gShaCtx,
            actualSha
        ) != 0
    ) {
        abortOta("sha_finish");
        return;
    }

    if (
        mbedtls_md_hmac_finish(
            &gHmacCtx,
            actualHmac
        ) != 0
    ) {
        abortOta("hmac_finish");
        return;
    }

    freeDigests();

    if (
        !constantTimeEqual(
            actualSha,
            gExpectedSha,
            32
        )
    ) {
        abortOta("sha_mismatch");
        return;
    }

    if (
        !constantTimeEqual(
            actualHmac,
            gExpectedHmac,
            32
        )
    ) {
        abortOta("hmac_mismatch");
        return;
    }

    if (!Update.end(true)) {
        abortOta("update_end");
        return;
    }

    gOtaActive = false;

    notifyText(
        "OTA_OK|firmware.bin|rebooting"
    );

    delay(700);
    ESP.restart();
}

static void wifiOff() {
    WiFi.softAPdisconnect(true);
    WiFi.disconnect(false, false);
    WiFi.mode(WIFI_OFF);
    delay(20);
}

static void runWifiSurvey() {
    if (
        gConnected ||
        gOtaActive ||
        gFastOtaActive
    ) {
        return;
    }

    WiFi.mode(WIFI_STA);
    delay(80);

    wifi_scan_config_t config = {};
    config.ssid = nullptr;
    config.bssid = nullptr;
    config.channel = 0;
    config.show_hidden = true;
    config.scan_type =
        WIFI_SCAN_TYPE_PASSIVE;
    config.scan_time.passive = 60;

    esp_err_t err =
        esp_wifi_scan_start(
            &config,
            true
        );

    if (err != ESP_OK) {
        Serial.printf(
            "WiFi passive survey failed: %d\n",
            static_cast<int>(err)
        );

        wifiOff();
        return;
    }

    uint16_t count = 0;
    esp_wifi_scan_get_ap_num(&count);

    gResearch.wifiApCount =
        static_cast<int>(count);

    gResearch.wifiOpenCount = 0;
    gResearch.wifiSecureCount = 0;
    gResearch.wifiBestRssi = -127;
    gResearch.wifiPeakChannel = 0;
    gResearch.wifiPeakChannelCount = 0;

    int channelCounts[15] = {0};

    memset(
        gWifiChannelCounts,
        0,
        sizeof(gWifiChannelCounts)
    );

    if (count > 0) {
        wifi_ap_record_t* records =
            static_cast<wifi_ap_record_t*>(
                calloc(
                    count,
                    sizeof(wifi_ap_record_t)
                )
            );

        if (records != nullptr) {
            uint16_t fetched = count;

            if (
                esp_wifi_scan_get_ap_records(
                    &fetched,
                    records
                ) == ESP_OK
            ) {
                gResearch.wifiApCount =
                    static_cast<int>(
                        fetched
                    );

                for (
                    uint16_t i = 0;
                    i < fetched;
                    ++i
                ) {
                    const wifi_ap_record_t& ap =
                        records[i];

                    if (
                        ap.rssi >
                        gResearch.wifiBestRssi
                    ) {
                        gResearch.wifiBestRssi =
                            ap.rssi;
                    }

                    if (
                        ap.authmode ==
                        WIFI_AUTH_OPEN
                    ) {
                        ++gResearch.wifiOpenCount;
                    } else {
                        ++gResearch.wifiSecureCount;
                    }

                    if (
                        ap.primary >= 1 &&
                        ap.primary <= 14
                    ) {
                        ++channelCounts[
                            ap.primary
                        ];

                        ++gWifiChannelCounts[
                            ap.primary - 1
                        ];
                    }
                }

                for (
                    int channel = 1;
                    channel <= 14;
                    ++channel
                ) {
                    if (
                        channelCounts[channel] >
                        gResearch.wifiPeakChannelCount
                    ) {
                        gResearch.wifiPeakChannelCount =
                            channelCounts[channel];

                        gResearch.wifiPeakChannel =
                            channel;
                    }
                }
            }

            free(records);
        }
    }

    esp_wifi_clear_ap_list();
    wifiOff();
}

static void runBleSurvey() {
    if (
        gConnected ||
        gOtaActive ||
        gFastOtaActive
    ) {
        return;
    }

    if (gAdvertising != nullptr) {
        gAdvertising->stop();
    }

    NimBLEScan* scan =
        NimBLEDevice::getScan();

    scan->stop();
    scan->clearResults();
    scan->setActiveScan(false);
    scan->setInterval(120);
    scan->setWindow(55);
    scan->setMaxResults(80);

    NimBLEScanResults results =
        scan->getResults(
            1500,
            false
        );

    gResearch.bleSeenCount =
        results.getCount();

    gResearch.bleBestRssi = -127;

    for (
        int i = 0;
        i < results.getCount();
        ++i
    ) {
        const NimBLEAdvertisedDevice* device =
            results.getDevice(i);

        if (
            device != nullptr &&
            device->getRSSI() >
            gResearch.bleBestRssi
        ) {
            gResearch.bleBestRssi =
                device->getRSSI();
        }
    }

    scan->clearResults();

    if (
        gAdvertising != nullptr &&
        !gConnected
    ) {
        gAdvertising->start();
    }
}

static void runResearchSurvey() {
    if (
        gConnected ||
        gOtaActive ||
        gFastOtaActive
    ) {
        return;
    }

    runWifiSurvey();

    if (gConnected) return;

    delay(30);
    runBleSurvey();

    gResearch.lastSurveyMs =
        millis();

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
    String text =
        "TEL|fw=" PL_FW_VERSION;

    text += "|node_id=" + gNodeId;
    text += "|model=" PL_MODEL;
    text += "|role=" PL_ROLE;
    text += "|flash_bytes=" + String(ESP.getFlashChipSize());

    text +=
        "|uptime_ms=" +
        String(millis());

    text +=
        "|heap=" +
        String(ESP.getFreeHeap());

    text += "|battery_v=na";

    text += "|ota=";
    text +=
        gOtaActive ? "1" : "0";

    text += "|fast_ota=";
    text +=
        gFastOtaActive ? "1" : "0";

    text += "|provisioned=";
    text +=
        gProvisioned ? "1" : "0";

    text += "|wifi_ap=" +
        String(gResearch.wifiApCount);

    text += "|wifi_open=" +
        String(gResearch.wifiOpenCount);

    text += "|wifi_secure=" +
        String(gResearch.wifiSecureCount);

    text += "|wifi_best=" +
        String(gResearch.wifiBestRssi);

    text += "|wifi_peak_ch=" +
        String(gResearch.wifiPeakChannel);

    text += "|wifi_peak_n=" +
        String(gResearch.wifiPeakChannelCount);

    text += "|ble_seen=" +
        String(gResearch.bleSeenCount);

    text += "|ble_best=" +
        String(gResearch.bleBestRssi);

    text += "|survey_age_ms=" +
        String(
            gResearch.lastSurveyMs == 0
                ? 0
                : millis() -
                    gResearch.lastSurveyMs
        );

    return text;
}

static int chooseFastOtaChannel() {
    static const int candidates[] = {
        1,
        6,
        11
    };

    uint32_t total = 0;

    for (
        int channel :
        candidates
    ) {
        total +=
            gWifiChannelCounts[
                channel - 1
            ];
    }

    if (total == 0) {
        return 6;
    }

    int bestChannel = 6;
    uint16_t bestCount = UINT16_MAX;

    for (
        int channel :
        candidates
    ) {
        uint16_t count =
            gWifiChannelCounts[
                channel - 1
            ];

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
    wifiOff();

    gFastOtaActive = false;
    gFastOtaStartedAt = 0;
    gFastOtaSsid = "";
    gFastOtaPassword = "";

    memset(
        gFastOtaToken,
        0,
        sizeof(gFastOtaToken)
    );

    gNextResearchAt =
        millis() +
        RESEARCH_IDLE_DELAY_MS;
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

    notifyText(
        "FAST_OTA_ERROR|" +
        reason
    );

    stopFastOtaSession();
}

static void startFastOtaSession() {
    if (
        !gAuthenticated ||
        !gProvisioned
    ) {
        notifyText(
            "ERR|not_authenticated"
        );
        return;
    }

    if (
        gOtaActive ||
        gFastOtaActive
    ) {
        notifyText(
            "FAST_OTA_ERROR|busy"
        );
        return;
    }

    gFastOtaChannel =
        chooseFastOtaChannel();

    uint8_t ssidEntropy[4];
    fillRandomBytes(
        ssidEntropy,
        sizeof(ssidEntropy)
    );

    gFastOtaSsid =
        "PL-" +
        bytesToHex(
            ssidEntropy,
            sizeof(ssidEntropy)
        );

    gFastOtaPassword =
        randomAlphaNumeric(20);

    fillRandomBytes(
        gFastOtaToken,
        sizeof(gFastOtaToken)
    );

    WiFi.mode(WIFI_AP);
    delay(60);

    IPAddress localIp(
        192,
        168,
        4,
        1
    );

    IPAddress gateway(
        192,
        168,
        4,
        1
    );

    IPAddress subnet(
        255,
        255,
        255,
        0
    );

    if (
        !WiFi.softAPConfig(
            localIp,
            gateway,
            subnet
        )
    ) {
        notifyText(
            "FAST_OTA_ERROR|ap_config"
        );

        wifiOff();
        return;
    }

    const bool hiddenSsid = false;

    if (
        !WiFi.softAP(
            gFastOtaSsid.c_str(),
            gFastOtaPassword.c_str(),
            gFastOtaChannel,
            hiddenSsid,
            1
        )
    ) {
        notifyText(
            "FAST_OTA_ERROR|ap_start"
        );

        wifiOff();
        return;
    }

    gFastOtaServer.begin();
    gFastOtaServer.setNoDelay(true);

    gFastOtaActive = true;
    gFastOtaStartedAt = millis();

    String response =
        "FAST_OTA_READY|";

    response += gFastOtaSsid;
    response += "|";
    response += gFastOtaPassword;
    response += "|";

    response +=
        bytesToHex(
            gFastOtaToken,
            sizeof(gFastOtaToken)
        );

    response +=
        "|192.168.4.1|";

    response +=
        String(FAST_OTA_PORT);

    response += "|";
    response +=
        String(gFastOtaChannel);

    response += "|0";

    notifyText(response);

    Serial.printf(
        "Fast OTA ready: channel=%d hidden=0\n",
        gFastOtaChannel
    );
}

static bool parseFastOtaHeader(
    const String& header,
    size_t& imageSize,
    uint8_t expectedSha[32],
    uint8_t expectedHmac[32]
) {
    if (
        fieldAt(
            header,
            '|',
            0
        ) != "PL_OTA_V1"
    ) {
        return false;
    }

    String sizeText =
        fieldAt(header, '|', 1);

    String shaText =
        fieldAt(header, '|', 2);

    String hmacText =
        fieldAt(header, '|', 3);

    String tokenText =
        fieldAt(header, '|', 4);

    imageSize =
        static_cast<size_t>(
            strtoull(
                sizeText.c_str(),
                nullptr,
                10
            )
        );

    if (
        imageSize == 0 ||
        imageSize > PL_MAX_APP_SIZE
    ) {
        return false;
    }

    if (
        !hexToBytes(
            shaText,
            expectedSha,
            32
        )
    ) {
        return false;
    }

    if (
        !hexToBytes(
            hmacText,
            expectedHmac,
            32
        )
    ) {
        return false;
    }

    uint8_t suppliedToken[32];

    if (
        !hexToBytes(
            tokenText,
            suppliedToken,
            sizeof(suppliedToken)
        )
    ) {
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
        millis() -
        gFastOtaStartedAt >
        FAST_OTA_TIMEOUT_MS
    ) {
        notifyText(
            "FAST_OTA_ERROR|timeout"
        );

        stopFastOtaSession();
        return;
    }

    WiFiClient client =
        gFastOtaServer.available();

    if (!client) return;

    client.setNoDelay(true);
    client.setTimeout(10);

    String header =
        client.readStringUntil('\n');

    header.trim();

    size_t imageSize = 0;
    uint8_t expectedSha[32];
    uint8_t expectedHmac[32];

    if (
        !parseFastOtaHeader(
            header,
            imageSize,
            expectedSha,
            expectedHmac
        )
    ) {
        client.print(
            "ERROR|auth_or_header\n"
        );

        client.flush();
        delay(30);
        client.stop();
        return;
    }

    if (!startDigests()) {
        client.print(
            "ERROR|digest_init\n"
        );

        client.flush();
        client.stop();
        stopFastOtaSession();
        return;
    }

    if (
        !Update.begin(
            imageSize,
            U_FLASH
        )
    ) {
        freeDigests();

        client.print(
            "ERROR|update_begin\n"
        );

        client.flush();
        client.stop();
        stopFastOtaSession();
        return;
    }

    client.print("READY\n");
    client.flush();

    uint8_t* buffer =
        static_cast<uint8_t*>(
            malloc(8192)
        );

    if (buffer == nullptr) {
        failFastOtaClient(
            client,
            "no_memory"
        );
        return;
    }

    size_t received = 0;
    uint32_t lastDataAt =
        millis();

    while (
        received < imageSize &&
        client.connected()
    ) {
        int available =
            client.available();

        if (available <= 0) {
            if (
                millis() -
                lastDataAt >
                15000
            ) {
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
            static_cast<size_t>(
                available
            ),
            min(
                static_cast<size_t>(
                    8192
                ),
                imageSize - received
            )
        );

        int got =
            client.read(
                buffer,
                wanted
            );

        if (got <= 0) {
            delay(1);
            continue;
        }

        lastDataAt = millis();

        size_t written =
            Update.write(
                buffer,
                got
            );

        if (
            written !=
            static_cast<size_t>(got)
        ) {
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

    if (
        mbedtls_md_finish(
            &gShaCtx,
            actualSha
        ) != 0
    ) {
        failFastOtaClient(
            client,
            "sha_finish"
        );
        return;
    }

    if (
        mbedtls_md_hmac_finish(
            &gHmacCtx,
            actualHmac
        ) != 0
    ) {
        failFastOtaClient(
            client,
            "hmac_finish"
        );
        return;
    }

    freeDigests();

    if (
        !constantTimeEqual(
            actualSha,
            expectedSha,
            32
        )
    ) {
        failFastOtaClient(
            client,
            "sha_mismatch"
        );
        return;
    }

    if (
        !constantTimeEqual(
            actualHmac,
            expectedHmac,
            32
        )
    ) {
        failFastOtaClient(
            client,
            "hmac_mismatch"
        );
        return;
    }

    if (!Update.end(true)) {
        failFastOtaClient(
            client,
            "update_end"
        );
        return;
    }

    client.print(
        "OK|" PL_FW_VERSION "\n"
    );

    client.flush();

    notifyText(
        "FAST_OTA_OK|" +
        String(received)
    );

    delay(350);
    client.stop();

    ESP.restart();
}

static bool handleProvision(
    const String& message
) {
    if (gProvisioned) {
        notifyText(
            "PROVISION_ERROR|already_provisioned"
        );
        return false;
    }

    if (
        static_cast<int32_t>(
            millis() -
            gProvisionWindowDeadline
        ) > 0
    ) {
        notifyText(
            "PROVISION_LOCKED|reboot_required"
        );
        return false;
    }

    String keyText =
        fieldAt(
            message,
            '|',
            1
        );

    String proofText =
        fieldAt(
            message,
            '|',
            2
        );

    uint8_t candidateKey[32];
    uint8_t suppliedProof[32];

    if (
        !hexToBytes(
            keyText,
            candidateKey,
            sizeof(candidateKey)
        ) ||
        !hexToBytes(
            proofText,
            suppliedProof,
            sizeof(suppliedProof)
        )
    ) {
        notifyText(
            "PROVISION_ERROR|format"
        );
        return false;
    }

    uint8_t expectedProof[32];

    hmacSha256WithKey(
        candidateKey,
        gProvisionNonce,
        sizeof(gProvisionNonce),
        expectedProof
    );

    if (
        !constantTimeEqual(
            suppliedProof,
            expectedProof,
            sizeof(expectedProof)
        )
    ) {
        notifyText(
            "PROVISION_ERROR|proof"
        );
        return false;
    }

    if (
        !storeProvisionedKey(
            candidateKey
        )
    ) {
        notifyText(
            "PROVISION_ERROR|storage"
        );
        return false;
    }

    memset(
        candidateKey,
        0,
        sizeof(candidateKey)
    );

    memset(
        suppliedProof,
        0,
        sizeof(suppliedProof)
    );

    memset(
        expectedProof,
        0,
        sizeof(expectedProof)
    );

    notifyText(
        "PROVISION_OK|" PL_FW_VERSION
    );

    Serial.println(
        "PrivateLink HMAC key provisioned to NVS."
    );

    return true;
}

static void processControl(
    const String& message
) {
    if (message == "HELLO") {
        gAuthenticated = false;

        if (!gProvisioned) {
            fillRandomBytes(
                gProvisionNonce,
                sizeof(gProvisionNonce)
            );

            notifyText(
                "PROVISION_REQUIRED|" PL_FW_VERSION "|" +
                gNodeId +
                "|" +
                bytesToHex(
                    gProvisionNonce,
                    sizeof(gProvisionNonce)
                )
            );

            return;
        }

        fillRandomBytes(
            gChallenge,
            sizeof(gChallenge)
        );

        if (gKeyVersion == 0) {
            notifyText(
                "HELLO|" +
                bytesToHex(
                    gChallenge,
                    sizeof(gChallenge)
                )
            );
        } else {
            notifyText(
                "HELLO2|" +
                gNodeId +
                "|" +
                bytesToHex(
                    gChallenge,
                    sizeof(gChallenge)
                )
            );
        }

        return;
    }

    if (
        message.startsWith(
            "PROVISION|"
        )
    ) {
        if (
            handleProvision(
                message
            )
        ) {
            delay(50);

            fillRandomBytes(
                gChallenge,
                sizeof(gChallenge)
            );

            notifyText(
                "HELLO2|" +
                gNodeId +
                "|" +
                bytesToHex(
                    gChallenge,
                    sizeof(gChallenge)
                )
            );
        }

        return;
    }

    if (
        message.startsWith(
            "AUTH|"
        )
    ) {
        if (!gProvisioned) {
            notifyText(
                "AUTH_FAIL|not_provisioned"
            );
            return;
        }

        String suppliedText =
            fieldAt(
                message,
                '|',
                1
            );

        uint8_t supplied[32];

        if (
            !hexToBytes(
                suppliedText,
                supplied,
                sizeof(supplied)
            )
        ) {
            notifyText(
                "AUTH_FAIL|format"
            );
            return;
        }

        uint8_t expected[32];

        hmacSha256(
            gChallenge,
            sizeof(gChallenge),
            expected
        );

        if (
            !constantTimeEqual(
                supplied,
                expected,
                sizeof(expected)
            )
        ) {
            notifyText(
                "AUTH_FAIL|hmac"
            );
            return;
        }

        gAuthenticated = true;

        notifyText(
            "AUTH_OK|" PL_FW_VERSION "|" +
            gNodeId
        );

        return;
    }

    if (!gAuthenticated) {
        notifyText(
            "ERR|not_authenticated"
        );
        return;
    }

    if (
        message.startsWith(
            "KEY_MIGRATE|"
        )
    ) {
        if (gKeyVersion != 0) {
            notifyText(
                "KEY_MIGRATE_ERROR|already_current"
            );
            return;
        }

        String keyText =
            fieldAt(
                message,
                '|',
                1
            );

        uint8_t migratedKey[32];

        if (
            !hexToBytes(
                keyText,
                migratedKey,
                sizeof(migratedKey)
            )
        ) {
            notifyText(
                "KEY_MIGRATE_ERROR|format"
            );
            return;
        }

        if (
            !storeProvisionedKey(
                migratedKey,
                1
            )
        ) {
            memset(
                migratedKey,
                0,
                sizeof(migratedKey)
            );

            notifyText(
                "KEY_MIGRATE_ERROR|storage"
            );
            return;
        }

        memset(
            migratedKey,
            0,
            sizeof(migratedKey)
        );

        gAuthenticated = false;

        notifyText(
            "KEY_MIGRATE_OK|" +
            gNodeId
        );

        delay(50);

        fillRandomBytes(
            gChallenge,
            sizeof(gChallenge)
        );

        notifyText(
            "HELLO2|" +
            gNodeId +
            "|" +
            bytesToHex(
                gChallenge,
                sizeof(gChallenge)
            )
        );

        return;
    }

    if (message == "PING") {
        notifyText(
            "PONG|" +
            String(millis())
        );
        return;
    }

    if (message == "INFO") {
        notifyText(
            deviceInfoText()
        );
        return;
    }

    if (message == "STATUS") {
        notifyText(
            telemetryText()
        );
        return;
    }

    if (message == "REBOOT") {
        notifyText("OK|reboot");
        delay(300);
        ESP.restart();
        return;
    }

    if (
        message.startsWith(
            "LAB_WIFI|"
        )
    ) {
        if (
            gOtaActive ||
            gFastOtaActive ||
            LabTest::isBusy()
        ) {
            notifyText(
                "LAB_ERROR|reason=busy"
            );
            return;
        }

        String error;

        const bool ok =
            LabTest::setWifiCredentialsBase64(
                fieldAt(
                    message,
                    '|',
                    1
                ),
                fieldAt(
                    message,
                    '|',
                    2
                ),
                error
            );

        notifyText(
            ok
                ? "LAB_WIFI_OK"
                : (
                    "LAB_ERROR|reason=" +
                    error
                )
        );

        return;
    }

    if (
        message.startsWith(
            "LAB_CONFIG|"
        )
    ) {
        if (
            gOtaActive ||
            gFastOtaActive ||
            LabTest::isBusy()
        ) {
            notifyText(
                "LAB_ERROR|reason=busy"
            );
            return;
        }

        const String target =
            fieldAt(
                message,
                '|',
                1
            );

        const uint16_t port =
            static_cast<uint16_t>(
                strtoul(
                    fieldAt(
                        message,
                        '|',
                        2
                    ).c_str(),
                    nullptr,
                    10
                )
            );

        const uint8_t level =
            static_cast<uint8_t>(
                strtoul(
                    fieldAt(
                        message,
                        '|',
                        3
                    ).c_str(),
                    nullptr,
                    10
                )
            );

        const uint32_t durationSeconds =
            static_cast<uint32_t>(
                strtoul(
                    fieldAt(
                        message,
                        '|',
                        4
                    ).c_str(),
                    nullptr,
                    10
                )
            );

        String error;

        const bool ok =
            LabTest::configure(
                target,
                port,
                level,
                durationSeconds,
                error
            );

        notifyText(
            ok
                ? LabTest::statusText()
                : (
                    "LAB_ERROR|reason=" +
                    error
                )
        );

        return;
    }

    if (
        message ==
        "LAB_START"
    ) {
        if (
            gOtaActive ||
            gFastOtaActive
        ) {
            notifyText(
                "LAB_ERROR|reason=busy"
            );
            return;
        }

        String error;

        const bool ok =
            LabTest::start(
                gNodeId,
                error
            );

        if (!ok) {
            notifyText(
                "LAB_ERROR|reason=" +
                error
            );
        }

        return;
    }

    if (
        message ==
        "LAB_STOP"
    ) {
        LabTest::stop(
            "app_stop"
        );

        return;
    }

    if (
        message ==
        "LAB_STATUS"
    ) {
        notifyText(
            LabTest::statusText()
        );

        return;
    }

    if (
        message ==
        "FAST_OTA_BEGIN"
    ) {
        if (LabTest::isBusy()) {
            notifyText(
                "FAST_OTA_ERROR|lab_active"
            );
            return;
        }
        startFastOtaSession();
        return;
    }

    if (
        message ==
        "FAST_OTA_CANCEL"
    ) {
        if (gFastOtaActive) {
            stopFastOtaSession();
        }

        notifyText(
            "FAST_OTA_STOPPED"
        );
        return;
    }

    if (
        message.startsWith(
            "OTA_BEGIN|"
        )
    ) {
        if (LabTest::isBusy()) {
            notifyText(
                "OTA_ERROR|lab_active"
            );
            return;
        }

        beginOta(message);
        return;
    }

    if (message == "OTA_END") {
        finishOta();
        return;
    }

    if (message == "OTA_ABORT") {
        abortOta(
            "cancelled_by_app"
        );
        return;
    }

    notifyText(
        "ERR|unknown_command"
    );
}

static bool requireEncryptedLink(
    NimBLEConnInfo& connInfo
) {
    if (connInfo.isEncrypted()) {
        gLinkEncrypted = true;
        return true;
    }

    Serial.println(
        "Rejected GATT operation: link is not encrypted."
    );

    notifyText(
        "ERR|link_not_encrypted"
    );

    if (gServer != nullptr) {
        gServer->disconnect(
            connInfo
        );
    }

    return false;
}

class ControlCallbacks :
    public NimBLECharacteristicCallbacks {
    void onWrite(
        NimBLECharacteristic* characteristic,
        NimBLEConnInfo& connInfo
    ) override {
        if (
            !requireEncryptedLink(
                connInfo
            )
        ) {
            return;
        }

        std::string raw =
            characteristic->getValue();

        String text;
        text.reserve(raw.size());

        for (char c : raw) {
            text += c;
        }

        processControl(text);
    }
};

class OtaCallbacks :
    public NimBLECharacteristicCallbacks {
    void onWrite(
        NimBLECharacteristic* characteristic,
        NimBLEConnInfo& connInfo
    ) override {
        if (
            !requireEncryptedLink(
                connInfo
            )
        ) {
            return;
        }

        std::string raw =
            characteristic->getValue();

        receiveOtaData(
            reinterpret_cast<const uint8_t*>(
                raw.data()
            ),
            raw.size()
        );
    }
};

class ServerCallbacks :
    public NimBLEServerCallbacks {
    uint32_t onPassKeyDisplay() override {
        return BLE_PASSKEY;
    }

    void onConnect(
        NimBLEServer* server,
        NimBLEConnInfo& connInfo
    ) override {
        gConnected = true;
        gAuthenticated = false;
        gLinkEncrypted =
            connInfo.isEncrypted();

        server->updateConnParams(
            connInfo.getConnHandle(),
            24,
            48,
            0,
            400
        );

        Serial.println(
            "BLE client connected."
        );
    }

    void onAuthenticationComplete(
        NimBLEConnInfo& connInfo
    ) override {
        gLinkEncrypted =
            connInfo.isEncrypted();

        Serial.printf(
            "BLE security complete: encrypted=%d authenticated=%d bonded=%d\n",
            connInfo.isEncrypted() ? 1 : 0,
            connInfo.isAuthenticated() ? 1 : 0,
            connInfo.isBonded() ? 1 : 0
        );

        if (!connInfo.isEncrypted()) {
            if (gServer != nullptr) {
                gServer->disconnect(
                    connInfo
                );
            }
        }
    }

    void onDisconnect(
        NimBLEServer* server,
        NimBLEConnInfo& connInfo,
        int reason
    ) override {
        gConnected = false;
        gLinkEncrypted = false;
        gAuthenticated = false;

        if (gOtaActive) {
            Update.abort();
            freeDigests();

            gOtaActive = false;
            gOtaSize = 0;
            gOtaReceived = 0;
        }

        if (LabTest::isBusy()) {
            LabTest::stop(
                "control_lost"
            );
        }

        gNextResearchAt =
            millis() +
            RESEARCH_IDLE_DELAY_MS;

        delay(30);

        if (
            gAdvertising != nullptr &&
            !gFastOtaActive
        ) {
            gAdvertising->start();
        }

        Serial.printf(
            "BLE client disconnected: reason=%d\n",
            reason
        );
    }
};

void setup() {
    Serial.begin(115200);
    delay(250);

    wifiOff();

    gNodeId =
        nodeIdFromEfuse();

    gProvisioned =
        loadProvisionedKey();

    if (!gProvisioned) {
        gProvisionWindowDeadline =
            millis() +
            PROVISION_WINDOW_MS;
    }

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

    gServer =
        NimBLEDevice::createServer();

    gServer->setCallbacks(
        new ServerCallbacks()
    );

    gServer->advertiseOnDisconnect(
        false
    );

    NimBLEService* service =
        gServer->createService(
            SERVICE_UUID
        );

    NimBLECharacteristic* control =
        service->createCharacteristic(
            CONTROL_UUID,
            NIMBLE_PROPERTY::WRITE,
            512
        );

    control->setCallbacks(
        new ControlCallbacks()
    );

    gResponse =
        service->createCharacteristic(
            RESPONSE_UUID,
            NIMBLE_PROPERTY::NOTIFY,
            512
        );

    NimBLECharacteristic* ota =
        service->createCharacteristic(
            OTA_UUID,
            NIMBLE_PROPERTY::WRITE,
            512
        );

    ota->setCallbacks(
        new OtaCallbacks()
    );

    gServer->start();

    gAdvertising =
        NimBLEDevice::getAdvertising();

    gAdvertising->addServiceUUID(
        SERVICE_UUID
    );

    gAdvertising->setMinInterval(
        1280
    );

    gAdvertising->setMaxInterval(
        1920
    );

    gAdvertising->start();

    gNextResearchAt =
        millis() +
        RESEARCH_IDLE_DELAY_MS;

    Serial.println();
    Serial.println(
        "ESPhub Multi-Node + LabTest V1"
    );

    Serial.println(
        "Firmware: " PL_FW_VERSION
    );

    Serial.println(
        "Model: " PL_MODEL
    );

    Serial.println(
        "Board: " PL_BOARD
    );

    Serial.println(
        "Role: " PL_ROLE
    );

    Serial.printf(
        "Node ID: %s\n",
        gNodeId.c_str()
    );

    Serial.printf(
        "Flash detected: %u bytes\n",
        static_cast<unsigned>(
            ESP.getFlashChipSize()
        )
    );

    Serial.println(
        "Advertising iniciado."
    );

    Serial.printf(
        "Provisioned: %s\n",
        gProvisioned
            ? "yes"
            : "no"
    );

    Serial.printf(
        "Key version: %u%s\n",
        static_cast<unsigned>(
            gKeyVersion
        ),
        (
            gProvisioned &&
            gKeyVersion == 0
        )
            ? " (legacy migration pending)"
            : ""
    );

    Serial.println(
        "Normal mode: Wi-Fi OFF."
    );

    Serial.println(
        "Research runs only while BLE is disconnected."
    );

    Serial.println(
        "Fast OTA enables a temporary visible WPA2 AP only for the update session."
    );
}

void loop() {
    serviceFastOta();
    LabTest::service();

    String labNotification;

    if (
        LabTest::pollNotification(
            labNotification
        )
    ) {
        notifyText(
            labNotification
        );
    }

    uint32_t now = millis();

    if (
        !gConnected &&
        !gOtaActive &&
        !gFastOtaActive &&
        !LabTest::isBusy() &&
        static_cast<int32_t>(
            now -
            gNextResearchAt
        ) >= 0
    ) {
        runResearchSurvey();

        gNextResearchAt =
            millis() +
            RESEARCH_INTERVAL_MS;
    }

    if (
        gConnected &&
        gAuthenticated &&
        !gOtaActive &&
        now -
            gLastTelemetry >=
            5000
    ) {
        gLastTelemetry = now;

        notifyText(
            telemetryText()
        );
    }

    delay(20);
}
