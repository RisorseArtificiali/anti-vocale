#!/usr/bin/env bash
# Install debug APK on device via wireless ADB.
# Installs to main profile only (user 0).
#
# Usage:
#   ./scripts/install.sh                              # auto-detect device
#   ./scripts/install.sh telefonopaolo:40615          # override address
#   ./scripts/install.sh telefonopaolo:40615 794996   # override address + pairing code
#   FLAVOR=fdroid ./scripts/install.sh                # install the fdroid flavor
#   APK_ABI=x86_64 ./scripts/install.sh               # for an x86_64 emulator
#
# When no address is given, the device is auto-detected rather than assumed:
#   1. mDNS discovery (`adb mdns services`) → wireless ip:port
#   2. the single already-connected device (install directly)
# The wireless debugging port rotates on reboot/Wi-Fi changes, so auto-detection
# avoids relying on a hardcoded port that is usually stale.
#
# Config can also be set via environment variables or ~/.config/anti-vocale/device.env
# (DEVICE_ADDRESS / DEVICE_PAIRING_CODE / FLAVOR / APK_ABI). An explicit address
# always wins over auto-detect.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

# Load config from env file if present
ENV_FILE="${DEVICE_ENV_FILE:-$HOME/.config/anti-vocale/device.env}"
if [[ -f "$ENV_FILE" ]]; then
    source "$ENV_FILE"
fi

# Product flavors + ABI splits produce per-flavor, per-ABI APKs:
#   app/build/outputs/apk/<flavor>/debug/app-<flavor>-<abi>-debug.apk
FLAVOR="${FLAVOR:-playStore}"
APK_ABI="${APK_ABI:-arm64-v8a}"
APK="$PROJECT_DIR/app/build/outputs/apk/$FLAVOR/debug/app-$FLAVOR-$APK_ABI-debug.apk"

DEVICE="${1:-${DEVICE_ADDRESS:-}}"
PAIRING_CODE="${2:-${DEVICE_PAIRING_CODE:-}}"

if [[ ! -f "$APK" ]]; then
    echo "APK not found at $APK"
    echo "Run ./gradlew assemble${FLAVOR^}Debug first (or set FLAVOR/APK_ABI)"
    exit 1
fi

APK_AGE_SEC=$(( $(date +%s) - $(date +%s -r "$APK") ))
if (( APK_AGE_SEC > 60 )); then
    AGE_MIN=$(( APK_AGE_SEC / 60 ))
    echo "APK is ${AGE_MIN}m old - rebuild first:"
    echo "  ./gradlew assemble${FLAVOR^}Debug"
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
# probe falls through to the next strategy instead of tripping `set -e`.
# Order: an existing connection always beats discovery (a connected device, in any
# serial form, is current; mDNS records can advertise stale rotated ports).
USE_CONNECTED_SINGLE=0
if [[ -z "$DEVICE" ]]; then
    DEVICE="$(connected_ipport || true)"
    if [[ -n "$DEVICE" ]]; then
        echo "Using connected device: $DEVICE"
    elif [[ "$(connected_count || true)" == "1" ]]; then
        echo "Installing to the single connected device (mDNS-serial form)."
        USE_CONNECTED_SINGLE=1
    else
        DEVICE="$(mdns_address || true)"
        if [[ -n "$DEVICE" ]]; then
            echo "Auto-detected device via mDNS: $DEVICE"
        else
            n="$(connected_count || true)"
            echo "No device address given and auto-detection failed ($n device(s) connected)." >&2
            echo "Specify one, e.g.: ./scripts/install.sh <ip:port>" >&2
            "$ADB" devices >&2
            exit 1
        fi
    fi
fi

# Install + verify (TASK-573): adb can stream for minutes, print its own
# error, and still exit 0, so the exit code alone is not evidence. Require
# "Success" in the output AND confirm the package resolver answers, then
# report the installed versionName. Any miss exits 1.
install_and_verify() {
    local adb_args=("$@")
    local out rc
    set +e
    out="$("$ADB" "${adb_args[@]}" install --user 0 -r "$APK" 2>&1)"
    rc=$?
    set -e
    echo "$out"
    if (( rc != 0 )) || ! grep -q "Success" <<<"$out"; then
        echo "INSTALL FAILED (adb exit $rc; no Success marker)." >&2
        exit 1
    fi
    # Post-install verification against the package manager, not the adb
    # client: the debug build carries the .debug applicationIdSuffix.
    local pkg
    for pkg in com.antivocale.app.debug com.antivocale.app; do
        if "$ADB" "${adb_args[@]}" shell pm path "$pkg" >/dev/null 2>&1; then
            echo "Installed: $pkg ($("$ADB" "${adb_args[@]}" shell dumpsys package "$pkg" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1))"
            echo "Done."
            return 0
        fi
    done
    echo "INSTALL FAILED: Success reported but the package does not resolve." >&2
    exit 1
}

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
    install_and_verify -s "$DEVICE"
else
    if ! "$ADB" shell echo "ok" >/dev/null 2>&1; then
        echo "Connected device not responding"
        exit 1
    fi
    echo "Installing $(basename "$APK") to main profile (single connected device)..."
    install_and_verify
fi
