#!/usr/bin/env bash
# Pubky places on the base map, on a running emulator, against the recorded Pinakes places of Zürich
# (scripts/ci/place_fixture_server.py through adb reverse) and the live map styles: the map opens at
# zoom 16 on the Conditorei Schober (node 270803095), which Pinakes holds and the base map has as a
# point of interest, so it is drawn highlighted (MapPubkyPois.kt), not as a small teal dot beside the
# map's own icon. On the light map (OpenFreeMap Liberty, which draws points of interest, so the
# highlight has to win against the map's own icon and label) it is the style's own café icon (read
# from its style and sprite, StyleIcons.kt) in a teal ring: checked by the ring's teal around the
# screen's centre and the cup's orange in it. On the dark map (OpenFreeMap Dark, which draws none:
# the schema's layer is used, and there is no icon of the style's) it is a teal badge with its
# kind's glyph: checked by the badge's teal at the centre. A tap at the badge opens the
# place's page (a dot or the map's own icon there would open it too: this checks that the badge
# leaves the page reachable). The name under the badge is checked for its teal, and every places
# request for Pinakes' referenced filter. Screenshots at zoom 16 and 14 are kept for a look.
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
    # Only places someone referenced on Pubky are Pubky places, not every point of interest of
    # Pinakes' OpenStreetMap catalogue.
    unfiltered=$(grep '"/v1/search"' "$log" | grep -vc '"referenced": "true"' || true)
    [ "$unfiltered" = 0 ] || fail "the map asked Pinakes $unfiltered times for places without referenced=true ($1)"
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

# expect_ring NAME: the style's own café icon in the highlight's teal ring. The ring's teal (#00897B,
# a 2 dp ring of 24 dp) is ~9 % of the box of expect_badge, less its anti-aliased edges ~5 %; the bar
# is 3 % (the map's own icon has no teal); the cup's orange (#D97200, OpenFreeMap's cafe image) is in a ~10 x 10 dp box at the centre,
# where a dot or a badge would be teal; its bar is 8 %.
expect_ring() {
    local stats ring cup
    stats=$(python3 -I "$here/png_stats.py" "$out/$1.png" 0.455 0.478 0.545 0.522 '#00897b') ||
        fail "png_stats could not read $1.png"
    ring=$(awk '{print $3}' <<< "$stats")
    stats=$(python3 -I "$here/png_stats.py" "$out/$1.png" 0.488 0.4945 0.512 0.5055 '#d97200') ||
        fail "png_stats could not read $1.png"
    cup=$(awk '{print $3}' <<< "$stats")
    echo "$1: ring teal share $ring, cup orange share at the centre $cup"
    python3 -I -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.03 else 1)" "$ring" ||
        fail "the Conditorei Schober is not highlighted on the map ($1; share of the ring's teal: $ring)"
    python3 -I -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.08 else 1)" "$cup" ||
        fail "the Conditorei Schober's highlight does not show the map's own café icon ($1; share of its orange: $cup)"
}

# expect_name NAME SHADE: the name under the badge is drawn in the name's teal SHADE (#00695C light,
# #4DB6AC dark), not in the map's own label colour. The CI emulator's renderer drops the green
# channel of every map text, the base style's own too (Liberty's water names, #495E91, come out
# #490091), while icons keep it; so the row counts in the shade as drawn there (green 0) or as a phone
# draws it. The box is the row of "Conditorei schober" under the badge: the name is ~17 % of it on
# light and ~9 % on dark (thinner letters on the dark style's halo), any other colour ~0 %; the bar
# is 5 %.
expect_name() {
    local shade=$1 as_drawn share
    as_drawn="#${shade:1:2}00${shade:5:2}"
    share=$(python3 -I -c '
import subprocess, sys
here, png, *shades = sys.argv[1:]
print(sum(float(subprocess.check_output([sys.executable, "-I", f"{here}/png_stats.py", png, "0.36", "0.515", "0.64", "0.533", s], text=True).split()[2]) for s in shades))
' "$here" "$out/$2.png" "$shade" "$as_drawn") || fail "png_stats could not read $2.png"
    echo "$2: name shade share under the badge $share"
    python3 -I -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.05 else 1)" "$share" ||
        fail "the Conditorei Schober's name is not drawn in the Pubky teal ($2; share of $shade or $as_drawn: $share)"
}

adb shell cmd uimode night no
open_at pois-z16-light 16
expect_ring pois-z16-light
expect_name '#00695c' pois-z16-light
open_at pois-z14-light 14

adb shell cmd uimode night yes
open_at pois-z16-dark 16
expect_badge pois-z16-dark
expect_name '#4db6ac' pois-z16-dark
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
