#!/usr/bin/env bash
# Offline maps on a running emulator, against two tiny map styles served on the runner
# (scripts/ci/offline_fixture_server.py through adb reverse; no public network): Settings > Offline
# maps > Download an area opens the area picker on the map; Download fetches the area in the light
# and the dark style until the list says Ready under the name of the town the map labels in it, with
# the app's User-Agent, never more than 4 tile requests at once and no tile twice. Then the server
# moves its tiles to a new release and the app, started far from the area, updates the area by
# itself and replaces its packs, so no tile of the old release stays linked to it. With the server
# gone the area draws in light and dark (the fixture's water colour, not the bare background), also
# after three theme switches in one process (which count MainActivity's
# instances for StrictMode), and search, whose servers are gone too, finds the area's places in the
# offline maps, each saying whether it is open now from the opening hours the app kept for the area
# from Overpass (the server's fixture), as soon as the download began, and the place page of one shows
# those hours and its phone number. Delete removes it.
# Usage: emulator-offline.sh <apk> <out dir>
set -euo pipefail

apk=$1
out=$2
pkg=to.pubky.maps
port=18493
here=$(cd "$(dirname "$0")" && pwd)
mkdir -p "$out"
log="$out/requests.jsonl"
: > "$log"
# shellcheck source=scripts/ci/emulator-lib.sh
source "$here/emulator-lib.sh"

python3 -I "$here/offline_fixture_server.py" "$port" "$log" &
server=$!
trap 'kill $server 2> /dev/null || true; adb shell cmd uimode night no > /dev/null 2>&1 || true' EXIT
for _ in $(seq 1 20); do
    curl -fsS "http://127.0.0.1:$port/light.json" > /dev/null 2>&1 && break
    sleep 0.5
done
: > "$log"

adb install -r "$apk" > /dev/null
adb shell am force-stop "$pkg"
adb reverse "tcp:$port" "tcp:$port"
cat > "$out/settings.json" <<JSON
{"schemaVersion": 1, "servers": {
  "map_style_light": [{"name": "Fixture light", "url": "http://127.0.0.1:$port/light.json"}],
  "map_style_dark": [{"name": "Fixture dark", "url": "http://127.0.0.1:$port/dark.json"}],
  "photon": [{"name": "Fixture Photon", "url": "http://127.0.0.1:$port/photon"}],
  "pinakes": [{"name": "Fixture Pinakes", "url": "http://127.0.0.1:$port/pinakes"}],
  "overpass": [{"name": "Fixture Overpass", "url": "http://127.0.0.1:$port/overpass"}]}}
JSON
adb push "$out/settings.json" /data/local/tmp/pubky-maps-settings.json > /dev/null
adb shell chmod 644 /data/local/tmp/pubky-maps-settings.json
adb shell run-as "$pkg" mkdir -p files/datastore
adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-settings.json files/datastore/settings.json
adb shell run-as "$pkg" rm -f no_backup/maplibre.db no_backup/maplibre.db-journal
adb logcat -c || true
adb logcat -b crash -c || true

# When the app last started (open_at); long before the script's start until it does.
app_started=-1000

# Starts the app afresh on a map link, from the whole world (not where the last start left the map).
# A Ready area's check starts 20 s after a start and walks each of its two styles for 10 s; a stop
# during a walk leaves the area "Update paused" until a later start walks it again (without a server,
# not at once). So a stop waits for a check that has started to end, whatever the steps since the
# start took (a run of theme switches, a search).
open_at() {
    local age=$((SECONDS - app_started))
    if ((age >= 15 && age < 45)); then sleep $((45 - age)); fi
    adb shell am force-stop "$pkg"
    adb shell run-as "$pkg" rm -f no_backup/map-view.json
    local started
    started=$(adb shell am start -W -a android.intent.action.VIEW -d "'geo:$1'" -p "$pkg" 2>&1) || true
    app_started=$SECONDS
    if grep -q "Error" <<< "$started"; then fail "the link did not reach the app: $started"; fi
    sleep 5
}

# Waits until the map shows the fixture's water colour $2 over most of the frame's top half.
expect_water() {
    local stats share=0
    for _ in $(seq 1 15); do
        screenshot "$1"
        stats=$(python3 -I "$here/png_stats.py" "$out/$1.png" 0.15 0.2 0.85 0.45 "$2") || stats="0 1.000 0"
        share=$(awk '{print $3}' <<< "$stats")
        if python3 -I -c "import sys; sys.exit(float(sys.argv[1]) < 0.8)" "$share"; then
            echo "$1: water covers $share of the map"
            return 0
        fi
        sleep 2
    done
    fail "the offline map does not draw ($1): water covers $share of the map"
}

open_offline_maps() {
    local xy
    xy=$(wait_for "Settings" 30) || fail "no Settings button on the map"
    adb shell input tap $xy
    xy=$(wait_for "Offline maps" 15) || fail "no Offline maps in Settings"
    adb shell input tap $xy
}

# Waits up to $2 seconds until the list says the area is Ready.
wait_ready() {
    local deadline=$((SECONDS + $2)) dump
    while ((SECONDS < deadline)); do
        dump=$(dump_ui "$1")
        if grep -q 'text="Ready · ' "$dump"; then return 0; fi
        sleep 3
    done
    return 1
}

# A map link puts the map on a few streets of Zürich: small enough for one download.
zurich="47.3769,8.5417?z=16"
open_at "$zurich"

open_offline_maps
xy=$(wait_for "Download an area" 15) || fail "no Download an area on the Offline maps page"
adb shell input tap $xy
wait_for "The map in the frame, light and dark, to use without a connection." 30 > /dev/null ||
    fail "the area picker does not offer the area on screen"
screenshot picker
check_alive "area picker"
xy=$(wait_for "Download" 5) || fail "no Download button in the area picker"
# From here on the requests are the download's (the map has its tiles already); a tile request of
# the map that is still open finishes first.
sleep 2
: > "$log"
adb shell input tap $xy

wait_for "On this phone" 15 > /dev/null || fail "Download did not open the Offline maps page"
wait_ready offline-list 120 || fail "the offline map did not finish downloading"
# Named after the town the map labels in the frame, not its coordinates.
wait_for "Fixture Town" 5 > /dev/null || fail "the offline map is not named after the town in its area"
screenshot offline-ready
check_alive "offline map ready"

# The light style downloads first; the dark one finds the tiles they share on the phone.
python3 -I - "$log" <<'PY' || fail "the download did not keep to the limits"
import collections, json, sys
lines = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
tiles = [line for line in lines if line["path"].startswith("/tiles/")]
assert tiles, "no tile was requested"
most = max(line["open"] for line in tiles)
assert most <= 4, f"{most} tile requests at once"
agents = {line["ua"] for line in tiles}
assert all(agent.startswith("PubkyMaps/") for agent in agents), f"tiles asked as {agents}"
twice = [path for path, n in collections.Counter(line["path"] for line in tiles).items() if n > 1]
assert not twice, f"tiles requested more than once: {twice}"
print(f"{len(tiles)} tile requests, at most {most} at once, as {sorted(agents)}")
details = [line for line in lines if line["path"] == "/overpass"]
assert len(details) == 1, f"{len(details)} Overpass queries for the area's place details, not 1"
assert details[0]["ua"].startswith("PubkyMaps/"), f"Overpass asked as {details[0]['ua']}"
assert "[bbox:" in details[0]["query"] and "make end;out;" in details[0]["query"], details[0]["query"]
print(f"one Overpass query for the place details: {details[0]['query'][:80]}...")
PY
cp "$log" "$out/download.jsonl"

# The server publishes a new release of its map data at new tile addresses. The app starts on the
# other side of the world, so the map itself fetches none of the area's new tiles: only the app's
# check for updates (20 s after the start) can.
curl -fsS "http://127.0.0.1:$port/release/2" > /dev/null
# On a metered network a check stops as soon as it finds the new release: the update needs the
# emulator's Wi-Fi as the default network.
adb shell svc wifi enable || true
default_network() {
    local dump id
    dump=$(adb shell dumpsys connectivity 2> /dev/null) || return 1
    id=$(sed -n 's/.*Active default network: \([0-9]*\).*/\1/p' <<< "$dump" | sed -n 1p)
    [ -n "$id" ] || return 1
    grep -E "network\{$id\}" <<< "$dump" | sed -n 1p
}
network=""
for _ in $(seq 1 15); do
    network=$(default_network) || network=""
    grep -q NOT_METERED <<< "$network" && break
    sleep 2
done
echo "default network: ${network:-none reported}" | cut -c 1-300
: > "$log"
open_at "-33.8688,151.2093?z=16"
# Exit 3: not yet. The map's flight to Sydney may fetch a low-zoom tile of the area itself (MapLibre's
# own client); it counts, while the limits are checked on the app's requests.
updated=""
deadline=$((SECONDS + 150))
while ((SECONDS < deadline)); do
    rc=0
    python3 -I - "$out/download.jsonl" "$log" <<'PY' || rc=$?
import collections, json, sys
def tiles(path):
    lines = [json.loads(line) for line in open(path, encoding="utf-8") if line.strip()]
    return [line for line in lines if line["path"].startswith("/tiles/")]
before = {tuple(line["path"].split("/")[3:]) for line in tiles(sys.argv[1])}
now = [line for line in tiles(sys.argv[2]) if tuple(line["path"].split("/")[3:]) in before]
got = {tuple(line["path"].split("/")[3:]) for line in now if line["path"].startswith("/tiles/2/")}
if not before <= got:
    sys.exit(3)
app = [line for line in now if line["ua"].startswith("PubkyMaps/")]
assert app, "the app fetched none of the area's new tiles"
assert max(line["open"] for line in app) <= 4, "the update sent more than 4 tile requests at once"
twice = [p for p, n in collections.Counter(line["path"] for line in app).items() if n > 1]
assert not twice, f"the update requested tiles more than once: {twice}"
print(f"the area's {len(got)} tiles of the new release are on the phone, {len(app)} fetched by the update")
PY
    if ((rc == 0)); then
        updated=1
        break
    fi
    ((rc == 3)) || fail "the update did not keep to the limits"
    sleep 5
done
cp "$log" "$out/update.jsonl"
[ -n "$updated" ] || fail "the app did not update the offline map to the server's new tiles (default network: ${network:-none reported})"
open_offline_maps
wait_ready offline-updated 60 || fail "the updated offline map is not Ready"
check_alive "offline map updated"
# The check walks each style for 10 s and marks it until its walk ends; the row says Ready during
# a walk, Updating from a walk still downloading after its 10 s until the last walk ends. Stopping
# the app before both walks end leaves the area "Update paused" at the next start until its first
# check walks it again, so the steps below would not see it Ready at once.
sleep 25

# After the update the app replaces each style's pack by one that links only the new release's
# tiles, and deletes the old one: no tile of the release before stays linked to the area (MapLibre's
# map cache keeps them within its size limit). Read from a copy of MapLibre's database (journal mode
# DELETE, so a copy taken between two writes is whole; a torn one is taken again).
linked=""
for _ in $(seq 1 20); do
    adb exec-out run-as "$pkg" cat no_backup/maplibre.db > "$out/maplibre-updated.db" || true
    rc=0
    linked=$(python3 -I - "$out/maplibre-updated.db" <<'PY'
import sqlite3, sys
try:
    db = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True)
    regions = db.execute("SELECT COUNT(*) FROM regions").fetchone()[0]
    rows = db.execute(
        "SELECT t.url_template, COUNT(*) FROM region_tiles r JOIN tiles t ON t.id = r.tile_id GROUP BY t.url_template"
    ).fetchall()
    cached = db.execute("SELECT COUNT(*) FROM tiles WHERE url_template LIKE '%/tiles/1/%'").fetchone()[0]
except sqlite3.DatabaseError as e:
    print(f"unreadable copy: {e}")
    sys.exit(3)
links = {template.split("/tiles/")[1].split("/")[0]: n for template, n in rows}
print(f"{regions} packs; tiles linked per release: {links}; release 1 tiles in the database: {cached}")
sys.exit(0 if regions == 2 and "2" in links and "1" not in links else 3)
PY
) || rc=$?
    ((rc == 0)) && break
    sleep 3
done
echo "$linked"
((rc == 0)) || fail "the update left the release before's tiles linked to the offline map: $linked"

# Without the server the area still draws, in both themes.
kill "$server"
wait "$server" 2> /dev/null || true
open_at "$zurich"
wait_for "Settings" 30 > /dev/null || fail "the map screen did not appear offline"
expect_water offline-light "#3c78d8"
adb shell cmd uimode night yes
open_at "$zurich"
wait_for "Settings" 30 > /dev/null || fail "the map screen did not appear offline (dark)"
expect_water offline-dark "#1c4587"
# Three theme switches in this one process, each with the map drawn after it. A switch recreates
# MainActivity, and Android counts its instances when the old one is destroyed: the old one still
# counts then, so only an earlier one that is still in memory (a leak) goes over the limit. That
# needs a second recreation in the same process; open_at's force-stop would start the count afresh.
# The 5 s after a redraw let the old activity's teardown (MapLibre's render thread) finish, so only
# an activity still held seconds after its destroy counts.
adb shell cmd uimode night no
expect_water offline-light-recreated "#3c78d8"
sleep 5
adb shell cmd uimode night yes
expect_water offline-dark-recreated "#1c4587"
sleep 5
adb shell cmd uimode night no
expect_water offline-light-recreated-again "#3c78d8"
check_alive "offline map drawn without a connection"

# A theme switch while the area's update check walks its packs (from 20 s after a start, at least
# 10 s per style), when the app's download client loads every request of the map too. The new map's
# style and tiles are fresh in MapLibre's cache: asking the unreachable server for them again would
# fail the style the map already drew, and the map would fall back to nothing (run 45865109025).
# The screenshots wait 3 s, as a failed style blanks the map only after its cached copy drew; and no
# style may fail at all.
open_at "$zurich"
since=$(adb shell date +"'%m-%d %H:%M:%S.000'" | tr -d '\r')
wait_for "Settings" 30 > /dev/null || fail "the map screen did not appear offline before the update check"
age=$((SECONDS - app_started))
((age <= 27)) || fail "the map screen took ${age}s to appear, so the theme switch would miss the update check's walk"
if ((age < 23)); then sleep $((23 - age)); fi
adb shell cmd uimode night yes
sleep 3
expect_water offline-dark-during-check "#1c4587"
adb shell cmd uimode night no
sleep 3
expect_water offline-light-during-check "#3c78d8"
age=$((SECONDS - app_started))
failed=$(adb logcat -d -T "$since" | grep -c "loading style failed") || true
echo "theme switches 23-${age} s after the start, styles failed meanwhile: $failed"
((failed == 0)) || fail "a map style failed to load during the update check's walk without a server"
check_alive "theme switches during an update check"

# Without any server, search answers from the places in the area's tiles (by the bakery's word:
# the fixture's town labels are places too, and could fill the list for "fixture").
open_at "$zurich"
xy=$(wait_for "Search here" 30) || fail "no search bar without a connection"
adb shell input tap $xy
# The field takes the keyboard a moment after the tap; earlier keys are lost.
sleep 1
adb shell input text bakery
wait_for "Fixture Bakery" 30 > /dev/null || fail "search without a connection did not find the offline area's places"
# The town it lies in comes from the tiles' place labels (a Fixture Town label is in every tile).
wait_for "Bakery · Fixture Town" 5 > /dev/null || fail "an offline result does not say which town it is in"
# The opening hours kept for the area say on the row whether it is open now (the fixture's are 24/7).
wait_for "Open 24 hours" 5 > /dev/null || fail "an offline result does not say whether it is open from the hours kept for the area"
wait_for "Search is offline: these places are from your offline maps. Street addresses need a connection." 5 > /dev/null ||
    fail "search does not say its results come from the offline maps"
screenshot offline-search
check_alive "search in the offline maps"
# Its page has no Pinakes card without a connection: the details kept for the area stand in.
xy=$(wait_for "Fixture Bakery" 5) || fail "no offline result to open"
adb shell input tap $xy
# The phone number first: the row it was opened from also says "Open 24 hours". The list leaves the
# screen once a result is picked (MapScreen shows it only while typing or with the results), so the
# hours below are the page's.
wait_for "+41 44 000 00 00" 20 > /dev/null || fail "the offline place page does not show the phone number kept for the area"
wait_for "Open 24 hours" 5 > /dev/null || fail "the offline place page does not show the opening hours kept for the area"
screenshot offline-place-details
check_alive "offline place page with kept details"
open_at "$zurich"
open_offline_maps
wait_ready offline-final 20 || fail "the offline map is not Ready on the list"

xy=$(python3 -I - "$(dump_ui offline-delete)" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).getroot().iter("node"):
    if (node.get("content-desc") or "").startswith("Delete Fixture Town"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
else:
    sys.exit(1)
PY
) || fail "no Delete button on the offline map"
adb shell input tap $xy
xy=$(wait_for "Delete" 5) || fail "no confirmation to delete"
adb shell input tap $xy
wait_for "No offline maps yet." 20 > /dev/null || fail "the offline map was not deleted"
check_alive "offline map deleted"

# This also fails on a leaked activity of ours, counted by the theme switches above.
check_strict_mode "offline maps"
echo "an area downloads, updates to new tiles, draws and is searched without a connection in light and dark, its places show their kept details, and it deletes"
