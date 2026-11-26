#!/usr/bin/env bash
# Offline maps on a running emulator, against two tiny map styles served on the runner
# (scripts/ci/offline_fixture_server.py through adb reverse; no public network): Settings > Offline
# maps > Download an area opens the area picker on the map; Download fetches the area in the light
# and the dark style until the list says Ready; Delete removes it. Checks that the download sent the
# app's User-Agent and never more than 4 tile requests at once.
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
trap 'kill $server 2> /dev/null || true' EXIT
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

# A map link puts the map on a few streets of Zürich: small enough for one download.
started=$(adb shell am start -W -a android.intent.action.VIEW -d "'geo:47.3769,8.5417?z=16'" -p "$pkg" 2>&1) || true
if grep -q "Error" <<< "$started"; then fail "the link did not reach the app: $started"; fi
sleep 5

xy=$(wait_for "Settings" 30) || fail "no Settings button on the map"
adb shell input tap $xy
xy=$(wait_for "Offline maps" 15) || fail "no Offline maps in Settings"
adb shell input tap $xy
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
found=""
deadline=$((SECONDS + 120))
while ((SECONDS < deadline)); do
    dump=$(dump_ui offline-list)
    if grep -q 'text="Ready · ' "$dump"; then found=1; break; fi
    sleep 3
done
[ -n "$found" ] || fail "the offline map did not finish downloading"
screenshot offline-ready
check_alive "offline map ready"

python3 -I - "$log" <<'PY' || fail "the download did not keep to the limits"
import json, sys
lines = [json.loads(line) for line in open(sys.argv[1], encoding="utf-8") if line.strip()]
tiles = [line for line in lines if line["path"].startswith("/tiles/")]
assert tiles, "no tile was requested"
most = max(line["open"] for line in tiles)
assert most <= 4, f"{most} tile requests at once"
agents = {line["ua"] for line in tiles}
assert all(agent.startswith("PubkyMaps/") for agent in agents), f"tiles asked as {agents}"
print(f"{len(tiles)} tile requests, at most {most} at once, as {sorted(agents)}")
PY

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
echo "an area downloads for offline use and deletes"
