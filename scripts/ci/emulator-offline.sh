#!/usr/bin/env bash
# Offline maps on a running emulator, against two tiny map styles served on the runner
# (scripts/ci/offline_fixture_server.py through adb reverse; no public network): Settings > Offline
# maps > Download an area opens the area picker on the map; Download fetches the area in the light
# and the dark style until the list says Ready, with the app's User-Agent, never more than 4 tile
# requests at once and no tile twice. Then the server moves its tiles to a new release and the app,
# started far from the area, updates the area by itself. With the server gone the area draws in
# light and dark (the fixture's water colour, not the bare background). Delete removes it.
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
  "map_style_dark": [{"name": "Fixture dark", "url": "http://127.0.0.1:$port/dark.json"}]}}
JSON
adb push "$out/settings.json" /data/local/tmp/pubky-maps-settings.json > /dev/null
adb shell chmod 644 /data/local/tmp/pubky-maps-settings.json
adb shell run-as "$pkg" mkdir -p files/datastore
adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-settings.json files/datastore/settings.json
adb shell run-as "$pkg" rm -f no_backup/maplibre.db no_backup/maplibre.db-journal
adb logcat -c || true
adb logcat -b crash -c || true

# Starts the app afresh on a map link.
open_at() {
    adb shell am force-stop "$pkg"
    local started
    started=$(adb shell am start -W -a android.intent.action.VIEW -d "'geo:$1'" -p "$pkg" 2>&1) || true
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
# The check walks each style for 10 s and marks it until its walk ends; the row says Ready during a
# walk. Stopping the app before both walks end leaves the area "Update paused" at the next start
# until its first check walks it again, so the steps below would not see it Ready at once.
sleep 25

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
adb shell cmd uimode night no
check_alive "offline map drawn without a connection"
open_offline_maps
wait_ready offline-final 20 || fail "the offline map is not Ready on the list"

xy=$(python3 -I - "$(dump_ui offline-delete)" <<'PY'
import re, sys, xml.etree.ElementTree as ET
for node in ET.parse(sys.argv[1]).getroot().iter("node"):
    if (node.get("content-desc") or "").startswith("Delete Map around"):
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

adb logcat -d > "$out/logcat.txt" || true
echo "an area downloads, updates to new tiles, draws without a connection in light and dark, and deletes"
