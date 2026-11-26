#!/usr/bin/env bash
# Map links on a running emulator, after emulator-smoke.sh installed the app: a geo: link, a Google
# Maps link and a link shared as text each open Pubky Maps with the place's card (name and point).
# Screenshots of each go to <out dir>. Pinakes may be unreachable from the emulator: the card then
# comes from the link text, which is what is checked.
# Usage: emulator-links.sh <out dir>
set -euo pipefail

out=$1
pkg=to.pubky.maps
mkdir -p "$out"
# shellcheck source=scripts/ci/emulator-lib.sh
source "$(cd "$(dirname "$0")" && pwd)/emulator-lib.sh"

# Sends an intent to the app; the arguments are `am start` options (quoted for the device shell).
send_intent() {
    local started
    started=$(adb shell am start -W "$@" -p "$pkg" 2>&1) || true
    echo "$started"
    if grep -q "Error" <<< "$started"; then fail "the intent did not reach the app: $started"; fi
}

# Waits for the place card titled $2 with the detail line $3, then saves screenshot $1.
expect_card() {
    wait_for "$2" 60 > /dev/null || fail "no place card \"$2\" ($1)"
    wait_for "$3" 5 > /dev/null || fail "the card \"$2\" lacks \"$3\" ($1)"
    # The camera moves to the place and the tiles there load.
    sleep 4
    screenshot "$1"
    check_alive "$1"
    local xy
    xy=$(wait_for "Close" 5) || fail "no Close on the card ($1)"
    adb shell input tap $xy
}

# A log buffer large enough to keep StrictMode's reports from the start until the end.
adb logcat -G 16M || true
adb logcat -c || true
adb logcat -b crash -c || true
# The first link starts the app cold; the next ones reach the running app (singleTask).
adb shell am force-stop "$pkg"

send_intent -a android.intent.action.VIEW -d "'geo:47.37,8.54?q=Test'"
# q=Test is a search near the point (as in Google Maps): the map moves there, no pin, no coordinates.
expect_card link-geo "Test" "From a map link"

google='https://www.google.com/maps/place/Z%C3%BCrich+HB/@47.3721439,8.5351212,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x0!8m2!3d47.3778579!4d8.5403226'
send_intent -a android.intent.action.VIEW -d "'$google'"
expect_card link-google "Zürich HB" "47.37786, 8.54032 · From a Google Maps link"

send_intent -a android.intent.action.SEND -t text/plain \
    --es android.intent.extra.TEXT "'https://osmand.net/map?pin=52.51628,13.37771'" \
    --es android.intent.extra.SUBJECT "'Brandenburger Tor'"
expect_card link-shared "Brandenburger Tor" "52.51628, 13.37771 · From an OsmAnd link"

check_strict_mode "map links"
echo "map links open on the map"
