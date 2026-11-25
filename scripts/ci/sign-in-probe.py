#!/usr/bin/env python3
"""The Pubky Ring sign-in on a running emulator, up to the link and back (CI: android.yml).

Pubky Ring cannot run in CI, so the test plays both other sides: a fixture sign-in relay on the
runner (fixture_relay.py, reached as the phone's 127.0.0.1 through adb reverse, set as the app's
sign-in relay in its settings file) and Ring's return links (opened with `am start`). It checks:

- Settings > Account opens the sign-in page; "Sign in with Pubky Ring" starts a sign-in and, with
  no Ring on the phone, shows a QR code. The QR code holds a pubkyauth:// link on the fixture relay
  whose return links are pubkymaps://auth/...?nonce=<n>, and the app listens on that relay.
- Ring's success link brings the app back and shows "Finishing the sign-in".
- The process is killed while the user is in Ring; a new start shows the same sign-in (same link),
  continued from its stored state.
- Ring's cancel link, opening the killed app, ends the sign-in with Ring's message and the app
  stops listening.
- A return link with another nonce changes nothing.

The link holds the flow's relay secret, so it is never printed or saved; only its parts are checked.

Usage: sign-in-probe.py <apk> <out dir>
"""
import json
import sys
import time
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from emulator import PKG, Device, Failure, decode_qr, log  # noqa: E402
from fixture_relay import FixtureRelay  # noqa: E402

PORT = 18490


def settings_with_relay(relay_url):
    """The app's settings document with the fixture relay as the only sign-in relay."""
    return json.dumps({
        "schemaVersion": 1,
        "servers": {"auth_relay": [{"name": "Fixture relay", "url": relay_url}]},
    }).encode()


def link_parts(link):
    if not link.startswith("pubkyauth://"):
        raise Failure("the QR code is not a pubkyauth:// link")
    query = urllib.parse.parse_qs(urllib.parse.urlsplit(link).query)
    return {k: v[0] for k, v in query.items()}


def read_qr(dev, name):
    node = dev.wait_for("QR code of the sign-in link", 30)
    if node is None:
        dev.fail("no QR code on the sign-in page")
    time.sleep(1)
    link = decode_qr(dev.screenshot(name))
    if link is None:
        dev.fail(f"the QR code on {name}.png does not decode")
    return link


def main():
    apk, out = sys.argv[1], sys.argv[2]
    dev = Device(out)
    relay = FixtureRelay(PORT).start()
    dev.adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    dev.install(apk)
    # The settings file must be in place before the app first reads it.
    dev.shell(f"am force-stop {PKG}")
    dev.adb("shell", f"run-as {PKG} sh -c 'mkdir -p files/datastore && cat > files/datastore/settings.json'",
            stdin=settings_with_relay(relay.url))
    dev.clear_logs()
    dev.start_app()
    if dev.wait_for("Show my location", 60) is None:
        dev.fail("the map screen did not appear")

    dev.tap("Settings")
    dev.tap("Sign in with Pubky Ring", timeout=20)
    if dev.wait_for("Sign in with Pubky", 15) is None:
        dev.fail("the account page did not open")
    dev.screenshot("account")
    dev.tap("Sign in with Pubky Ring")
    if dev.wait_for("Pubky Ring is not on this phone", 20, exact=False) is None:
        dev.fail("no 'Ring is not on this phone' text: the emulator has no Ring, the QR code should show")
    link = read_qr(dev, "sign-in-qr")
    parts = link_parts(link)
    log(f"QR link: pubkyauth, relay {parts.get('relay')}, return links {sorted(k for k in parts if k.startswith('x-'))}")
    if parts.get("relay", "").rstrip("/") != relay.url:
        dev.fail(f"the link's relay is {parts.get('relay')!r}, not the fixture relay {relay.url}")
    nonce = None
    for outcome in ("success", "error", "cancel"):
        back = parts.get(f"x-{outcome}", "")
        if not back.startswith(f"pubkymaps://auth/{outcome}?nonce="):
            dev.fail(f"the link's x-{outcome} is not a pubkymaps://auth/{outcome} return link")
        n = back.split("nonce=", 1)[1]
        if nonce not in (None, n):
            dev.fail("the return links have different nonces")
        nonce = n

    # The core listens on the fixture relay for Ring's approval.
    deadline = time.time() + 30
    while time.time() < deadline and not relay.channel_polls():
        time.sleep(1)
    channels = set(relay.channel_polls())
    if not channels:
        dev.fail(f"the app never listened on the fixture relay ({len(relay.snapshot())} requests)")
    log(f"the app listens on the fixture relay ({len(relay.channel_polls())} polls, {len(channels)} channel)")

    # A return link of another sign-in is ignored.
    dev.open_link("pubkymaps://auth/cancel?nonce=not-this-one")
    time.sleep(3)
    if dev.find(dev.dump("foreign"), "QR code of the sign-in link") is None:
        dev.fail("a return link with another nonce changed the sign-in")

    # Ring approved and sent the user back.
    dev.open_link(f"pubkymaps://auth/success?nonce={nonce}")
    if dev.wait_for("Finishing the sign-in…", 20) is None:
        dev.fail("Ring's success link did not show 'Finishing the sign-in'")
    dev.screenshot("sign-in-returned")
    dev.check_alive("after Ring's success link")

    # The user goes back to Ring and Android kills the app meanwhile.
    dev.shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(2)
    dev.shell(f"am kill {PKG}")
    time.sleep(2)
    if dev.shell(f"pidof {PKG}").strip():
        dev.shell(f"am force-stop {PKG}")
    log("the app was killed during the sign-in")

    # A new start continues the same sign-in.
    dev.start_app()
    dev.tap("Settings", timeout=60)
    dev.tap("Signing in…", timeout=20)
    resumed = read_qr(dev, "sign-in-resumed")
    if resumed != link:
        dev.fail("after the kill the sign-in shows a different link: it did not continue the stored one")
    log("after the kill the same sign-in continues")

    # Killed again; Ring's cancel link starts the app.
    dev.shell("input", "keyevent", "KEYCODE_HOME")
    time.sleep(2)
    dev.shell(f"am kill {PKG}")
    time.sleep(2)
    if dev.shell(f"pidof {PKG}").strip():
        dev.shell(f"am force-stop {PKG}")
    polls_before = len(relay.channel_polls())
    dev.open_link(f"pubkymaps://auth/cancel?nonce={nonce}")
    if dev.wait_for("The sign-in was cancelled in Pubky Ring.", 30) is None:
        dev.fail("Ring's cancel link did not end the sign-in on the account page")
    dev.screenshot("sign-in-cancelled")
    dev.check_alive("after Ring's cancel link")
    time.sleep(12)
    late = len(relay.channel_polls()) - polls_before
    # At most the poll that was running when the link came.
    if late > 2:
        dev.fail(f"the app kept listening on the relay after the cancel ({late} more polls)")

    # Nothing of the flow is left: a restart is signed out.
    dev.shell(f"am force-stop {PKG}")
    dev.start_app()
    dev.tap("Settings", timeout=60)
    if dev.wait_for("Sign in with Pubky Ring", 20) is None:
        dev.fail("after the cancel and a restart the account is not signed out")
    left = dev.shell(f"run-as {PKG} ls no_backup/secrets").split()
    if "pending-sign-in.bin" in left:
        dev.fail("the sign-in's stored state is still there after the cancel")
    dev.save_logcat()
    (Path(out) / "fixture-relay.json").write_text(json.dumps([{"method": r["method"], "path": r["path"]} for r in relay.snapshot()], indent=1))
    log("sign-in probe passed")


if __name__ == "__main__":
    try:
        main()
    except Failure as e:
        print(f"FAIL {e}", flush=True)
        sys.exit(1)
