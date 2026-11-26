#!/usr/bin/env bash
# Helpers for emulator checks. The caller sets $out (where dumps and screenshots go) and $pkg.

fail() {
    echo "::error::$*"
    adb logcat -d > "$out/logcat.txt" || true
    exit 1
}

screenshot() { adb exec-out screencap -p > "$out/$1.png"; }

# The UI as uiautomator sees it; prints the dump file.
dump_ui() {
    adb shell rm -f /sdcard/ui.xml || true
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 || true
    adb exec-out cat /sdcard/ui.xml > "$out/$1.xml" 2>/dev/null || true
    echo "$out/$1.xml"
}

# Centre of the first node whose content-desc or text is $2 in dump $1, as "x y".
centre_of() {
    python3 -I - "$1" "$2" <<'PY'
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(1)
for node in root.iter("node"):
    if sys.argv[2] in (node.get("content-desc"), node.get("text")):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
}

# A system "<app> isn't responding" dialog: fails when it is about this app; for another app it taps
# "Wait" and carries on.
handle_anr_dialog() {
    local title xy
    title=$(python3 -I -c "
import re, sys
m = re.search(r'text=\"([^\"]*) isn.t responding\"', open(sys.argv[1], errors='replace').read())
print(m.group(1) if m else '')" "$1")
    [ -n "$title" ] || return 0
    if [ "$title" = "Pubky Maps" ]; then fail "Pubky Maps is not responding (ANR)"; fi
    echo "dismissing an ANR dialog of $title"
    if xy=$(centre_of "$1" "Wait"); then adb shell input tap $xy; fi
}

# Waits up to $2 seconds for a node labelled $1; prints its centre.
wait_for() {
    local deadline=$((SECONDS + $2)) dump
    while ((SECONDS < deadline)); do
        dump=$(dump_ui "wait")
        handle_anr_dialog "$dump" >&2
        if centre_of "$dump" "$1"; then return 0; fi
        sleep 2
    done
    return 1
}

# Fails when the app crashed or is not the resumed activity.
check_alive() {
    adb logcat -d -b crash > "$out/crash.txt" || true
    if grep -qE "Process: $pkg|>>> $pkg <<<" "$out/crash.txt"; then
        fail "the app crashed ($1); see crash.txt"
    fi
    adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > "$out/resumed.txt" || true
    grep -q "$pkg/" "$out/resumed.txt" || fail "the app is not the resumed activity ($1): $(cat "$out/resumed.txt")"
}

# Saves the log and fails when StrictMode caught our code ($1: the step); scripts/ci/strict_mode.py.
check_strict_mode() {
    adb logcat -d > "$out/logcat.txt" || true
    python3 -I "$(dirname "${BASH_SOURCE[0]}")/strict_mode.py" "$out/logcat.txt" || fail "StrictMode caught our code ($1)"
}
