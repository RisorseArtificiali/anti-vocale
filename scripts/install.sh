#!/usr/bin/env bash
# Install debug APK on device via wireless ADB.
# Installs to main profile only (user 0).
#
# Usage:
#   ./scripts/install.sh                              # auto-detect device
#   ./scripts/install.sh telefonopaolo:40615          # override address
#   ./scripts/install.sh telefonopaolo:40615 794996   # override address + pairing code
#
# When no address is given, the device is auto-detected rather than assumed:
#   1. mDNS discovery (`adb mdns services`) → wireless ip:port
#   2. the single already-connected device (install directly)
# The wireless debugging port rotates on reboot/Wi-Fi changes, so auto-detection
# avoids relying on a hardcoded port that is usually stale.
#
# Config can also be set via environment variables or ~/.config/anti-vocale/device.env
# (DEVICE_ADDRESS / DEVICE_PAIRING_CODE). An explicit address always wins over auto-detect.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APK="$PROJECT_DIR/app/build/outputs/apk/debug/app-debug.apk"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

# Load config from env file if present
ENV_FILE="${DEVICE_ENV_FILE:-$HOME/.config/anti-vocale/device.env}"
if [[ -f "$ENV_FILE" ]]; then
    source "$ENV_FILE"
fi

DEVICE="${1:-${DEVICE_ADDRESS:-}}"
PAIRING_CODE="${2:-${DEVICE_PAIRING_CODE:-}}"

if [[ ! -f "$APK" ]]; then
    echo "APK not found at $APK"
    echo "Run ./gradlew assembleDebug first"
    exit 1
fi

APK_AGE_SEC=$(( $(date +%s) - $(date +%s -r "$APK") ))
if (( APK_AGE_SEC > 60 )); then
    AGE_MIN=$(( APK_AGE_SEC / 60 ))
    echo "APK is ${AGE_MIN}m old — rebuild first:"
    echo "  ./gradlew assembleDebug"
    exit 1
fi

# A connected wireless device's ip:port, read straight from `adb devices`. Most reliable
# source — the device is already connected, so the address is current (no rotated-port guess).
connected_ipport() {
    "$ADB" devices 2>/dev/null | awk '
        $1 ~ /^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]+$/ && $NF == "device" { print $1; exit }
    '
}

# Wireless ip:port via mDNS discovery (for when nothing is connected yet). Best-effort:
# mDNS can be slow/flaky, so this is a fallback rather than the primary path.
mdns_address() {
    timeout 10 "$ADB" mdns services 2>/dev/null | awk '
        /_adb-tls-connect\._tcp/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^([0-9]{1,3}\.){3}[0-9]{1,3}:[0-9]+$/) { print $i; exit }
            }
        }'
}

# Count currently connected devices (last field == "device"; tolerates serials with spaces).
connected_count() {
    "$ADB" devices 2>/dev/null | awk '$NF == "device" { c++ } END { print c + 0 }'
}

# Resolve the target when no explicit address was given. `|| true` so a hung/empty
# mDNS query falls through to the next strategy instead of tripping `set -e`.
USE_CONNECTED_SINGLE=0
if [[ -z "$DEVICE" ]]; then
    DEVICE="$(connected_ipport || true)"
    if [[ -n "$DEVICE" ]]; then
        echo "Using connected device: $DEVICE"
    else
        DEVICE="$(mdns_address || true)"
        if [[ -n "$DEVICE" ]]; then
            echo "Auto-detected device via mDNS: $DEVICE"
        else
            n="$(connected_count || true)"
            if (( n == 1 )); then
                echo "No address resolved; installing to the single connected device."
                USE_CONNECTED_SINGLE=1
            else
                echo "No device address given and auto-detection failed ($n device(s) connected)." >&2
                echo "Specify one, e.g.: ./scripts/install.sh <ip:port>" >&2
                "$ADB" devices >&2
                exit 1
            fi
        fi
    fi
fi

# Optional pairing (only meaningful for an explicit/mDNS address, not the single-device path).
if [[ "${USE_CONNECTED_SINGLE}" == "0" ]]; then
    if [[ -n "$PAIRING_CODE" ]]; then
        echo "Pairing with $DEVICE..."
        "$ADB" pair "$DEVICE" "$PAIRING_CODE" 2>/dev/null || true
    fi
    echo "Connecting to $DEVICE..."
    "$ADB" connect "$DEVICE"
    if ! "$ADB" -s "$DEVICE" shell echo "ok" >/dev/null 2>&1; then
        echo "Failed to connect to $DEVICE"
        exit 1
    fi
    echo "Installing $(basename "$APK") to main profile..."
    "$ADB" -s "$DEVICE" install --user 0 -r "$APK"
else
    if ! "$ADB" shell echo "ok" >/dev/null 2>&1; then
        echo "Connected device not responding"
        exit 1
    fi
    echo "Installing $(basename "$APK") to main profile (single connected device)..."
    "$ADB" install --user 0 -r "$APK"
fi

echo "Done."
