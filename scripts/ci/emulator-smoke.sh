#!/usr/bin/env bash
# Smoke test on a running emulator: install the APK, start the app, wait until the map has drawn,
# open Settings and each settings page, switch "Follow local rules" off and check it stays off
# after a restart, then go back with the system back button. Fails on a crash or when the app is
# not in front. Screenshots, the UI dumps and the log go to <out dir>.
# Usage: emulator-smoke.sh <apk> <out dir>
set -euo pipefail

apk=$1
out=$2
pkg=to.pubky.maps
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"

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

# A system "<app> isn't responding" dialog: fails when it is about this app; for another app (the
# emulator's launcher or System UI often stall right after boot) it taps "Wait" and carries on.
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

# "true" or "false": the checked state of the switch row holding the text $2 in dump $1.
checked_of() {
    python3 -I - "$1" "$2" <<'PY'
import sys, xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
parents = {child: parent for parent in root.iter() for child in parent}
for node in root.iter("node"):
    if node.get("text") == sys.argv[2]:
        while node is not None and node.get("checkable") != "true":
            node = parents.get(node)
        if node is not None:
            print(node.get("checked"))
            sys.exit(0)
sys.exit(1)
PY
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

check_alive() {
    adb logcat -d -b crash > "$out/crash.txt" || true
    # A Java crash names the process; a native one (MapLibre) shows it in the tombstone header.
    if grep -qE "Process: $pkg|>>> $pkg <<<" "$out/crash.txt"; then
        fail "the app crashed ($1); see crash.txt"
    fi
    adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > "$out/resumed.txt" || true
    grep -q "$pkg/" "$out/resumed.txt" || fail "the app is not the resumed activity ($1): $(cat "$out/resumed.txt")"
}

adb install -r "$apk"
adb logcat -c || true
adb logcat -b crash -c || true
started=$(adb shell am start -W -n "$pkg/.android.MainActivity" 2>&1) || true
echo "$started"
if grep -q "Error" <<< "$started"; then fail "the app did not start: $started"; fi

wait_for "Show my location" 60 > /dev/null || fail "the map screen did not appear"
check_alive "map screen"

# The map has drawn once the middle of the screen holds many colours and no single colour covers
# almost all of it (land, water and labels, not one flat background). Tiles come over the network,
# so give it time.
drawn=false
for _ in $(seq 1 30); do
    handle_anr_dialog "$(dump_ui map-wait)"
    screenshot map || { sleep 3; continue; }
    stats=$(python3 -I "$here/png_stats.py" "$out/map.png" 0.1 0.2 0.9 0.8) || stats="0 1.000"
    read -r colours dominant <<< "$stats"
    echo "map: $colours colours, most common $dominant"
    if ((colours >= 40)) && python3 -I -c "import sys; sys.exit(float(sys.argv[1]) >= 0.9)" "$dominant"; then
        drawn=true
        break
    fi
    sleep 3
done
check_alive "map drawn"
# The could-not-load notice means every style failed, even if the screen looks busy.
if centre_of "$(dump_ui map)" "Retry" > /dev/null; then fail "the map could not load any style (Retry notice shown)"; fi
$drawn || fail "the map did not draw ($colours colours, most common $dominant in the middle of the screen)"

# Let the remaining tiles and labels arrive: keep screenshotting until two in a row are identical.
for _ in $(seq 1 10); do
    sleep 3
    screenshot map-next || continue
    if cmp -s "$out/map.png" "$out/map-next.png"; then break; fi
    mv "$out/map-next.png" "$out/map.png"
done
rm -f "$out/map-next.png"

open_settings() {
    local xy
    xy=$(wait_for "Settings" 10) || fail "no Settings button"
    adb shell input tap $xy
    wait_for "What is stored online" 15 > /dev/null || fail "Settings did not open"
}

# Taps the row labelled $1, waits for $2 on the page it opens, screenshots it as $3, goes back and
# waits for $4 on the page it came from.
visit() {
    local xy
    xy=$(wait_for "$1" 10) || fail "no \"$1\" row"
    adb shell input tap $xy
    wait_for "$2" 15 > /dev/null || fail "\"$1\" did not open (no \"$2\")"
    screenshot "$3"
    check_alive "$3"
    adb shell input keyevent KEYCODE_BACK
    wait_for "$4" 15 > /dev/null || fail "back from \"$1\" did not return (no \"$4\")"
}

open_settings
screenshot settings
check_alive "settings"

# Every settings page: Server lists and one list, What is stored online, Open in other apps.
xy=$(wait_for "Server lists" 10) || fail "no Server lists row"
adb shell input tap $xy
wait_for "Map style, light" 15 > /dev/null || fail "Server lists did not open"
screenshot servers
check_alive "servers"
visit "Map style, light" "Add server" server-list "Map style, dark"
adb shell input keyevent KEYCODE_BACK
wait_for "What is stored online" 15 > /dev/null || fail "back from Server lists did not return to Settings"
visit "What is stored online" "Saved places" stored-online "Open in other apps"
visit "Open in other apps" "Organic Maps" open-in "What is stored online"

# A setting survives a restart: switch "Follow local rules" off, restart the app, look again, and
# switch it back on.
xy=$(wait_for "Follow local rules" 10) || fail "no Follow local rules switch"
[ "$(checked_of "$(dump_ui rules-before)" "Follow local rules")" = "true" ] || fail "Follow local rules is not on by default"
adb shell input tap $xy
sleep 2
[ "$(checked_of "$(dump_ui rules-off)" "Follow local rules")" = "false" ] || fail "Follow local rules did not switch off"
adb shell am force-stop "$pkg"
started=$(adb shell am start -W -n "$pkg/.android.MainActivity" 2>&1) || true
if grep -q "Error" <<< "$started"; then fail "the app did not start again: $started"; fi
wait_for "Show my location" 60 > /dev/null || fail "the map screen did not appear after the restart"
open_settings
xy=$(wait_for "Follow local rules" 10) || fail "no Follow local rules switch after the restart"
[ "$(checked_of "$(dump_ui rules-after)" "Follow local rules")" = "false" ] || fail "Follow local rules was not kept across the restart"
adb shell input tap $xy
check_alive "settings after the restart"

adb shell input keyevent KEYCODE_BACK
wait_for "Show my location" 15 > /dev/null || fail "back from Settings did not return to the map"
sleep 3
screenshot map-after-back
check_alive "back to the map"

adb logcat -d > "$out/logcat.txt"
echo "smoke test passed"
