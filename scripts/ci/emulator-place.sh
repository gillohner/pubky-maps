#!/usr/bin/env bash
# The place page on a running emulator, against recorded Pinakes and Nexus answers served on the
# runner (scripts/ci/place_fixture_server.py through adb reverse; no public network): an
# openstreetmap.org link to the Conditorei Schober opens the app on its place page, a sheet over the
# map with the card's name and kind, the Pubky tags and the posts. Directions, with location granted
# and the emulator's GPS at Zürich HB, ask the fixture Valhalla for a route by car from there to the
# place, show its time and draw it on the map; Walk asks again on foot, the swap button from the place
# back to the fix and again to the place. Its steps open and raise the sheet to the whole screen; a
# tapped step lowers it and the map moves to that turn, as does the route card's next step. The lists
# holding the place show at the top; back closes it. A long-press on the map then drops a pin: its
# page is named by its coordinates and asks no server; its Start here makes it the start of the
# next directions: the link opened again, Directions route from the pin to the place, and From your
# location from the fix again; back closes it.
# Checks that Pinakes, Nexus and Valhalla were asked with the app's User-Agent, Nexus for every
# spelling people used for the place.
# Usage: emulator-place.sh <apk> <out dir>
set -euo pipefail

apk=$1
out=$2
pkg=to.pubky.maps
port=18492
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
mkdir -p "$out"
log="$out/requests.jsonl"
: > "$log"
# shellcheck source=scripts/ci/emulator-lib.sh
source "$here/emulator-lib.sh"

# Waits up to $2 seconds for a node whose text starts with $1 (the kind line carries the distance
# once location is granted: "Café · 790 m"); prints its centre.
wait_for_start() {
    local deadline=$((SECONDS + $2)) dump
    while ((SECONDS < deadline)); do
        dump=$(dump_ui "wait")
        handle_anr_dialog "$dump" >&2
        if python3 -I - "$dump" "$1" <<'PY'; then return 0; fi
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except ET.ParseError:
    sys.exit(1)
for node in root.iter("node"):
    if (node.get("text") or "").startswith(sys.argv[2]):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        sys.exit(0)
sys.exit(1)
PY
        sleep 2
    done
    return 1
}

python3 -I "$here/place_fixture_server.py" "$port" "$root/shared/src/commonTest/fixtures/place" "$log" &
server=$!
trap 'kill $server 2> /dev/null || true' EXIT
for _ in $(seq 1 20); do
    curl -fsS "http://127.0.0.1:$port/v1/search?q=x" > /dev/null 2>&1 && break
    sleep 0.5
done
: > "$log"

adb install -r "$apk" > /dev/null
adb shell am force-stop "$pkg"
adb reverse "tcp:$port" "tcp:$port"
cat > "$out/settings.json" <<JSON
{"schemaVersion": 1, "servers": {
  "pinakes": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}],
  "nexus": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}],
  "valhalla": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}],
  "panoramax": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}]}}
JSON
adb push "$out/settings.json" /data/local/tmp/pubky-maps-settings.json > /dev/null
adb shell chmod 644 /data/local/tmp/pubky-maps-settings.json
adb shell run-as "$pkg" mkdir -p files/datastore
adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-settings.json files/datastore/settings.json
adb shell run-as "$pkg" rm -f no_backup/place-cache.db no_backup/place-cache.db-journal
# The map opens at the whole world, not where an earlier test left it.
adb shell run-as "$pkg" rm -f no_backup/map-view.json
# A log buffer large enough to keep StrictMode's reports from the start until the end.
adb logcat -G 16M || true
adb logcat -c || true
adb logcat -b crash -c || true

# The emulator's GPS at Zürich HB, sent again every 2 s: Android hands a fix only to listeners
# already registered.
( for _ in $(seq 1 90); do adb emu geo fix 8.5403 47.3779 > /dev/null 2>&1 || true; sleep 2; done ) &
gps=$!
# The tests after this one start without location access, as a fresh install does.
revoke_location() {
    adb shell pm revoke "$pkg" android.permission.ACCESS_FINE_LOCATION > /dev/null 2>&1 || true
    adb shell pm revoke "$pkg" android.permission.ACCESS_COARSE_LOCATION > /dev/null 2>&1 || true
}
trap 'kill $server $gps 2> /dev/null || true; revoke_location' EXIT
# Directions start from the user's position: the permission is granted up front (the dialog is the
# my-location button's, tested in the smoke test) and the GPS is put at Zürich HB above.
adb shell pm grant "$pkg" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$pkg" android.permission.ACCESS_COARSE_LOCATION
started=$(adb shell am start -W -a android.intent.action.VIEW -d "'https://www.openstreetmap.org/node/270803095'" -p "$pkg" 2>&1) || true
if grep -q "Error" <<< "$started"; then fail "the link did not reach the app: $started"; fi

wait_for "Conditorei schober" 60 > /dev/null || fail "no place page for the Conditorei Schober"
wait_for_start "Café" 15 > /dev/null || fail "the place page does not say what the place is (the Pinakes card)"
wait_for "Tags on Pubky" 20 > /dev/null || fail "no Pubky tags on the place page (Nexus)"
wait_for "coffee" 5 > /dev/null || fail "the coffee tag is missing"
sleep 3
screenshot place-half
check_alive "place page"

xy=$(wait_for "Directions" 10) || fail "no Directions on the place page"
adb shell input tap $xy
wait_for "5 min · 1.4 km" 40 > /dev/null || fail "no route time and length after Directions"
# A server the user added carries no credits of its own: OpenStreetMap's, and where to report errors.
wait_for "© OpenStreetMap contributors · Report a map error" 5 > /dev/null || fail "no routing credits under the route"
sleep 3
screenshot place-directions
check_alive "directions"
# The route's darker edge (RouteLine.kt) on the map above the sheet.
stats=$(python3 -I "$here/png_stats.py" "$out/place-directions.png" 0 0.1 1 0.45 '#0b4fb3') ||
    fail "png_stats could not read the screenshot"
share=$(awk '{print $3}' <<< "$stats")
python3 -I -c "import sys; sys.exit(0 if float(sys.argv[1]) >= 0.0005 else 1)" "$share" ||
    fail "the route is not drawn on the map (share of its colour: $share)"
xy=$(wait_for "Walk" 5) || fail "no Walk mode in the directions"
adb shell input tap $xy
sleep 4
check_alive "directions on foot"
# Swapped, the route goes from the place back to the GPS fix; swapped again, to the place.
xy=$(wait_for "Swap start and destination" 5) || fail "no swap button in the route card"
adb shell input tap $xy
wait_for "On foot to your location" 20 > /dev/null || fail "the swapped route does not say it goes to the user's location"
check_alive "swapped directions"
adb shell input tap $xy
wait_for "On foot from your location" 20 > /dev/null || fail "swapping again did not route from the user's location"
# The route's steps open under the header, the sheet raised to Full for them (the fixture's turn
# instructions, as FOSSGIS gave them; the fixture answers every mode with the same route).
xy=$(wait_for "Steps" 5) || fail "no Steps button under the route"
adb shell input tap $xy
sleep 2
wait_for "Show less of the place" 5 > /dev/null || fail "opening the steps did not raise the sheet to the top"
wait_for "Turn right onto Museumstrasse." 5 > /dev/null || fail "the route's steps did not open"
screenshot place-steps
check_alive "steps"
# A tapped step lowers the sheet and the map moves to the turn at zoom 17 (DirectionsController
# .showStep, MapRoute's moveToStep), the route card showing it; the next step from the card moves
# the map on. The view the camera rests at is stored (no_backup/map-view.json), so it says where the
# map went: the turn's point on the fixture's line, the centre south of it by half the sheet's lowest
# stop (~480 dp with the step card, ~240 dp or ~0.0009 degrees at zoom 17), as the turn lands in the
# middle of the map above the sheet. That the sheet is at its lowest stop, not half, PlaceSheetStepsTest checks.
# expect_step_view NAME LAT LON: the stored view is at zoom 17, at the turn's longitude and
# 0.0004-0.0016 degrees south of LAT LON.
expect_step_view() {
    for _ in $(seq 1 8); do
        sleep 1
        adb shell run-as "$pkg" cat no_backup/map-view.json > "$out/map-view-$1.json" 2> /dev/null || true
        python3 -I -c "
import json, sys
try:
    view = json.load(open(sys.argv[1], encoding='utf-8')).get('view') or {}
except ValueError:
    view = {}
lat, lon = float(sys.argv[2]), float(sys.argv[3])
south = lat - view.get('lat', 0)
ok = abs(view.get('zoom', 0) - 17) < 0.1 and 0.0004 <= south <= 0.0016 and abs(view.get('lon', 0) - lon) < 0.0004
sys.exit(0 if ok else 1)" "$out/map-view-$1.json" "$2" "$3" && return 0
    done
    fail "the map did not move to the step $1 at $2,$3 (stored view: $(cat "$out/map-view-$1.json" 2> /dev/null))"
}
xy=$(wait_for "Turn left onto Rudolf-Brun-Brücke." 3) || fail "no Rudolf-Brun-Brücke step in the list"
adb shell input tap $xy
wait_for "Step 5 of 9 · 330 m" 5 > /dev/null || fail "the route card does not show the tapped step"
wait_for "Show more of the place" 5 > /dev/null || fail "a tapped step did not lower the sheet"
expect_step_view rudolf-brun 47.374429 8.541692
screenshot place-step
check_alive "step on the map"
xy=$(wait_for "Next step" 3) || fail "no Next step in the route card"
adb shell input tap $xy
wait_for "Step 6 of 9 · 210 m" 5 > /dev/null || fail "Next step did not show the next step"
expect_step_view seilergraben 47.374664 8.545873
check_alive "next step"
# Closed again so the page is as long as before, at Full again for the lists.
xy=$(wait_for "Hide steps" 3) || fail "no Hide steps button"
adb shell input tap $xy
sleep 1
kill $gps 2> /dev/null || true
wait_for "Step 6 of 9 · 210 m" 1 > /dev/null && fail "closing the steps left the step in the route card"
# The handle raises the sheet a stop at a time (half, then the top).
for _ in 1 2; do
    xy=$(wait_for "Show more of the place" 3) || break
    adb shell input tap $xy
    sleep 1
done
wait_for "Show less of the place" 3 > /dev/null || fail "the sheet's handle did not raise it to the top"
found=""
for _ in 1 2 3; do
    if wait_for "In 3 collections" 4 > /dev/null; then found=1; break; fi
    adb shell input swipe 540 1700 540 900 400
    sleep 1
done
[ -n "$found" ] || fail "the collections holding the place are not on the page"
wait_for "Posts" 5 > /dev/null || fail "no posts about the place"
screenshot place-full
check_alive "place page full"
# Street-level photos of the place at the end of the page, from the fixture Panoramax (the recorded
# answer, its thumbnails served by the fixture server; a thumbnail that fails takes its photo off).
found=""
for _ in 1 2 3 4 5; do
    if wait_for "Street-level photos" 3 > /dev/null; then found=1; break; fi
    adb shell input swipe 540 1700 540 900 400
    sleep 1
done
[ -n "$found" ] || fail "no street-level photos on the place page"
sleep 3
wait_for "Street-level photo by Koreller taken Jul 2025" 5 > /dev/null || fail "the first street-level photo is not shown"
wait_for "Photos: Panoramax, CC BY-SA 4.0" 5 > /dev/null || fail "no licence under the street-level photos"
screenshot place-street-photos
check_alive "street-level photos"

adb shell input keyevent KEYCODE_BACK
sleep 2
dump=$(dump_ui after-back)
if centre_of "$dump" "Conditorei schober" > /dev/null; then fail "back did not close the place page"; fi
centre_of "$dump" "Show my location" > /dev/null || fail "back did not return to the map"
check_alive "after back"

# A long-press on the map drops a pin there: the point's page, named by its coordinates (the map is
# at the route's last shown step, in Zürich), with Directions. The point is asked of no server.
adb shell input swipe 540 900 540 900 1500
wait_for_start "Dropped pin" 15 > /dev/null || fail "a long-press on the map dropped no pin"
wait_for_start "47.3" 5 > /dev/null || fail "the dropped pin's page is not named by its coordinates"
wait_for "Directions" 5 > /dev/null || fail "no Directions on the dropped pin's page"
screenshot place-dropped-pin
check_alive "dropped pin"
# Its page asks Pinakes and Nexus nothing (the places layer is held while it is open, see layerMayAsk).
if grep -q "geo:" "$log"; then fail "the dropped pin's point was sent to a server"; fi
xy=$(wait_for "Start here" 5) || fail "no Start here on the dropped pin's page"
adb shell input tap $xy
wait_for "Starting here" 5 > /dev/null || fail "Start here did not make the dropped pin the start"
wait_for "Directions to other places start here." 5 > /dev/null || fail "the chosen start does not say what it does"
screenshot place-dropped-pin-start
adb shell input keyevent KEYCODE_BACK
sleep 2
wait_for "Show my location" 10 > /dev/null || fail "back did not return to the map from the dropped pin"
if wait_for_start "Dropped pin" 2 > /dev/null; then fail "back did not close the dropped pin's page"; fi
check_alive "after the dropped pin"

# The place again from its link: its Directions start at the dropped pin, no position asked, the
# start's dot on the map (looked at in the screenshot); From your location routes from the GPS fix
# again, fed anew (it was stopped for the steps' views).
( for _ in $(seq 1 30); do adb emu geo fix 8.5403 47.3779 > /dev/null 2>&1 || true; sleep 2; done ) &
gps=$!
started=$(adb shell am start -W -a android.intent.action.VIEW -d "'https://www.openstreetmap.org/node/270803095'" -p "$pkg" 2>&1) || true
if grep -q "Error" <<< "$started"; then fail "the link did not reach the app again: $started"; fi
wait_for "Conditorei schober" 30 > /dev/null || fail "no place page for the Conditorei Schober from its link again"
xy=$(wait_for "Directions" 10) || fail "no Directions on the place page opened again"
adb shell input tap $xy
wait_for_start "On foot from 47.3" 30 > /dev/null || fail "the directions do not start at the dropped pin"
wait_for "From your location" 5 > /dev/null || fail "no way back to the user's location in the route card"
sleep 3
screenshot place-directions-from-pin
check_alive "directions from the dropped pin"
xy=$(wait_for "From your location" 5) || fail "no From your location in the route card"
adb shell input tap $xy
wait_for "On foot from your location" 20 > /dev/null || fail "From your location did not route from the user's location"
check_alive "directions from the user's location again"
adb shell input keyevent KEYCODE_BACK
sleep 2
wait_for "Show my location" 10 > /dev/null || fail "back did not return to the map from the place opened again"

python3 -I - "$log" <<'PY' || fail "the place page did not ask Pinakes and Nexus as expected"
import json, sys
lines = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8") if l.strip()]
paths = [l["path"] for l in lines]
assert "/v1/resources/by-uri" in paths, paths
views = [l for l in lines if l["path"] == "/v1/stream/resources/by_ids"]
assert views, paths
asked = set(views[0]["uris"])
assert {"https://www.openstreetmap.org/node/270803095", "https://www.openstreetmap.org/node/270803095/"} <= asked, asked
assert "/v1/stream/users/by_ids" in paths, paths
assert any(p.endswith("/replies") for p in paths), paths
assert any(p.endswith("/collections") for p in paths), paths
# The link itself is read by the Rust core (its own User-Agent); the page's requests by the app.
agents = {l["ua"] for l in lines}
assert all(a.startswith(("PubkyMaps/", "pubky-maps-core/")) for a in agents), agents
assert all(l["ua"].startswith("PubkyMaps/") for l in lines if l["path"].startswith("/v1/stream/")), agents
# Directions: by car from the GPS fix to the card's point, then on foot from the same start, swapped
# (from the card's point back to the fix) and swapped again; later on foot from the dropped pin
# (somewhere else on the map), then from the fix again.
routes = [l for l in lines if l["path"] == "/route"]
assert [r["costing"] for r in routes] == ["pedestrian" if i else "auto" for i in range(6)], routes
for i, r in enumerate(routes):
    start, end = r["locations"]
    if i == 2:
        start, end = end, start
    fix = abs(start["lat"] - 47.3779) < 1e-3 and abs(start["lon"] - 8.5403) < 1e-3
    assert fix == (i != 4), r
    assert abs(end["lat"] - 47.3708689) < 1e-6 and abs(end["lon"] - 8.5418241) < 1e-6, r
    assert r["ua"].startswith("PubkyMaps/"), r
pin = routes[4]["locations"][0]
assert abs(pin["lat"] - 47.37) < 0.1 and abs(pin["lon"] - 8.54) < 0.1, pin
# Street-level photos: Panoramax asked once per place opened, for the card's point, with the app's
# User-Agent; the thumbnails loaded.
photos = [l for l in lines if l["path"] == "/api/search"]
assert photos and all(p["ua"].startswith("PubkyMaps/") for p in photos), photos
assert photos[0]["place_position"] == "8.5418241,47.3708689", photos[0]
assert any(l["path"].startswith("/panoramax/thumb/") for l in lines), paths
print("requests:", len(lines), sorted(set(paths)))
PY

check_strict_mode "place page"
echo "place page test passed"
