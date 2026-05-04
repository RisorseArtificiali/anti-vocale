#!/usr/bin/env bash
# capture-screenshots.sh — Semi-automated Play Store screenshot capture
#
# Guides you through capturing 10 screenshots for the Anti-Vocale
# Play Store listing in English and/or Italian locales.
#
# Usage:
#   ./scripts/capture-screenshots.sh          # capture both en and it
#   ./scripts/capture-screenshots.sh en       # English only
#   ./scripts/capture-screenshots.sh it       # Italian only
#   ./scripts/capture-screenshots.sh both     # same as no argument
#
# Prerequisites:
#   - Device connected via wireless debugging (or USB)
#   - App installed with sample transcription data
#   - For notification_result: a recent transcription to trigger

set -euo pipefail

# ── Paths ────────────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RAW_DIR="$PROJECT_DIR/docs/screenshots/raw"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"

# ── App identifiers ──────────────────────────────────────────────────────────

PACKAGE="com.antivocale.app"
ACTIVITY="com.antivocale.app/.MainActivity"

# ── Colors ───────────────────────────────────────────────────────────────────

if [[ -t 1 ]]; then
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    RED='\033[0;31m'
    CYAN='\033[0;36m'
    BOLD='\033[1m'
    RESET='\033[0m'
else
    GREEN='' YELLOW='' RED='' CYAN='' BOLD='' RESET=''
fi

# ── State ────────────────────────────────────────────────────────────────────

CAPTURED_FILES=()
SKIPPED_FILES=()
CURRENT_LOCALE=""
TEMP_REMOTE="/sdcard/anti-vocale-screenshot.png"

# ── Cleanup on exit ──────────────────────────────────────────────────────────

cleanup() {
    # Remove temp screenshot from device if it exists
    "$ADB" shell rm -f "$TEMP_REMOTE" 2>/dev/null || true
}
trap cleanup EXIT

# ── Helpers ──────────────────────────────────────────────────────────────────

info()    { printf "${CYAN}[INFO]${RESET}  %s\n" "$*"; }
success() { printf "${GREEN}[OK]${RESET}    %s\n" "$*"; }
warn()    { printf "${YELLOW}[WAIT]${RESET}  %s\n" "$*"; }
error()   { printf "${RED}[ERROR]${RESET} %s\n" "$*"; }
header()  { printf "\n${BOLD}${GREEN}══ %s ══${RESET}\n" "$*"; }

# ── Core functions ───────────────────────────────────────────────────────────

check_adb() {
    if ! command -v "$ADB" &>/dev/null; then
        error "adb not found at $ADB"
        error "Set ADB environment variable or install Android SDK platform-tools"
        exit 1
    fi

    # Handle multiple connected devices — pick the first one
    local device_output
    device_output=$("$ADB" devices 2>/dev/null)
    local device_count
    device_count=$(echo "$device_output" | grep -c "device$" || true)
    if [[ "$device_count" -eq 0 ]]; then
        error "No device connected via adb"
        error "Connect your device and try again"
        exit 1
    elif [[ "$device_count" -gt 1 ]]; then
        local serial
        serial=$(echo "$device_output" | grep "device$" | head -1 | awk '{print $1}')
        warn "Multiple devices detected — using: $serial"
        ADB="$ADB -s $serial"
    fi

    # Verify device connection
    if ! "$ADB" shell echo "ok" >/dev/null 2>&1; then
        error "Cannot communicate with device"
        error "Connect your device and try again"
        exit 1
    fi
    success "Device connected"
}

set_app_language() {
    local locale="$1"
    info "Setting app language to: $locale"

    # Use per-app locale override (Android 14+)
    "$ADB" shell cmd locale set-app-locales "$PACKAGE" --locales "$locale" 2>/dev/null && \
        success "Language set via per-app locale API" && return

    # Fallback: broadcast intent that the app handles internally
    "$ADB" shell am broadcast \
        -a "$PACKAGE.SET_LANGUAGE" \
        --es language "$locale" 2>/dev/null || true
    warn "Per-app locale may not be supported — check app UI language manually"
}

reset_app_language() {
    info "Resetting app language to system default"
    "$ADB" shell cmd locale set-app-locales "$PACKAGE" --locales "" 2>/dev/null || true
}

launch_app() {
    local extra_name="$1"
    local extra_value="$2"

    info "Restarting app..."
    "$ADB" shell am force-stop "$PACKAGE"
    sleep 0.5

    if [[ -n "$extra_name" ]]; then
        "$ADB" shell am start -n "$ACTIVITY" --ez "$extra_name" "$extra_value"
    else
        "$ADB" shell am start -n "$ACTIVITY"
    fi

    info "Waiting for UI to render..."
    sleep 2
}

capture_screenshot() {
    local name="$1"
    local out_dir="$RAW_DIR/$CURRENT_LOCALE"
    local out_file="$out_dir/${name}.png"

    mkdir -p "$out_dir"

    "$ADB" shell screencap -p "$TEMP_REMOTE"
    "$ADB" pull "$TEMP_REMOTE" "$out_file"
    "$ADB" shell rm -f "$TEMP_REMOTE"

    if [[ -f "$out_file" ]]; then
        local size
        size=$(du -h "$out_file" | cut -f1)
        local dimensions
        dimensions=$("$ADB" shell dumpsys window displays 2>/dev/null | grep -oP 'init=\K[0-9]+x[0-9]+' | head -1 || echo "unknown")
        success "Saved: docs/screenshots/raw/$CURRENT_LOCALE/${name}.png ($size)"
        CAPTURED_FILES+=("docs/screenshots/raw/$CURRENT_LOCALE/${name}.png")
    else
        error "Failed to capture: ${name}"
        return 1
    fi
}

prompt_user() {
    local message="$1"
    local instruction="$2"

    echo ""
    warn "$message"
    if [[ -n "$instruction" ]]; then
        printf "         ${BOLD}Action:${RESET} %s\n" "$instruction"
    fi
    printf "         Press ${BOLD}Enter${RESET} to capture, ${BOLD}s${RESET} to skip, ${BOLD}q${RESET} to quit: "

    local response
    read -r response

    case "$response" in
        q|Q)
            info "Quitting..."
            print_summary
            exit 0
            ;;
        s|S)
            warn "Skipped"
            SKIPPED_FILES+=("$CURRENT_LOCALE/${1:-unknown}")
            return 1
            ;;
        *)
            return 0
            ;;
    esac
}

# ── Screenshot definitions ───────────────────────────────────────────────────

# Each entry: name|launch_extra_name|launch_extra_value|pre_instruction|manual_instruction
SCREENSHOTS=(
    "logs_populated|||The app will open on the Logs tab.|Ensure there are populated transcription entries visible. Add some if needed."
    "logs_search|||Stay on the Logs tab.|Tap the search bar and type a query to show filtered results."
    "logs_expanded|||Stay on the Logs tab.|Expand one log entry to show the Copy/Share/Retry action buttons."
    "model_overview|navigate_to_model_tab|true|The app will open on the Model tab.|Scroll to show the key model cards (Parakeet recommended, Whisper)."
    "model_gemma|||Stay on the Model tab.|Scroll down to show the Gemma models section."
    "settings_transcription|||Navigate to the Settings tab (rightmost tab).|No additional action needed — capture the Transcription section at top."
    "settings_appearance|||Stay on the Settings tab.|Scroll down to show the Appearance section."
    "settings_advanced|||Stay on the Settings tab.|Expand the Advanced section to show its contents."
    "retranscribe_dialog|||Go to the Logs tab, expand an entry.|Tap the Retry button to open the Re-transcribe dialog."
    "notification_result|||This requires a recent transcription.|Trigger a transcription, wait for completion, then open the notification shade."
)

capture_all_for_locale() {
    local locale="$1"
    CURRENT_LOCALE="$locale"

    local locale_label
    case "$locale" in
        en) locale_label="English" ;;
        it) locale_label="Italian" ;;
        *)  locale_label="$locale" ;;
    esac

    header "Capturing screenshots for locale: $locale ($locale_label)"

    info "Target directory: $RAW_DIR/$locale/"
    mkdir -p "$RAW_DIR/$locale"

    # Set language and restart app
    set_app_language "$locale"

    local count=0
    local total=${#SCREENSHOTS[@]}

    for entry in "${SCREENSHOTS[@]}"; do
        count=$((count + 1))

        # Parse the pipe-delimited entry
        IFS='|' read -r name extra_name extra_value pre_instr manual_instr <<< "$entry"

        echo ""
        printf "${BOLD}[%d/%d] %s${RESET}\n" "$count" "$total" "$name"
        printf "  ${CYAN}Preparation:${RESET} %s\n" "$pre_instr"
        printf "  ${CYAN}Your action:${RESET} %s\n" "$manual_instr"

        # Auto-launch if the screenshot requires a specific tab
        if [[ -n "$extra_name" ]]; then
            launch_app "$extra_name" "$extra_value"
        elif [[ "$count" -eq 1 ]]; then
            # First screenshot always needs a fresh launch
            launch_app "" ""
        fi

        if prompt_user "Ready to capture: ${name}" ""; then
            capture_screenshot "$name"
        fi
    done
}

# ── Summary ──────────────────────────────────────────────────────────────────

print_summary() {
    header "Capture Summary"

    if [[ ${#CAPTURED_FILES[@]} -gt 0 ]]; then
        printf "\n${GREEN}Captured (%d):${RESET}\n" "${#CAPTURED_FILES[@]}"
        for f in "${CAPTURED_FILES[@]}"; do
            local size
            size=$(du -h "$PROJECT_DIR/$f" 2>/dev/null | cut -f1 || echo "?")
            printf "  ${GREEN}*${RESET} %s  (%s)\n" "$f" "$size"
        done
    fi

    if [[ ${#SKIPPED_FILES[@]} -gt 0 ]]; then
        printf "\n${YELLOW}Skipped (%d):${RESET}\n" "${#SKIPPED_FILES[@]}"
        for f in "${SKIPPED_FILES[@]}"; do
            printf "  ${YELLOW}-${RESET} %s\n" "$f"
        done
    fi

    echo ""
    if [[ ${#CAPTURED_FILES[@]} -gt 0 ]]; then
        info "Raw screenshots are in: $RAW_DIR/"
        info "Next step: crop and resize with process_screenshots.sh or process_screenshots.py"
    fi
}

# ── Main ─────────────────────────────────────────────────────────────────────

main() {
    local mode="${1:-both}"

    case "$mode" in
        en|it|both)
            ;;
        *)
            error "Unknown argument: $mode"
            echo "Usage: $0 [en|it|both]"
            exit 1
            ;;
    esac

    echo ""
    printf "${BOLD}${CYAN}Anti-Vocale — Play Store Screenshot Capture${RESET}\n"
    echo ""

    check_adb

    if [[ "$mode" == "both" || "$mode" == "en" ]]; then
        capture_all_for_locale "en"
    fi

    if [[ "$mode" == "both" || "$mode" == "it" ]]; then
        if [[ "$mode" == "both" ]]; then
            echo ""
            info "Switching to Italian locale..."
        fi
        capture_all_for_locale "it"
    fi

    # Reset language to system default
    reset_app_language

    print_summary
}

main "$@"
