#!/usr/bin/env bash
# Pubky places on the base map, on a running emulator, against the recorded Pinakes places of Zürich
# (scripts/ci/place_fixture_server.py through adb reverse) and the live map styles: the map opens at
# zoom 16 on the Conditorei Schober (node 270803095), which Pinakes holds and the base map has as a
# point of interest, so it is drawn highlighted (MapPubkyPois.kt: a teal badge with its kind's glyph
# and its name in teal), not as a small teal dot beside the map's own icon. Checked by the share of
# the badge's teal at the screen's centre in the light (OpenFreeMap Liberty, which draws points of
# interest, so the badge has to win against the map's own icon and label) and the dark map
# (OpenFreeMap Dark, which draws none: the schema's layer is used). A tap at the badge opens the
# place's page (a dot or the map's own icon there would open it too: this checks that the badge
# leaves the page reachable). Screenshots at zoom 16 and 14 are kept for a look.
# Usage: emulator-pubky-pois.sh <apk> <out dir>
set -euo pipefail

apk=$1
out=$2
pkg=to.pubky.maps
port=18493
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
mkdir -p "$out"
log="$out/requests.jsonl"
: > "$log"
# shellcheck source=scripts/ci/emulator-lib.sh
source "$here/emulator-lib.sh"

python3 -I "$here/place_fixture_server.py" "$port" "$root/shared/src/commonTest/fixtures/place" "$log" &
server=$!
trap 'kill $server 2> /dev/null || true; adb shell cmd uimode night no > /dev/null 2>&1 || true' EXIT
for _ in $(seq 1 20); do
    curl -fsS "http://127.0.0.1:$port/v1/search?q=x" > /dev/null 2>&1 && break
    sleep 0.5
done
: > "$log"

adb install -r "$apk" > /dev/null
adb reverse "tcp:$port" "tcp:$port"
cat > "$out/settings.json" <<JSON
{"schemaVersion": 1, "servers": {
  "pinakes": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}],
  "nexus": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}]}}
JSON
adb logcat -G 16M || true
adb logcat -c || true
adb logcat -b crash -c || true

# open_at NAME ZOOM: starts the app afresh at the Conditorei Schober at ZOOM (the view a start opens
# at, no_backup/map-view.json) and takes a screenshot NAME once the places are on the map.
open_at() {
    adb shell am force-stop "$pkg"
    adb push "$out/settings.json" /data/local/tmp/pubky-maps-settings.json > /dev/null
    adb shell chmod 644 /data/local/tmp/pubky-maps-settings.json
    adb shell run-as "$pkg" mkdir -p files/datastore no_backup
    adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-settings.json files/datastore/settings.json
    printf '{"view":{"lat":47.3708689,"lon":8.5418241,"zoom":%s}}' "$2" > "$out/map-view.json"
    adb push "$out/map-view.json" /data/local/tmp/pubky-maps-view.json > /dev/null
    adb shell chmod 644 /data/local/tmp/pubky-maps-view.json
    adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-view.json no_backup/map-view.json
    : > "$log"
    adb shell am start -W -n "$pkg/.android.MainActivity" > /dev/null 2>&1 || true
    wait_for "Show my location" 60 > /dev/null || fail "the map did not open ($1)"
    # The places layer asks after the camera rests (400 ms); the tiles and the highlight follow.
    for _ in $(seq 1 15); do
        grep -q '"/v1/search"' "$log" && break
        sleep 1
    done
    grep -q '"/v1/search"' "$log" || fail "the map did not ask Pinakes for its places ($1)"
    sleep 8
    screenshot "$1"
    check_alive "$1"
}

# expect_badge NAME: the badge's teal (#00897B) covers the middle of the screen as a 24 dp badge
# does, not only as a 12 dp dot. The box is ~37 x 40 dp around the screen's centre, where the
# camera's centre is (the map fills the screen, no camera padding): a badge, less its white ring and
# glyph, is ~15-17 % of it at the Pixel 6's density, a dot ~7 %; the bar is 12 %.
expect_badge() {
    local stats share
    stats=$(python3 -I "$here/png_stats.py" "$out/$1.png" 0.455 0.478 0.545 0.522 '#00897b') ||
        fail "png_stats could not read $1.png"
    share=$(awk '{print $3}' <<< "$stats")
    echo "$1: teal share at the centre $share"
    python3 -I -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.12 else 1)" "$share" ||
        fail "the Conditorei Schober is not highlighted on the map ($1; share of the badge's teal at the centre: $share)"
}

adb shell cmd uimode night no
open_at pois-z16-light 16
expect_badge pois-z16-light
open_at pois-z14-light 14

adb shell cmd uimode night yes
open_at pois-z16-dark 16
expect_badge pois-z16-dark
adb shell cmd uimode night no

# A tap on the badge opens the place's page (the Pinakes place).
open_at pois-z16-tap 16
size=$(adb shell wm size | awk '/Physical/ {print $3}')
w=${size%x*}
h=${size#*x}
adb shell input tap $((w / 2)) $((h / 2))
wait_for "Conditorei schober" 20 > /dev/null || fail "a tap on the highlighted point of interest did not open its place"
screenshot pois-tapped
check_alive "tapped highlight"
adb shell input keyevent KEYCODE_BACK

check_strict_mode "Pubky points of interest"
echo "Pubky points of interest test passed"
