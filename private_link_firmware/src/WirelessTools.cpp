#include "WirelessTools.h"

#include <WiFi.h>
#include <esp_wifi.h>
#include <mbedtls/base64.h>

namespace WirelessTools {
namespace {

enum class State : uint8_t {
    Idle,
    Scanning,
    ScanEmitting,
    Monitoring
};

struct Counters {
    volatile uint32_t total = 0;
    volatile uint32_t management = 0;
    volatile uint32_t control = 0;
    volatile uint32_t data = 0;

    volatile uint32_t beacon = 0;
    volatile uint32_t probeRequest = 0;
    volatile uint32_t probeResponse = 0;
    volatile uint32_t auth = 0;
    volatile uint32_t assoc = 0;
    volatile uint32_t reassoc = 0;
    volatile uint32_t deauthSeen = 0;
    volatile uint32_t disassocSeen = 0;

    volatile uint32_t eapol = 0;
    volatile uint32_t eapolM1 = 0;
    volatile uint32_t eapolM2 = 0;
    volatile uint32_t eapolM3 = 0;
    volatile uint32_t eapolM4 = 0;

    volatile int64_t rssiSum = 0;
    volatile uint32_t rssiSamples = 0;
};

struct Runtime {
    State state = State::Idle;

    String pendingNotification;

    int scanCount = 0;
    int scanEmitIndex = 0;
    int scanEmitLimit = 0;

    uint8_t channel = 0;
    bool hopping = false;
    uint8_t hopChannel = 1;
    uint32_t durationMs = 0;
    uint32_t startedMs = 0;
    uint32_t lastTelemetryMs = 0;
    uint32_t lastRateMs = 0;
    uint32_t lastRateTotal = 0;
    uint32_t actualFps = 0;
    uint32_t lastHopMs = 0;

    bool filterBssid = false;
    uint8_t bssid[6] = {0};

    Counters counters;
};

Runtime g;

static const uint32_t TELEMETRY_INTERVAL_MS = 1000;
static const uint32_t HOP_INTERVAL_MS = 350;
static const uint8_t HOP_MIN_CHANNEL = 1;
static const uint8_t HOP_MAX_CHANNEL = 11;
static const uint32_t MIN_MONITOR_SECONDS = 5;
static const uint32_t MAX_MONITOR_SECONDS = 120;
static const int MAX_SCAN_RESULTS = 24;

void publish(const String& text) {
    if (g.pendingNotification.length() == 0) {
        g.pendingNotification = text;
    }
}

void radioOff() {
    esp_wifi_set_promiscuous(false);
    WiFi.disconnect(true, false);
    delay(10);
    WiFi.mode(WIFI_OFF);
}

String base64Encode(const String& input) {
    if (input.length() == 0) {
        return "";
    }

    size_t outLen = 0;
    const size_t required =
        4 * ((input.length() + 2) / 3) + 1;

    unsigned char* out =
        static_cast<unsigned char*>(
            malloc(required)
        );

    if (out == nullptr) {
        return "";
    }

    const int rc =
        mbedtls_base64_encode(
            out,
            required,
            &outLen,
            reinterpret_cast<const unsigned char*>(
                input.c_str()
            ),
            input.length()
        );

    if (rc != 0) {
        free(out);
        return "";
    }

    out[outLen] = 0;
    String encoded(
        reinterpret_cast<const char*>(out)
    );
    free(out);
    return encoded;
}

bool parseBssid(
    const String& text,
    uint8_t out[6]
) {
    if (text.length() == 0) {
        return false;
    }

    unsigned int values[6] = {0};

    if (
        sscanf(
            text.c_str(),
            "%x:%x:%x:%x:%x:%x",
            &values[0],
            &values[1],
            &values[2],
            &values[3],
            &values[4],
            &values[5]
        ) != 6
    ) {
        return false;
    }

    for (int i = 0; i < 6; ++i) {
        if (values[i] > 255) {
            return false;
        }

        out[i] =
            static_cast<uint8_t>(
                values[i]
            );
    }

    return true;
}

bool macEquals(
    const uint8_t* a,
    const uint8_t* b
) {
    for (int i = 0; i < 6; ++i) {
        if (a[i] != b[i]) {
            return false;
        }
    }

    return true;
}

bool frameMatchesTarget(
    const uint8_t* frame,
    uint16_t len
) {
    if (!g.filterBssid) {
        return true;
    }

    if (len >= 10 && macEquals(frame + 4, g.bssid)) {
        return true;
    }

    if (len >= 16 && macEquals(frame + 10, g.bssid)) {
        return true;
    }

    if (len >= 22 && macEquals(frame + 16, g.bssid)) {
        return true;
    }

    return false;
}

void classifyEapol(
    const uint8_t* frame,
    uint16_t len,
    uint16_t frameControl,
    uint8_t subtype
) {
    const bool protectedFrame =
        (frameControl & 0x4000) != 0;

    if (protectedFrame) {
        return;
    }

    size_t headerLen = 24;

    const bool toDs =
        (frameControl & 0x0100) != 0;

    const bool fromDs =
        (frameControl & 0x0200) != 0;

    if (toDs && fromDs) {
        headerLen += 6;
    }

    if ((subtype & 0x08) != 0) {
        headerLen += 2;
    }

    // LLC/SNAP (8) + EAPOL header (4) + key descriptor/type + key_info (2).
    if (len < headerLen + 15) {
        return;
    }

    const uint8_t* llc =
        frame + headerLen;

    static const uint8_t EAPOL_LLC[8] = {
        0xAA, 0xAA, 0x03,
        0x00, 0x00, 0x00,
        0x88, 0x8E
    };

    if (
        memcmp(
            llc,
            EAPOL_LLC,
            sizeof(EAPOL_LLC)
        ) != 0
    ) {
        return;
    }

    const uint8_t* eapol =
        llc + sizeof(EAPOL_LLC);

    // EAPOL packet type 3 = EAPOL-Key.
    if (eapol[1] != 3) {
        return;
    }

    ++g.counters.eapol;

    // EAPOL header is 4 bytes, descriptor type is one byte.
    const uint16_t keyInfo =
        (
            static_cast<uint16_t>(
                eapol[5]
            ) << 8
        ) |
        static_cast<uint16_t>(
            eapol[6]
        );

    const bool pairwise =
        (keyInfo & (1U << 3)) != 0;

    const bool install =
        (keyInfo & (1U << 6)) != 0;

    const bool ack =
        (keyInfo & (1U << 7)) != 0;

    const bool mic =
        (keyInfo & (1U << 8)) != 0;

    const bool secure =
        (keyInfo & (1U << 9)) != 0;

    if (!pairwise) {
        return;
    }

    if (ack && !mic) {
        ++g.counters.eapolM1;
    } else if (!ack && mic && !secure) {
        ++g.counters.eapolM2;
    } else if (ack && mic && (install || secure)) {
        ++g.counters.eapolM3;
    } else if (!ack && mic && secure) {
        ++g.counters.eapolM4;
    }
}

void promiscuousCallback(
    void* buffer,
    wifi_promiscuous_pkt_type_t packetType
) {
    if (
        g.state != State::Monitoring ||
        buffer == nullptr
    ) {
        return;
    }

    const wifi_promiscuous_pkt_t* packet =
        static_cast<const wifi_promiscuous_pkt_t*>(
            buffer
        );

    const uint16_t len =
        packet->rx_ctrl.sig_len;

    if (len < 2) {
        return;
    }

    const uint8_t* frame =
        packet->payload;

    if (
        !frameMatchesTarget(
            frame,
            len
        )
    ) {
        return;
    }

    const uint16_t frameControl =
        static_cast<uint16_t>(
            frame[0]
        ) |
        (
            static_cast<uint16_t>(
                frame[1]
            ) << 8
        );

    const uint8_t type =
        (frameControl >> 2) & 0x03;

    const uint8_t subtype =
        (frameControl >> 4) & 0x0F;

    ++g.counters.total;
    g.counters.rssiSum +=
        packet->rx_ctrl.rssi;
    ++g.counters.rssiSamples;

    if (
        packetType ==
            WIFI_PKT_MGMT ||
        type == 0
    ) {
        ++g.counters.management;

        switch (subtype) {
            case 8:
                ++g.counters.beacon;
                break;
            case 4:
                ++g.counters.probeRequest;
                break;
            case 5:
                ++g.counters.probeResponse;
                break;
            case 11:
                ++g.counters.auth;
                break;
            case 0:
                ++g.counters.assoc;
                break;
            case 2:
                ++g.counters.reassoc;
                break;
            case 12:
                ++g.counters.deauthSeen;
                break;
            case 10:
                ++g.counters.disassocSeen;
                break;
            default:
                break;
        }

        return;
    }

    if (
        packetType ==
            WIFI_PKT_CTRL ||
        type == 1
    ) {
        ++g.counters.control;
        return;
    }

    if (
        packetType ==
            WIFI_PKT_DATA ||
        type == 2
    ) {
        ++g.counters.data;

        classifyEapol(
            frame,
            len,
            frameControl,
            subtype
        );
    }
}

void resetCounters() {
    g.counters.total = 0;
    g.counters.management = 0;
    g.counters.control = 0;
    g.counters.data = 0;
    g.counters.beacon = 0;
    g.counters.probeRequest = 0;
    g.counters.probeResponse = 0;
    g.counters.auth = 0;
    g.counters.assoc = 0;
    g.counters.reassoc = 0;
    g.counters.deauthSeen = 0;
    g.counters.disassocSeen = 0;
    g.counters.eapol = 0;
    g.counters.eapolM1 = 0;
    g.counters.eapolM2 = 0;
    g.counters.eapolM3 = 0;
    g.counters.eapolM4 = 0;
    g.counters.rssiSum = 0;
    g.counters.rssiSamples = 0;

    g.actualFps = 0;
    g.lastRateTotal = 0;
}

String monitorText(
    const char* prefix
) {
    const uint32_t elapsed =
        g.startedMs == 0
            ? 0
            : millis() - g.startedMs;

    int avgRssi = -127;

    const uint32_t samples =
        g.counters.rssiSamples;

    if (samples > 0) {
        avgRssi =
            static_cast<int>(
                g.counters.rssiSum /
                static_cast<int64_t>(
                    samples
                )
            );
    }

    String text = prefix;
    text += "|state=";
    text +=
        g.state == State::Monitoring
            ? "RUNNING"
            : "IDLE";
    text += "|channel=" +
        String(g.hopping ? g.hopChannel : g.channel);
    text += "|hopping=" +
        String(g.hopping ? 1 : 0);
    text += "|elapsed_ms=" +
        String(elapsed);
    text += "|fps=" +
        String(g.actualFps);
    text += "|rssi_avg=" +
        String(avgRssi);

    text += "|frames=" +
        String(g.counters.total);
    text += "|mgmt=" +
        String(g.counters.management);
    text += "|ctrl=" +
        String(g.counters.control);
    text += "|data=" +
        String(g.counters.data);

    text += "|beacon=" +
        String(g.counters.beacon);
    text += "|probe_req=" +
        String(g.counters.probeRequest);
    text += "|probe_resp=" +
        String(g.counters.probeResponse);
    text += "|auth=" +
        String(g.counters.auth);
    text += "|assoc=" +
        String(g.counters.assoc);
    text += "|reassoc=" +
        String(g.counters.reassoc);
    text += "|deauth_seen=" +
        String(g.counters.deauthSeen);
    text += "|disassoc_seen=" +
        String(g.counters.disassocSeen);

    text += "|eapol=" +
        String(g.counters.eapol);
    text += "|m1=" +
        String(g.counters.eapolM1);
    text += "|m2=" +
        String(g.counters.eapolM2);
    text += "|m3=" +
        String(g.counters.eapolM3);
    text += "|m4=" +
        String(g.counters.eapolM4);

    return text;
}

}  // namespace

bool requestScan(
    String& error
) {
    if (isBusy()) {
        error = "wireless_busy";
        return false;
    }

    WiFi.mode(WIFI_STA);
    WiFi.disconnect(false, false);
    delay(30);

    const int started =
        WiFi.scanNetworks(
            true,
            true
        );

    if (started == WIFI_SCAN_FAILED) {
        radioOff();
        error = "scan_start_failed";
        return false;
    }

    g.scanCount = 0;
    g.scanEmitIndex = 0;
    g.scanEmitLimit = 0;
    g.state = State::Scanning;

    publish(
        "WIFI_SCAN_STARTED"
    );

    error = "";
    return true;
}

bool startMonitor(
    uint8_t channel,
    uint32_t durationSeconds,
    const String& bssid,
    String& error
) {
    if (isBusy()) {
        error = "wireless_busy";
        return false;
    }

    if (
        durationSeconds <
            MIN_MONITOR_SECONDS ||
        durationSeconds >
            MAX_MONITOR_SECONDS
    ) {
        error = "duration_5_to_120";
        return false;
    }

    if (
        channel != 0 &&
        (
            channel <
                HOP_MIN_CHANNEL ||
            channel >
                HOP_MAX_CHANNEL
        )
    ) {
        error = "channel_0_or_1_to_11";
        return false;
    }

    g.filterBssid = false;
    memset(
        g.bssid,
        0,
        sizeof(g.bssid)
    );

    if (bssid.length() > 0) {
        if (
            !parseBssid(
                bssid,
                g.bssid
            )
        ) {
            error = "bssid";
            return false;
        }

        g.filterBssid = true;
    }

    WiFi.mode(WIFI_STA);
    WiFi.disconnect(false, false);
    delay(40);

    g.channel = channel;
    g.hopping =
        channel == 0;

    g.hopChannel =
        g.hopping
            ? HOP_MIN_CHANNEL
            : channel;

    esp_err_t rc =
        esp_wifi_set_channel(
            g.hopChannel,
            WIFI_SECOND_CHAN_NONE
        );

    if (rc != ESP_OK) {
        radioOff();
        error =
            "set_channel_" +
            String(
                static_cast<int>(rc)
            );
        return false;
    }

    wifi_promiscuous_filter_t filter = {};
    filter.filter_mask =
        WIFI_PROMIS_FILTER_MASK_MGMT |
        WIFI_PROMIS_FILTER_MASK_CTRL |
        WIFI_PROMIS_FILTER_MASK_DATA;

    esp_wifi_set_promiscuous_filter(
        &filter
    );

    esp_wifi_set_promiscuous_rx_cb(
        promiscuousCallback
    );

    rc =
        esp_wifi_set_promiscuous(
            true
        );

    if (rc != ESP_OK) {
        esp_wifi_set_promiscuous_rx_cb(
            nullptr
        );
        radioOff();
        error =
            "promiscuous_" +
            String(
                static_cast<int>(rc)
            );
        return false;
    }

    resetCounters();

    g.durationMs =
        durationSeconds * 1000UL;
    g.startedMs = millis();
    g.lastTelemetryMs = g.startedMs;
    g.lastRateMs = g.startedMs;
    g.lastHopMs = g.startedMs;
    g.state = State::Monitoring;

    publish(
        monitorText(
            "MONITOR_STARTED"
        )
    );

    error = "";
    return true;
}

void stopMonitor(
    const char* reason
) {
    if (
        g.state !=
            State::Monitoring
    ) {
        return;
    }

    String text =
        monitorText(
            "MONITOR_DONE"
        );

    text += "|reason=";
    text +=
        reason == nullptr
            ? "stopped"
            : reason;

    esp_wifi_set_promiscuous(
        false
    );

    esp_wifi_set_promiscuous_rx_cb(
        nullptr
    );

    g.state = State::Idle;
    publish(text);

    radioOff();
}

void service() {
    if (
        g.state ==
            State::Scanning
    ) {
        const int result =
            WiFi.scanComplete();

        if (result == WIFI_SCAN_RUNNING) {
            return;
        }

        if (result < 0) {
            WiFi.scanDelete();
            radioOff();
            g.state = State::Idle;
            publish(
                "WIFI_SCAN_ERROR|reason=scan_failed"
            );
            return;
        }

        g.scanCount = result;
        g.scanEmitLimit =
            min(
                result,
                MAX_SCAN_RESULTS
            );

        g.scanEmitIndex = 0;
        g.state =
            State::ScanEmitting;

        publish(
            "WIFI_SCAN_RESULT|count=" +
            String(result) +
            "|shown=" +
            String(g.scanEmitLimit)
        );

        return;
    }

    if (
        g.state ==
            State::ScanEmitting
    ) {
        if (
            g.pendingNotification.length() >
            0
        ) {
            return;
        }

        if (
            g.scanEmitIndex <
            g.scanEmitLimit
        ) {
            const int i =
                g.scanEmitIndex++;

            String ssid =
                WiFi.SSID(i);

            String text =
                "WIFI_AP";

            text += "|index=" +
                String(i);

            text += "|ssid64=" +
                base64Encode(ssid);

            text += "|bssid=" +
                WiFi.BSSIDstr(i);

            text += "|channel=" +
                String(
                    WiFi.channel(i)
                );

            text += "|rssi=" +
                String(
                    WiFi.RSSI(i)
                );

            text += "|enc=" +
                String(
                    static_cast<int>(
                        WiFi.encryptionType(i)
                    )
                );

            text += "|open=" +
                String(
                    WiFi.encryptionType(i) ==
                        WIFI_AUTH_OPEN
                            ? 1
                            : 0
                );

            publish(text);
            return;
        }

        WiFi.scanDelete();
        radioOff();
        g.state = State::Idle;

        publish(
            "WIFI_SCAN_DONE|count=" +
            String(g.scanCount) +
            "|shown=" +
            String(g.scanEmitLimit)
        );

        return;
    }

    if (
        g.state !=
            State::Monitoring
    ) {
        return;
    }

    const uint32_t now =
        millis();

    if (
        now -
            g.startedMs >=
        g.durationMs
    ) {
        stopMonitor(
            "duration_complete"
        );
        return;
    }

    if (
        g.hopping &&
        now -
            g.lastHopMs >=
        HOP_INTERVAL_MS
    ) {
        g.lastHopMs = now;

        ++g.hopChannel;

        if (
            g.hopChannel >
            HOP_MAX_CHANNEL
        ) {
            g.hopChannel =
                HOP_MIN_CHANNEL;
        }

        esp_wifi_set_channel(
            g.hopChannel,
            WIFI_SECOND_CHAN_NONE
        );
    }

    if (
        now -
            g.lastRateMs >=
        TELEMETRY_INTERVAL_MS
    ) {
        const uint32_t total =
            g.counters.total;

        const uint32_t delta =
            total -
            g.lastRateTotal;

        const uint32_t elapsed =
            max(
                static_cast<uint32_t>(1),
                now -
                    g.lastRateMs
            );

        g.actualFps =
            static_cast<uint32_t>(
                (
                    static_cast<uint64_t>(
                        delta
                    ) * 1000ULL
                ) /
                elapsed
            );

        g.lastRateTotal = total;
        g.lastRateMs = now;
    }

    if (
        now -
            g.lastTelemetryMs >=
        TELEMETRY_INTERVAL_MS
    ) {
        g.lastTelemetryMs = now;

        publish(
            monitorText(
                "MONITOR_TEL"
            )
        );
    }
}

bool isBusy() {
    return
        g.state != State::Idle;
}

bool isMonitoring() {
    return
        g.state == State::Monitoring;
}

String statusText() {
    if (
        g.state ==
            State::Monitoring
    ) {
        return monitorText(
            "MONITOR_STATUS"
        );
    }

    if (
        g.state ==
            State::Scanning ||
        g.state ==
            State::ScanEmitting
    ) {
        return
            "WIRELESS_STATUS|state=SCANNING";
    }

    return
        "WIRELESS_STATUS|state=IDLE";
}

bool pollNotification(
    String& out
) {
    if (
        g.pendingNotification.length() ==
        0
    ) {
        return false;
    }

    out =
        g.pendingNotification;

    g.pendingNotification = "";
    return true;
}

}  // namespace WirelessTools
