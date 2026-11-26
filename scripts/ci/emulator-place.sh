#!/usr/bin/env bash
# The place page on a running emulator, against recorded Pinakes and Nexus answers served on the
# runner (scripts/ci/place_fixture_server.py through adb reverse; no public network): an
# openstreetmap.org link to the Conditorei Schober opens the app on its place page, a sheet over the
# map with the card's name and kind, the Pubky tags and the posts; the handle raises it to the whole
# screen, where the lists holding the place show; back closes it. Checks that Pinakes and Nexus were
# asked with the app's User-Agent, Nexus for every spelling people used for the place.
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
  "nexus": [{"name": "Fixtures", "url": "http://127.0.0.1:$port"}]}}
JSON
adb push "$out/settings.json" /data/local/tmp/pubky-maps-settings.json > /dev/null
adb shell chmod 644 /data/local/tmp/pubky-maps-settings.json
adb shell run-as "$pkg" mkdir -p files/datastore
adb shell run-as "$pkg" cp /data/local/tmp/pubky-maps-settings.json files/datastore/settings.json
adb shell run-as "$pkg" rm -f no_backup/place-cache.db no_backup/place-cache.db-journal
# A log buffer large enough to keep StrictMode's reports from the start until the end.
adb logcat -G 16M || true
adb logcat -c || true
adb logcat -b crash -c || true

started=$(adb shell am start -W -a android.intent.action.VIEW -d "'https://www.openstreetmap.org/node/270803095'" -p "$pkg" 2>&1) || true
if grep -q "Error" <<< "$started"; then fail "the link did not reach the app: $started"; fi

wait_for "Conditorei schober" 60 > /dev/null || fail "no place page for the Conditorei Schober"
wait_for "Café" 15 > /dev/null || fail "the place page does not say what the place is (the Pinakes card)"
wait_for "Tags on Pubky" 20 > /dev/null || fail "no Pubky tags on the place page (Nexus)"
wait_for "coffee" 5 > /dev/null || fail "the coffee tag is missing"
sleep 3
screenshot place-half
check_alive "place page"

xy=$(wait_for "Show more of the place" 5) || fail "no sheet handle"
adb shell input tap $xy
sleep 2
found=""
for _ in 1 2 3; do
    if wait_for "In 3 lists" 4 > /dev/null; then found=1; break; fi
    adb shell input swipe 540 1700 540 900 400
    sleep 1
done
[ -n "$found" ] || fail "the lists holding the place are not on the page"
wait_for "Posts" 5 > /dev/null || fail "no posts about the place"
screenshot place-full
check_alive "place page full"

adb shell input keyevent KEYCODE_BACK
sleep 2
dump=$(dump_ui after-back)
if centre_of "$dump" "Conditorei schober" > /dev/null; then fail "back did not close the place page"; fi
centre_of "$dump" "Show my location" > /dev/null || fail "back did not return to the map"
check_alive "after back"

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
print("requests:", len(lines), sorted(set(paths)))
PY

check_strict_mode "place page"
echo "place page test passed"
