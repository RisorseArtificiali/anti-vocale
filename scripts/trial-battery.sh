#!/usr/bin/env bash
# One-shot device trial battery for the next unlock window (av-mac).
# Usage: bash scripts/trial-battery.sh
# Assumes: phone connected + UNLOCKED + the current debug build installed.
# Every trial prints its evidence inline; nothing here modifies the repo.
set -uo pipefail

ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PKG=com.antivocale.app.debug
D=$("$ADB" devices | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p' | head -1)
[ -z "$D" ] && { echo "NO DEVICE"; exit 1; }
A() { "$ADB" -s "$D" shell "$@"; }

echo "== device: $D"
A dumpsys power | grep -m1 mWakefulness=
A dumpsys window | grep -m1 -i keyguardShowing

# Keep the display on for the battery; restore at exit.
ORIG=$(A settings get system screen_off_timeout)
A settings put system screen_off_timeout 1800000
trap 'A settings put system screen_off_timeout 30000' EXIT

wake() {
  A input keyevent KEYCODE_WAKEUP >/dev/null 2>&1
  A wm dismiss-keyguard >/dev/null 2>&1
  sleep 1
  A am start -n $PKG/com.antivocale.app.MainActivity >/dev/null 2>&1
  sleep 3
}

db_row() {  # db_row <taskId> -> status|result prefix
  for f in anti_vocale_database anti_vocale_database-wal anti_vocale_database-shm; do
    "$ADB" -s "$D" exec-out run-as $PKG cat databases/$f > /tmp/tb_$f 2>/dev/null
  done
  python3 - "$1" <<'EOF'
import sqlite3, sys
con = sqlite3.connect('/tmp/tb_anti_vocale_database')
row = con.execute("SELECT status, substr(coalesce(result,''),1,140), coalesce(errorMessage,'') FROM logs WHERE taskId=?", (sys.argv[1],)).fetchone()
print(f"{row[0] if row else 'NONE'} | {row[1] if row else ''} | {row[2] if row else ''}")
EOF
}

request() {  # request <taskId> [backend_id]
  wake
  local extra=""
  [ $# -ge 2 ] && extra="--es backend_id $2"
  A am broadcast -a com.antivocale.app.PROCESS_REQUEST \
    -n $PKG/com.antivocale.app.receiver.TaskerRequestReceiver \
    --es request_type audio \
    --es file_path /data/user/0/$PKG/files/shared_audio/viv.wav \
    --es task_id "$1" $extra >/dev/null 2>&1
}

wait_row() {  # wait_row <taskId> <seconds>
  for _ in $(seq 1 "$2"); do
    sleep 10
    R=$(db_row "$1")
    case "$R" in SUCCESS*|ERROR*|NONE*) ;; esac
    case "$R" in SUCCESS*|ERROR*) echo "  [$1] $R"; return 0;; esac
  done
  echo "  [$1] TIMEOUT: $R"
}

# ---- TASK-674: LLM post-pass timing (Gemma budget calibration) ----
echo "== 674: enabling punctuation (Gemma post-pass) and timing one decode"
A am broadcast -n $PKG/com.antivocale.app.receiver.TestSpiReceiver \
  -a com.antivocale.app.TEST_SPI --es op set --es key punctuation --es value always >/dev/null 2>&1
T0=$(date +%s)
request tb674 external:0f87a13aa5454a039d70489968757144
wait_row tb674 36
T1=$(date +%s)
echo "  674 wall: $((T1-T0))s (LLM post-pass budget calibration input)"
A am broadcast -n $PKG/com.antivocale.app.receiver.TestSpiReceiver \
  -a com.antivocale.app.TEST_SPI --es op set --es key punctuation --es value off >/dev/null 2>&1

# ---- TASK-605: landscape-with-cutout observation (manual: the reviewer
# reads the screen; adb cannot certify a visual cutout) ----
echo "== 605: rotate to landscape for the cutout observation (read the screen)"
# wm rotation is unsupported on Android 16: force via the rotation lock
A settings put system accelerometer_rotation 0 2>/dev/null
A settings put system user_rotation 1 2>/dev/null
sleep 3
A uiautomator dump /sdcard/tb605.xml >/dev/null 2>&1
"$ADB" -s "$D" exec-out cat /sdcard/tb605.xml > /tmp/tb605.xml 2>/dev/null
grep -c "android.widget" /tmp/tb605.xml 2>/dev/null && echo "  (dump at /tmp/tb605.xml; screencap next)"
A screencap -p /sdcard/tb605.png 2>/dev/null
"$ADB" -s "$D" exec-out cat /sdcard/tb605.png > /tmp/tb605.png 2>/dev/null
A settings put system user_rotation 0 2>/dev/null
echo "  (screenshot at /tmp/tb605.png; OCR before describing)"

echo "== battery done; screen_off_timeout restored on exit"
