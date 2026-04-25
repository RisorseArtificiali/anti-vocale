#!/usr/bin/env bash
# Install debug APK on device via wireless ADB.
# Installs to main profile only (user 0).
#
# Usage:
#   ./scripts/install.sh                              # uses defaults
#   ./scripts/install.sh telefonopaolo:40615          # override address
#   ./scripts/install.sh telefonopaolo:40615 794996   # override address + pairing code
#
# Config can also be set via environment variables or ~/.config/anti-vocale/device.env

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

DEVICE="${1:-${DEVICE_ADDRESS:-telefonopaolo:40615}}"
PAIRING_CODE="${2:-${DEVICE_PAIRING_CODE:-}}"

if [[ ! -f "$APK" ]]; then
    echo "APK not found at $APK"
    echo "Run ./gradlew assembleDebug first"
    exit 1
fi

# Pair if code provided (optional — often already paired)
if [[ -n "$PAIRING_CODE" ]]; then
    echo "Pairing with $DEVICE..."
    "$ADB" pair "$DEVICE" "$PAIRING_CODE" 2>/dev/null || true
fi

# Connect
echo "Connecting to $DEVICE..."
"$ADB" connect "$DEVICE"

# Verify connection
if ! "$ADB" -s "$DEVICE" shell echo "ok" >/dev/null 2>&1; then
    echo "Failed to connect to $DEVICE"
    exit 1
fi

# Install to main profile only (user 0)
echo "Installing $(basename "$APK") to main profile..."
"$ADB" -s "$DEVICE" install --user 0 -r "$APK"

echo "Done."
