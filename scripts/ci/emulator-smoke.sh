#!/usr/bin/env bash
# Smoke test on a running emulator: install the APK, start the app, wait until the map has drawn
# and hold the start time to a budget, open Settings and each settings page, switch "Follow local
# rules" off and check it stays off after a restart (a second start held to a budget), open the
# open-source licences and swipe to their end, then go back with the system back button; move the
# map with a link and check that the next start opens it there, then pan it and switch the theme or
# press Back at once and check that the panned view is stored. Fails on a crash or when the app is
# not in front.
# Screenshots, the UI dumps, the start times and the log go to <out dir>.
# Usage: emulator-smoke.sh <apk> <out dir>
set -euo pipefail

apk=$1
out=$2
pkg=to.pubky.maps
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
# The theme-switch step below leaves the emulator in the light theme the next tests expect.
trap 'adb shell cmd uimode night no > /dev/null 2>&1 || true' EXIT

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

check_no_crash() {
    adb logcat -d -b crash > "$out/crash.txt" || true
    # A Java crash names the process; a native one (MapLibre) shows it in the tombstone header.
    if grep -qE "Process: $pkg|>>> $pkg <<<" "$out/crash.txt"; then
        fail "the app crashed ($1); see crash.txt"
    fi
}

check_alive() {
    check_no_crash "$1"
    adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > "$out/resumed.txt" || true
    grep -q "$pkg/" "$out/resumed.txt" || fail "the app is not the resumed activity ($1): $(cat "$out/resumed.txt")"
}

# How long a start may take until the map has drawn its first whole view (Android's "Fully drawn",
# see check_start_time). The budgets are for this CI emulator (software GPU, shared runner), which is
# several times slower than a phone; the plan's target is under 1 s on a mid-range phone. On it the
# first start, which fetches the style and tiles over the network, took 10-18 s, and the restart,
# which has them in MapLibre's cache, 5-8 s. Runners differ up to about 2x, so each budget is over
# twice the slowest run: it fails a start that stalls for more than about 27 s (first) or 12 s
# (restart), or got several times slower, not a slow runner or a single short network wait.
first_start_budget_ms=45000
restart_budget_ms=20000

# Prints the last start's times from Android's "Displayed" and "Fully drawn" lines (start_time.py) and
# fails when its map took longer than $2 ms; waits up to a minute for the map. $1 names the start.
check_start_time() {
    local status=3
    for _ in $(seq 1 30); do
        adb logcat -d | grep -E "Displayed |Fully drawn " > "$out/start-$1.txt" || true
        status=0
        python3 -I "$here/start_time.py" "$out/start-$1.txt" "$pkg" "$1" "$2" || status=$?
        [ "$status" = 3 ] || break
        sleep 2
    done
    [ "$status" = 3 ] && fail "no \"Displayed\" and \"Fully drawn\" lines of the $1 start of $pkg after a minute; see start-$1.txt"
    [ "$status" = 0 ] || fail "the map of the $1 start took longer than its budget; see start-$1.txt"
}

adb install -r "$apk"
# A log buffer large enough to keep StrictMode's reports and the start's lines until the end.
adb logcat -G 16M || true
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
check_start_time first "$first_start_budget_ms"

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

# Swipes the page up until a node labelled $1 is on screen and clear of the bottom edge (the gesture
# bar); prints its centre.
scroll_to() {
    local xy
    for _ in $(seq 1 8); do
        if xy=$(centre_of "$(dump_ui scroll)" "$1") && [ "${xy#* }" -lt 1900 ]; then
            echo "$xy"
            return 0
        fi
        adb shell input swipe 540 1800 540 1000 300
        sleep 1
    done
    return 1
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
# One server list, and its Add server page.
xy=$(wait_for "Map style, light" 10) || fail "no Map style, light row"
adb shell input tap $xy
xy=$(wait_for "Add server" 15) || fail "the Map style, light list did not open"
screenshot server-list
check_alive "server list"
adb shell input tap $xy
wait_for "Added at the end of Map style, light. Move it up to use it first." 15 > /dev/null || fail "the Add server page did not open"
screenshot add-server
check_alive "add server page"
adb shell input keyevent KEYCODE_BACK
wait_for "OpenFreeMap Liberty" 15 > /dev/null || fail "back from Add server did not return to the list"
adb shell input keyevent KEYCODE_BACK
wait_for "Map style, dark" 15 > /dev/null || fail "back from the server list did not return to Server lists"
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
# Before Settings: the map leaves the screen there and would not report.
check_start_time restart "$restart_budget_ms"
open_settings
xy=$(wait_for "Follow local rules" 10) || fail "no Follow local rules switch after the restart"
[ "$(checked_of "$(dump_ui rules-after)" "Follow local rules")" = "false" ] || fail "Follow local rules was not kept across the restart"
adb shell input tap $xy
check_alive "settings after the restart"

# The open-source licences: the Gradle libraries and the Rust crates of the core in one list. Swipe
# through all of it: every item is composed once, so a broken entry crashes here.
xy=$(scroll_to "Open-source licences") || fail "no Open-source licences row"
adb shell input tap $xy
wait_for "aead" 20 > /dev/null || fail "the licence list has no Rust crates (no aead)"
screenshot licences
check_alive "licences"
for _ in $(seq 1 90); do adb shell input swipe 540 2000 540 400 150; done
sleep 2
check_alive "licences, scrolled to the end"
wait_for "zeroize" 10 > /dev/null || fail "the end of the licence list did not show (no zeroize)"
screenshot licences-end
adb shell input keyevent KEYCODE_BACK
wait_for "Open-source licences" 15 > /dev/null || fail "back from the licences did not return to Settings"

adb shell input keyevent KEYCODE_BACK
wait_for "Show my location" 15 > /dev/null || fail "back from Settings did not return to the map"
sleep 3
screenshot map-after-back
check_alive "back to the map"

# The map opens where the user left it: a map link moves it to Zürich, the camera rests there and
# the app stores the view; after a restart the stored view is still Zürich (a map that opened at
# the whole world would store that once its camera rested). The file is read through run-as, so
# only on a debug APK; release.yml's run on the published release APK skips this part.
# expect_view NAME [LAT_FROM LAT_TO]: the stored view is at Zürich's longitude and zoom 14, with
# its latitude in the range (Zürich's 47.37 by default); a copy of the file is kept as NAME.
expect_view() {
    adb shell run-as "$pkg" cat no_backup/map-view.json > "$out/map-view-$1.json" 2> /dev/null || true
    python3 -I -c "
import json, sys
try:
    view = json.load(open(sys.argv[1], encoding='utf-8')).get('view') or {}
except ValueError:
    view = {}
lat_from, lat_to = float(sys.argv[3]), float(sys.argv[4])
ok = lat_from <= view.get('lat', 0) <= lat_to and abs(view.get('lon', 0) - 8.54) < 0.01 and abs(view.get('zoom', 0) - 14) < 0.5
print(sys.argv[2] + ':', view)
sys.exit(0 if ok else 1)" "$out/map-view-$1.json" "$1" "${2:-47.36}" "${3:-47.38}"
}
# The latitude of the view kept as map-view-NAME.json.
view_lat() {
    python3 -I -c "import json, sys; print(json.load(open(sys.argv[1], encoding='utf-8'))['view']['lat'])" "$out/map-view-$1.json"
}
# The latitude range of a pan's view: 0.005-0.1 degrees south of LAT (a pan that flings goes further).
south_of() { python3 -I -c "import sys; lat = float(sys.argv[1]); print(lat - 0.1, lat - 0.005)" "$1"; }
# "MS EPOCH": milliseconds from the log line "lift NAME" to the next destroy of MainActivity, and
# the destroy's time in seconds since 1970; nothing before that destroy.
lift_to_destroy() {
    adb logcat -d -v epoch -b main -b events > "$out/logcat-$1.txt" || true
    awk -v tag="lift $1" 'index($0, "PubkyMapsSmoke") && index($0, tag) { up = $1 }
        up && /wm_on_destroy_called.*MainActivity/ { printf "%d %.3f\n", ($1 - up) * 1000, $1; exit }' "$out/logcat-$1.txt"
}
# The stored view's write time as stat prints it, or nothing.
written_at() { adb shell run-as "$pkg" stat -c %y no_backup/map-view.json 2> /dev/null || true; }
# The time of the first write of the stored view after the one at BEFORE, waiting up to SECONDS.
first_write_after() {
    local deadline=$((SECONDS + $2)) at
    while ((SECONDS < deadline)); do
        at=$(written_at)
        if [ -n "$at" ] && [ "$at" != "$1" ]; then echo "$at"; return; fi
    done
}
# Milliseconds from EPOCH (seconds since 1970) to the write time AT (as stat prints it).
ms_after() {
    python3 -I -c "
import datetime, sys
day, time, zone = sys.argv[1].split()
whole, _, frac = time.partition('.')
at = datetime.datetime.strptime(day + ' ' + whole + ' ' + zone, '%Y-%m-%d %H:%M:%S %z').timestamp()
print(round((at + float('0.' + (frac or '0')) - float(sys.argv[2])) * 1000))" "$1" "$2"
}
check_reopened_view() {
    started=$(adb shell am start -W -a android.intent.action.VIEW -d "'geo:47.37,8.54?z=14'" -p "$pkg" 2>&1) || true
    if grep -q "Error" <<< "$started"; then fail "the map link did not reach the app: $started"; fi
    stored=false
    for _ in $(seq 1 10); do
        sleep 2
        if expect_view link; then stored=true; break; fi
    done
    $stored || fail "the map's view at Zürich was not stored; see map-view-link.json"
    screenshot map-link
    adb shell am force-stop "$pkg"
    started=$(adb shell am start -W -n "$pkg/.android.MainActivity" 2>&1) || true
    if grep -q "Error" <<< "$started"; then fail "the app did not start a third time: $started"; fi
    wait_for "Show my location" 60 > /dev/null || fail "the map screen did not appear after the third start"
    # Longer than the camera's rest before a view is stored (VIEW_REST_MS, 1 s) once the map is up.
    sleep 8
    screenshot map-reopened
    check_alive "map reopened"
    expect_view reopened || fail "the map did not open where it was left (stored view changed); see map-view-reopened.json"
    # A pan, then the map leaves the screen at once, before its camera has rested for 1 s: it stores
    # the last view it showed. Each pan moves the finger up by 1,200 px (about 460 dp, about 0.013
    # degrees of latitude at zoom 14), so the map shows a view further south; the finger rests before
    # it lifts (each input call takes a few hundred ms), so the map mostly does not fling on; when
    # it does, south_of allows up to 0.1 degrees (about 7 pans). A first pan must be stored after its
    # rest: it shows that gestures reach the camera, and gives the software-drawn map time to draw
    # (it can stall for 8 s after a start, and a pan in a stall may not reach the camera before it
    # leaves). That pan flings too when it lands in the stall (2 and 4 pans' worth in runs
    # 45879166390 and 45879110548, each with an 8 s frame at the lift), so its range is south_of's.
    # The "lift" line is logged just before the finger lifts, so the camera's rest starts after it.
    pan_up() {
        adb shell "input motionevent DOWN 540 1800; input motionevent MOVE 540 1400; input motionevent MOVE 540 1000; input motionevent MOVE 540 600"
        adb shell "log -t PubkyMapsSmoke 'lift $1'; input motionevent UP 540 600; $2"
    }
    pan_up first ""
    range=$(south_of "$(view_lat reopened)")
    stored=false
    for _ in $(seq 1 15); do
        sleep 2
        if expect_view rested $range; then stored=true; break; fi
    done
    $stored || fail "the view after a pan was not stored once the camera rested; see map-view-rested.json"
    last=$(view_lat rested)
    sleep 5
    # A theme switch right after a pan: Android recreates MainActivity. The new map opens at the
    # panned camera (the map keeps it in its saved state) and would store it after its own 1 s rest,
    # so the view alone does not show the save on leaving; the file's write time does. A try shows
    # it when the old activity was destroyed less than 1 s after the lift (its rest cannot have
    # ended) and the view was first written less than 1 s after that destroy (the new map's rest
    # ends later). The switch waits 250 ms after the lift for the map to report its camera still: a
    # map that leaves while its camera moves stores nothing on leaving (on CI about half the tries
    # without the wait), and the new map's rest then stores the view. A try that does not show it
    # is tried again; each pans further south.
    proved=false
    for try in 1 2 3 4 5; do
        if ((try % 2)); then night=yes; else night=no; fi
        before=$(written_at)
        pan_up "switch-$try" "sleep 0.25; cmd uimode night $night"
        # Polled at once: the new map may write the view again after its rest (its camera comes back
        # from the saved state a few bits apart), so only the first write tells when it was stored.
        written=$(first_write_after "$before" 6 || true)
        destroy=""
        for _ in $(seq 1 15); do
            sleep 1
            destroy=$(lift_to_destroy "switch-$try")
            [ -z "$destroy" ] || break
        done
        [ -n "$destroy" ] || fail "MainActivity was not recreated after a theme switch"
        wait_for "Show my location" 60 > /dev/null || fail "the map did not come back after a theme switch"
        check_alive "theme switch right after a pan"
        # The new map draws before the next pan (and has rested long since).
        sleep 8
        read -r lift_ms destroyed_at <<< "$destroy"
        write_ms=""
        if [ -n "$written" ]; then write_ms=$(ms_after "$written" "$destroyed_at"); fi
        echo "theme switch $try: lift to destroy $lift_ms ms, destroy to the view's first write ${write_ms:-unknown} ms"
        range=$(south_of "$last")
        moved=false
        if expect_view "switched-$try" $range; then moved=true; fi
        last=$(view_lat "switched-$try" 2> /dev/null || echo "$last")
        if $moved && ((lift_ms < 1000 && ${write_ms:-1000} < 1000)); then proved=true; break; fi
    done
    $proved || fail "no theme switch right after a pan first wrote the view within 1 s of the destroy in 5 tries; see map-view-switched-*.json"
    if [ "$night" = yes ]; then
        adb shell cmd uimode night no
        sleep 3
        wait_for "Show my location" 60 > /dev/null || fail "the map did not come back after the theme switched back"
        sleep 8
    fi
    # Back right after a pan, the case the user meets. This start has no launcher intent, so Back
    # finishes the activity (from the launcher, Android 12+ only moves the task back and the rest
    # still runs). Back destroys the activity 0.9-1.5 s after the lift on CI, so a rest can end
    # first: this checks that Back stores the view, the theme switch above that the save on leaving
    # does it.
    pan_up back "input keyevent KEYCODE_BACK"
    sleep 3
    check_no_crash "back right after a pan"
    destroy=$(lift_to_destroy back)
    lift_ms=${destroy%% *}
    echo "back: lift to destroy ${lift_ms:-unknown} ms"
    expect_view panned $(south_of "$last") || fail "the view at Back right after a pan was not stored; see map-view-panned.json"
}
if adb shell run-as "$pkg" true > /dev/null 2>&1; then
    check_reopened_view
else
    echo "not a debug APK (no run-as): the reopened map view is not checked"
fi

adb logcat -d > "$out/logcat.txt"
python3 -I "$here/strict_mode.py" "$out/logcat.txt" || fail "StrictMode caught our code (smoke test)"
echo "smoke test passed"
