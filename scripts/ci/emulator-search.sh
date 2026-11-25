#!/usr/bin/env bash
# Search on a running emulator, against recorded Photon and Pinakes answers served on the runner
# (scripts/ci/search_fixture_server.py through adb reverse; no public network). The app's Photon list
# starts with a server that is down, so the search also shows the fallback to the next server.
# Types "kunsthaus" and waits for the merged result (Photon's address, Pinakes' "On Pubky"), checks
# that typing asked for the whole word once, never for short text and not per keystroke (debounce),
# always with the app's User-Agent; presses Search, picks the result, goes back twice, opens typed
# coordinates (no request) and finally sees the picked place and the coordinates as recent searches.
# Usage: emulator-search.sh <apk> <out dir>
set -euo pipefail

apk=$1
out=$2
pkg=to.pubky.maps
port=18490
dead_port=18491
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
mkdir -p "$out"
log="$out/requests.jsonl"
: > "$log"

fail() {
    echo "::error::$*"
    adb logcat -d > "$out/logcat.txt" || true
    exit 1
}

screenshot() { adb exec-out screencap -p > "$out/$1.png"; }

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

# Waits up to $2 seconds for a node labelled $1; prints its centre.
wait_for() {
    local deadline=$((SECONDS + $2)) dump
    while ((SECONDS < deadline)); do
        dump=$(dump_ui "wait")
        if centre_of "$dump" "$1"; then return 0; fi
        sleep 1
    done
    return 1
}

check_alive() {
    adb logcat -d -b crash > "$out/crash.txt" || true
    if grep -qE "Process: $pkg|>>> $pkg <<<" "$out/crash.txt"; then fail "the app crashed ($1); see crash.txt"; fi
    adb shell dumpsys activity activities | grep -E "topResumedActivity|mResumedActivity" > "$out/resumed.txt" || true
    grep -q "$pkg/" "$out/resumed.txt" || fail "the app is not the resumed activity ($1): $(cat "$out/resumed.txt")"
}

# Photon and Pinakes lines that ask for exactly $1, and every request line.
asked() { python3 -I -c "
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
print(sum(1 for l in lines if l['path'] == sys.argv[2] and l['q'] == sys.argv[3]))" "$log" "$1" "$2"; }

python3 -I "$here/search_fixture_server.py" "$port" "$root/shared/src/commonTest/fixtures/search" "$log" &
server=$!
trap 'kill $server 2> /dev/null || true' EXIT
for _ in $(seq 1 20); do
    curl -fsS "http://127.0.0.1:$port/api?q=x" > /dev/null 2>&1 && break
    sleep 0.5
done
: > "$log"

adb install -r "$apk" > /dev/null
adb shell am force-stop "$pkg"
adb reverse "tcp:$port" "tcp:$port"
# Nothing listens on the first Photon server: the app must move on to the second.
cat > "$out/settings.json" <<JSON
{"schemaVersion": 1, "servers": {
  "photon": [{"name": "Down", "url": "http://127.0.0.1:$dead_port"}, {"name": "Fixtures", "url": "http://127.0.0.1:$port"}],
  "pinakes": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}]}}
JSON
adb push "$out/settings.json" /data/local/tmp/pubky-maps-settings.json > /dev/null
adb shell chmod 644 /data/local/tmp/pubky-maps-settings.json
adb shell run-as "$pkg" mkdir -p files/datastore
adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-settings.json files/datastore/settings.json
adb shell run-as "$pkg" rm -f no_backup/recent-searches.json
adb logcat -c || true
adb logcat -b crash -c || true

started=$(adb shell am start -W -n "$pkg/.android.MainActivity" 2>&1) || true
if grep -q "Error" <<< "$started"; then fail "the app did not start: $started"; fi
wait_for "Show my location" 60 > /dev/null || fail "the map screen did not appear"

# Typing: one request per source once the typing pauses, with the app's User-Agent.
xy=$(wait_for "Search here" 10) || fail "no search bar"
adb shell input tap $xy
sleep 1
adb shell input text kunsthaus
wait_for "Museum · Heimplatz 1, 8001 Zürich" 30 > /dev/null || fail "no merged Kunsthaus result (Photon's address)"
wait_for "On Pubky" 10 > /dev/null || fail "the Kunsthaus result is not marked On Pubky (Pinakes)"
screenshot search-typing
check_alive "typing"
[ "$(asked /api kunsthaus)" = 1 ] || fail "Photon was asked $(asked /api kunsthaus) times for kunsthaus, expected once"
[ "$(asked /v1/search kunsthaus)" = 1 ] || fail "Pinakes was asked $(asked /v1/search kunsthaus) times for kunsthaus, expected once"
python3 -I -c "
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1], encoding='utf-8') if l.strip()]
short = [l['q'] for l in lines if len(l['q']) < 3]
assert not short, f'asked for short text: {short}'
# adb types fast, so typing pauses rarely; 9 letters asked per keystroke would be 7 requests.
photon = [l['q'] for l in lines if l['path'] == '/api']
assert len(photon) <= 3, f'Photon asked per keystroke: {photon}'
agents = {l['ua'] for l in lines}
assert all(a.startswith('PubkyMaps/') for a in agents), f'User-Agent: {agents}'
print('requests:', len(lines), 'user agents:', agents)" "$log" || fail "requests broke the fair-use rules"

# Search key: the results on the map, then the place.
adb shell input keyevent KEYCODE_ENTER
wait_for "Show my location" 15 > /dev/null || fail "Search did not show the results on the map"
xy=$(wait_for "Museum · Heimplatz 1, 8001 Zürich" 10) || fail "no results list on the map"
screenshot search-results
adb shell input tap $xy
wait_for "Heimplatz 1, 8001 Zürich" 15 > /dev/null || fail "the place card did not open"
# Share needs the map-link core, which this build may not have; Close is always there.
wait_for "Close" 5 > /dev/null || fail "the place card has no Close button"
screenshot search-place
check_alive "place card"

# Back: the results, then the map with an empty bar.
adb shell input keyevent KEYCODE_BACK
wait_for "Museum · Heimplatz 1, 8001 Zürich" 10 > /dev/null || fail "back from the place did not return to the results"
adb shell input keyevent KEYCODE_BACK
sleep 1
dump=$(dump_ui after-back)
centre_of "$dump" "Search here" > /dev/null || fail "back from the results did not empty the bar"
if centre_of "$dump" "Museum · Heimplatz 1, 8001 Zürich" > /dev/null; then fail "the results stayed after back"; fi

# Coordinates: no request, a card titled with the point.
lines_before=$(wc -l < "$log")
xy=$(wait_for "Search here" 10) || fail "no search bar after back"
adb shell input tap $xy
sleep 1
adb shell input text "47.37,8.54"
adb shell input keyevent KEYCODE_ENTER
wait_for "Coordinates" 15 > /dev/null || fail "typed coordinates did not open a card"
screenshot search-coordinates
[ "$(wc -l < "$log")" = "$lines_before" ] || fail "coordinates went to the network"
adb shell input keyevent KEYCODE_BACK
sleep 1

# Recent searches: the picked place and the coordinates, newest first.
xy=$(wait_for "Search here" 10) || fail "no search bar for recents"
adb shell input tap $xy
wait_for "Recent searches" 10 > /dev/null || fail "no recent searches for an empty bar"
wait_for "Kunsthaus" 5 > /dev/null || fail "the picked place is not a recent search"
wait_for "47.37,8.54" 5 > /dev/null || fail "the coordinates are not a recent search"
screenshot search-recent
check_alive "recent searches"
adb shell input keyevent KEYCODE_BACK
adb shell input keyevent KEYCODE_BACK
sleep 1
check_alive "end"

adb logcat -d > "$out/logcat.txt"
echo "search test passed"
