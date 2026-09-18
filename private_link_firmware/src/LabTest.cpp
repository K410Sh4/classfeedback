#include "LabTest.h"

#include <WiFi.h>
#include <WiFiUdp.h>
#include <mbedtls/base64.h>

#include "BoardConfig.h"

namespace LabTest {
namespace {

enum class State : uint8_t {
    Idle,
    Configured,
    Connecting,
    Running,
    Error
};

struct Profile {
    uint16_t pps;
    uint16_t bytes;
};

struct Runtime {
    State state = State::Idle;

    String ssid;
    String password;
    String nodeId;

    IPAddress target;
    uint16_t port = 0;
    uint8_t level = 0;
    uint32_t durationMs = 0;

    uint32_t connectStartedMs = 0;
    uint32_t startedMs = 0;
    uint32_t lastTelemetryMs = 0;
    uint32_t lastRateWindowMs = 0;
    uint32_t nextSendUs = 0;

    uint64_t txPackets = 0;
    uint64_t txBytes = 0;
    uint64_t rateWindowPackets = 0;
    uint32_t actualPps = 0;

    String pendingNotification;
};

Runtime g;
WiFiUDP gUdp;
uint8_t gPacket[PL_LAB_MAX_PACKET_BYTES];

static const uint32_t WIFI_CONNECT_TIMEOUT_MS = 15000;
static const uint32_t TELEMETRY_INTERVAL_MS = 1000;
static const uint32_t MAX_DURATION_SECONDS = 60;
static const uint32_t MIN_DURATION_SECONDS = 5;
static const uint16_t MIN_DESTINATION_PORT = 1024;

Profile profileForLevel(uint8_t level) {
    switch (level) {
        case 1:
            return {
                PL_LAB_LEVEL1_PPS,
                PL_LAB_LEVEL1_BYTES
            };
        case 2:
            return {
                PL_LAB_LEVEL2_PPS,
                PL_LAB_LEVEL2_BYTES
            };
        case 3:
            return {
                PL_LAB_LEVEL3_PPS,
                PL_LAB_LEVEL3_BYTES
            };
        default:
            return {0, 0};
    }
}

const char* stateName(State state) {
    switch (state) {
        case State::Idle:
            return "IDLE";
        case State::Configured:
            return "CONFIGURED";
        case State::Connecting:
            return "CONNECTING";
        case State::Running:
            return "RUNNING";
        case State::Error:
            return "ERROR";
    }

    return "UNKNOWN";
}

void publish(const String& text) {
    if (g.pendingNotification.length() == 0) {
        g.pendingNotification = text;
    }
}

void wifiOff() {
    gUdp.stop();
    WiFi.disconnect(true, false);
    delay(20);
    WiFi.mode(WIFI_OFF);
}

bool decodeBase64(
    const String& input,
    String& output
) {
    if (input.length() == 0) {
        output = "";
        return true;
    }

    const size_t inputLen = input.length();
    size_t outputLen = 0;

    int rc =
        mbedtls_base64_decode(
            nullptr,
            0,
            &outputLen,
            reinterpret_cast<const unsigned char*>(
                input.c_str()
            ),
            inputLen
        );

    if (
        rc != MBEDTLS_ERR_BASE64_BUFFER_TOO_SMALL &&
        rc != 0
    ) {
        return false;
    }

    if (outputLen > 128) {
        return false;
    }

    unsigned char buffer[129] = {0};
    size_t decodedLen = 0;

    rc =
        mbedtls_base64_decode(
            buffer,
            sizeof(buffer) - 1,
            &decodedLen,
            reinterpret_cast<const unsigned char*>(
                input.c_str()
            ),
            inputLen
        );

    if (rc != 0) {
        return false;
    }

    buffer[decodedLen] = 0;
    output =
        String(
            reinterpret_cast<const char*>(
                buffer
            )
        );

    return true;
}

bool isPrivateIpv4(
    const IPAddress& ip
) {
    if (ip[0] == 10) {
        return true;
    }

    if (
        ip[0] == 172 &&
        ip[1] >= 16 &&
        ip[1] <= 31
    ) {
        return true;
    }

    if (
        ip[0] == 192 &&
        ip[1] == 168
    ) {
        return true;
    }

    return false;
}

uint32_t ipToU32(
    const IPAddress& ip
) {
    return (
        (static_cast<uint32_t>(ip[0]) << 24) |
        (static_cast<uint32_t>(ip[1]) << 16) |
        (static_cast<uint32_t>(ip[2]) << 8) |
        static_cast<uint32_t>(ip[3])
    );
}

bool sameSubnet(
    const IPAddress& a,
    const IPAddress& b,
    const IPAddress& mask
) {
    return (
        ipToU32(a) &
        ipToU32(mask)
    ) == (
        ipToU32(b) &
        ipToU32(mask)
    );
}

bool isForbiddenTarget(
    const IPAddress& target,
    const IPAddress& local,
    const IPAddress& gateway,
    const IPAddress& mask
) {
    const uint32_t targetValue =
        ipToU32(target);

    const uint32_t localValue =
        ipToU32(local);

    const uint32_t gatewayValue =
        ipToU32(gateway);

    const uint32_t maskValue =
        ipToU32(mask);

    const uint32_t network =
        localValue & maskValue;

    const uint32_t broadcast =
        network | (~maskValue);

    return (
        targetValue == localValue ||
        targetValue == gatewayValue ||
        targetValue == network ||
        targetValue == broadcast
    );
}

void resetCounters() {
    g.startedMs = 0;
    g.lastTelemetryMs = 0;
    g.lastRateWindowMs = 0;
    g.nextSendUs = 0;
    g.txPackets = 0;
    g.txBytes = 0;
    g.rateWindowPackets = 0;
    g.actualPps = 0;
}

String metricsText(
    const char* prefix
) {
    const Profile profile =
        profileForLevel(g.level);

    const uint32_t elapsed =
        g.startedMs == 0
            ? 0
            : millis() - g.startedMs;

    String text = prefix;
    text += "|node_id=" + g.nodeId;
    text += "|state=";
    text += stateName(g.state);
    text += "|level=" + String(g.level);
    text += "|target=" + g.target.toString();
    text += "|port=" + String(g.port);
    text += "|profile_pps=" + String(profile.pps);
    text += "|packet_bytes=" + String(profile.bytes);
    text += "|tx_packets=" + String(
        static_cast<unsigned long long>(
            g.txPackets
        )
    );
    text += "|tx_bytes=" + String(
        static_cast<unsigned long long>(
            g.txBytes
        )
    );
    text += "|actual_pps=" + String(g.actualPps);
    text += "|elapsed_ms=" + String(elapsed);
    text += "|rssi=";

    if (WiFi.status() == WL_CONNECTED) {
        text += String(WiFi.RSSI());
    } else {
        text += "na";
    }

    return text;
}

void fail(
    const char* reason
) {
    g.state = State::Error;

    String text =
        "LAB_ERROR|reason=";

    text += reason;
    publish(text);

    wifiOff();
}

bool validateAssociatedTarget(
    String& error
) {
    if (WiFi.status() != WL_CONNECTED) {
        error = "wifi_not_connected";
        return false;
    }

    const IPAddress local =
        WiFi.localIP();

    const IPAddress gateway =
        WiFi.gatewayIP();

    const IPAddress mask =
        WiFi.subnetMask();

    if (
        !sameSubnet(
            g.target,
            local,
            mask
        )
    ) {
        error = "target_not_same_subnet";
        return false;
    }

    if (
        isForbiddenTarget(
            g.target,
            local,
            gateway,
            mask
        )
    ) {
        error = "target_forbidden";
        return false;
    }

    return true;
}

void startRunning() {
    String validationError;

    if (
        !validateAssociatedTarget(
            validationError
        )
    ) {
        fail(
            validationError.c_str()
        );
        return;
    }

    if (!gUdp.begin(0)) {
        fail("udp_begin_failed");
        return;
    }

    resetCounters();

    g.state = State::Running;
    g.startedMs = millis();
    g.lastTelemetryMs = g.startedMs;
    g.lastRateWindowMs = g.startedMs;
    g.nextSendUs = micros();

    String text =
        metricsText(
            "LAB_STARTED"
        );

    publish(text);
}

void buildPacket(
    uint16_t packetSize
) {
    memset(
        gPacket,
        0,
        packetSize
    );

    const int written =
        snprintf(
            reinterpret_cast<char*>(
                gPacket
            ),
            packetSize,
            "ESPHUB_LAB_V1|node=%s|level=%u|seq=%llu|tx_ms=%lu|",
            g.nodeId.c_str(),
            static_cast<unsigned>(g.level),
            static_cast<unsigned long long>(
                g.txPackets + 1
            ),
            static_cast<unsigned long>(
                millis()
            )
        );

    size_t start =
        written > 0
            ? static_cast<size_t>(
                min(
                    written,
                    static_cast<int>(
                        packetSize
                    )
                )
            )
            : 0;

    for (
        size_t i = start;
        i < packetSize;
        ++i
    ) {
        gPacket[i] =
            static_cast<uint8_t>(
                'A' + (i % 26)
            );
    }
}

void sendOnePacket(
    const Profile& profile
) {
    buildPacket(profile.bytes);

    if (
        !gUdp.beginPacket(
            g.target,
            g.port
        )
    ) {
        return;
    }

    const size_t written =
        gUdp.write(
            gPacket,
            profile.bytes
        );

    const bool sent =
        written ==
            profile.bytes &&
        gUdp.endPacket() == 1;

    if (sent) {
        ++g.txPackets;
        g.txBytes +=
            profile.bytes;
        ++g.rateWindowPackets;
    }
}

}  // namespace

bool setWifiCredentialsBase64(
    const String& ssidB64,
    const String& passwordB64,
    String& error
) {
    if (isBusy()) {
        error = "lab_busy";
        return false;
    }

    String ssid;
    String password;

    if (
        !decodeBase64(
            ssidB64,
            ssid
        ) ||
        !decodeBase64(
            passwordB64,
            password
        )
    ) {
        error = "base64";
        return false;
    }

    if (
        ssid.length() == 0 ||
        ssid.length() > 32
    ) {
        error = "ssid";
        return false;
    }

    if (
        password.length() != 0 &&
        (
            password.length() < 8 ||
            password.length() > 63
        )
    ) {
        error = "password";
        return false;
    }

    g.ssid = ssid;
    g.password = password;
    error = "";

    return true;
}

bool configure(
    const String& targetIpv4,
    uint16_t port,
    uint8_t level,
    uint32_t durationSeconds,
    String& error
) {
    if (isBusy()) {
        error = "lab_busy";
        return false;
    }

    if (
        g.ssid.length() == 0
    ) {
        error = "wifi_credentials_missing";
        return false;
    }

    IPAddress target;

    if (
        !target.fromString(
            targetIpv4
        ) ||
        !isPrivateIpv4(
            target
        )
    ) {
        error = "target_private_ipv4_required";
        return false;
    }

    if (
        port < MIN_DESTINATION_PORT
    ) {
        error = "port_must_be_1024_or_higher";
        return false;
    }

    const Profile profile =
        profileForLevel(level);

    if (
        profile.pps == 0 ||
        profile.bytes == 0 ||
        profile.bytes >
            PL_LAB_MAX_PACKET_BYTES
    ) {
        error = "level";
        return false;
    }

    if (
        durationSeconds <
            MIN_DURATION_SECONDS ||
        durationSeconds >
            MAX_DURATION_SECONDS
    ) {
        error = "duration_5_to_60";
        return false;
    }

    g.target = target;
    g.port = port;
    g.level = level;
    g.durationMs =
        durationSeconds * 1000UL;
    g.state = State::Configured;
    error = "";

    return true;
}

bool start(
    const String& nodeId,
    String& error
) {
    if (
        g.state != State::Configured
    ) {
        error = "not_configured";
        return false;
    }

    if (nodeId.length() == 0) {
        error = "node_id";
        return false;
    }

    g.nodeId = nodeId;
    resetCounters();

    WiFi.mode(WIFI_STA);
    WiFi.setSleep(false);
    WiFi.begin(
        g.ssid.c_str(),
        g.password.c_str()
    );

    g.state = State::Connecting;
    g.connectStartedMs = millis();

    publish(
        "LAB_CONNECTING|ssid_configured=1"
    );

    error = "";
    return true;
}

void stop(const char* reason) {
    if (
        g.state == State::Idle
    ) {
        return;
    }

    String finalText =
        metricsText(
            "LAB_STOPPED"
        );

    finalText += "|reason=";
    finalText +=
        reason == nullptr
            ? "stopped"
            : reason;

    g.state = State::Idle;
    publish(finalText);
    wifiOff();
}

void service() {
    if (
        g.state == State::Connecting
    ) {
        if (
            WiFi.status() ==
            WL_CONNECTED
        ) {
            startRunning();
            return;
        }

        if (
            millis() -
                g.connectStartedMs >
            WIFI_CONNECT_TIMEOUT_MS
        ) {
            fail(
                "wifi_connect_timeout"
            );
        }

        return;
    }

    if (
        g.state != State::Running
    ) {
        return;
    }

    const uint32_t nowMs =
        millis();

    if (
        nowMs -
            g.startedMs >=
        g.durationMs
    ) {
        stop(
            "duration_complete"
        );
        return;
    }

    if (
        WiFi.status() !=
        WL_CONNECTED
    ) {
        fail(
            "wifi_lost"
        );
        return;
    }

    const Profile profile =
        profileForLevel(
            g.level
        );

    const uint32_t intervalUs =
        max(
            static_cast<uint32_t>(1),
            1000000UL /
                static_cast<uint32_t>(
                    profile.pps
                )
        );

    const uint32_t nowUs =
        micros();

    if (
        static_cast<int32_t>(
            nowUs -
            g.nextSendUs
        ) >= 0
    ) {
        sendOnePacket(profile);

        // No catch-up bursts: if execution is late, restart cadence from now.
        g.nextSendUs =
            micros() +
            intervalUs;
    }

    if (
        nowMs -
            g.lastRateWindowMs >=
        TELEMETRY_INTERVAL_MS
    ) {
        const uint32_t elapsed =
            max(
                static_cast<uint32_t>(1),
                nowMs -
                    g.lastRateWindowMs
            );

        g.actualPps =
            static_cast<uint32_t>(
                (
                    g.rateWindowPackets *
                    1000ULL
                ) /
                elapsed
            );

        g.rateWindowPackets = 0;
        g.lastRateWindowMs = nowMs;
    }

    if (
        nowMs -
            g.lastTelemetryMs >=
        TELEMETRY_INTERVAL_MS
    ) {
        g.lastTelemetryMs = nowMs;

        publish(
            metricsText(
                "LAB_TEL"
            )
        );
    }
}

bool isActive() {
    return (
        g.state ==
            State::Running
    );
}

bool isBusy() {
    return (
        g.state ==
            State::Connecting ||
        g.state ==
            State::Running
    );
}

String statusText() {
    return metricsText(
        "LAB_STATUS"
    );
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

    out = g.pendingNotification;
    g.pendingNotification = "";
    return true;
}

}  // namespace LabTest
