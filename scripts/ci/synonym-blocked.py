#!/usr/bin/env python3
"""The app with every Synonym-run host unreachable, on a running emulator (CI: android.yml).

The workflow starts the emulator with a DNS server on the runner (dnsmasq) that answers NXDOMAIN
for *.pubky.app, *.pubky.org, *.synonym.to and *.coder.riginode.xyz (our development hosts), which
covers Synonym's homeservers, Pkarr relays and sign-in relay; in the `openfreemap` case it also
blocks tiles.openfreemap.org, the first map style. This script first checks from inside the phone
that the block works, then:

- seeds a signed-in account (debug builds read files/ci-fixture.json): a stored session made by
  core/examples/offline_session_fixture.rs, a cached profile name, two tags and a saved place;
- starts the app: the map draws (in the `openfreemap` case from the next style, VersaTiles, whose
  credits show, with no Retry notice);
- the session cannot be restored, so the banner names the missing part ("Can't reach your
  homeserver") while the map stays usable;
- Settings and the Account page show the cached name, tags and saved place;
- (`synonym` case) a place saved now waits in the outbox, which keeps a JobScheduler job; with the
  app closed (off screen, its process killed) Android runs the job again and starts the app's
  process for it (OutboxJobService), as it does for a closed app whose job is due.

Usage: synonym-blocked.py <apk> <session fixture json> <synonym|openfreemap> <out dir>
"""
import json
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from emulator import PKG, Device, Failure, log  # noqa: E402

BLOCKED = ["pkarr.pubky.app", "pkarr.pubky.org", "httprelay.pubky.app", "homeserver.pubky.app", "pubky.app",
           "synonym.to", "8092--main--pinakes--gil.coder.riginode.xyz"]
OPEN = ["tiles.versatiles.org"]
NAME = "Fixture Rider"
TAGS = [("https://www.openstreetmap.org/node/240109189", "great coffee"),
        ("geo:47.37689,8.54169", "bench with a view")]
SAVED = "https://www.openstreetmap.org/way/48210334"
WAITING_PLACE = "geo:47.3769,8.5417?q=Waiting%20write"
# As dumpsys and ActivityManager name it (in full or short form).
JOB_SERVICE = "OutboxJobService"
# `dumpsys jobscheduler <pkg>`: a registered job ("JOB #u0a190/1: 3f2a1b to.pubky.maps/.android.OutboxJobService"),
# and a start of it in the job history ("START: #u0a190/1 to.pubky.maps/.android.OutboxJobService").
REGISTERED = re.compile(r"^\s*JOB #u\d+a\d+/1: \S+ \S*OutboxJobService", re.M)
STARTED = re.compile(r"START: #u\d+a\d+/1 \S*OutboxJobService")
BANNER = "Can't reach your homeserver"


def resolves(dev, host):
    out = dev.shell(f"ping -c 1 -W 2 {host}", timeout=30)
    if "unknown host" in out:
        return False
    return f"PING {host}" in out


def main():
    apk, fixture_file, case, out = sys.argv[1], Path(sys.argv[2]), sys.argv[3], sys.argv[4]
    dev = Device(out)
    blocked = BLOCKED + (["tiles.openfreemap.org"] if case == "openfreemap" else [])
    allowed = OPEN + ([] if case == "openfreemap" else ["tiles.openfreemap.org"])

    # The block itself, from inside the phone: blocked names do not resolve, the others do.
    deadline = time.time() + 120
    while time.time() < deadline and not all(resolves(dev, h) for h in allowed):
        time.sleep(3)
    for host in allowed:
        if not resolves(dev, host):
            dev.fail(f"{host} does not resolve on the phone: no network, the test would prove nothing")
    for host in blocked:
        if resolves(dev, host):
            dev.fail(f"{host} still resolves on the phone: the Synonym block does not work")
    log(f"blocked on the phone: {', '.join(blocked)}; open: {', '.join(allowed)}")

    session = json.loads(fixture_file.read_text())
    seed = {
        "secret": session["secret"],
        "publicKey": session["publicKey"],
        "name": NAME,
        "tags": [{"place": p, "label": l} for p, l in TAGS],
        "bookmarks": [{"place": SAVED}],
    }
    dev.install(apk)
    dev.shell(f"am force-stop {PKG}")
    dev.write_app_file("ci-fixture.json", json.dumps(seed).encode())
    dev.clear_logs()
    dev.start_app()
    if dev.wait_for("Show my location", 60) is None:
        dev.fail("the map screen did not appear")
    dev.check_alive("map screen")

    dev.wait_for_map("map")
    texts = dev.texts(dev.dump("map-credits"))
    credits = " ".join(texts)
    if case == "openfreemap":
        if "VersaTiles" not in credits or "OpenFreeMap" in credits:
            dev.fail(f"with OpenFreeMap blocked the map should draw from VersaTiles; credits shown: {credits!r}")
        log("the map draws from VersaTiles, the next style in the list")
    elif "OpenFreeMap" not in credits:
        dev.fail(f"the map should draw from OpenFreeMap (not a Synonym host); credits shown: {credits!r}")

    # The session cannot be restored (homeserver lookup and every Synonym host blocked).
    if dev.wait_for(BANNER, 150) is None:
        dev.fail(f"no degraded banner {BANNER!r} on the map")
    dev.screenshot("map-degraded")
    dev.check_alive("degraded banner")
    log("the banner names the missing part; the map stays")

    dev.tap("Settings")
    if dev.wait_for(NAME, 20) is None:
        dev.fail("Settings does not show the signed-in user from the cache")
    if dev.wait_for("homeserver unreachable", 5, exact=False) is None:
        dev.fail("the Settings account row does not say the homeserver is unreachable")
    dev.screenshot("settings")
    dev.tap(NAME)
    if dev.wait_for("Your homeserver can't be reached. Showing what this phone saved.", 20) is None:
        dev.fail("the Account page does not say it shows saved data")
    nodes = dev.dump("account")
    shown = dev.texts(nodes)
    for _, label in TAGS:
        if label not in shown:
            dev.fail(f"the cached tag {label!r} is not on the Account page")
    # No name is stored for the saved place here, so it reads as its link in words.
    if "OpenStreetMap way 48210334" not in shown:
        dev.fail("the cached saved place is not on the Account page")
    dev.screenshot("account")
    dev.check_alive("account page")
    dev.back()
    dev.back()
    if dev.wait_for("Show my location", 15) is None:
        dev.fail("back did not return to the map")
    dev.check_alive("back on the map")
    if case == "synonym":
        outbox_wakes_the_closed_app(dev)
    dev.check_strict_mode(f"synonym-blocked, {case}")
    log(f"synonym-blocked ({case}) passed")


def app_pid(dev):
    return dev.shell(f"pidof {PKG}").strip()


def outbox_wakes_the_closed_app(dev):
    """A write that cannot be sent keeps a job; Android runs it, and so starts the app, with the app closed."""
    dev.open_link(WAITING_PLACE)
    dev.tap("Save", timeout=30)
    if dev.wait_for("Saved", 15) is None:
        dev.fail("the place page does not show the place as saved")
    dev.screenshot("saved-waiting")
    deadline = time.time() + 30
    jobs = ""
    while time.time() < deadline and not REGISTERED.search(jobs):
        time.sleep(2)
        jobs = dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)
    (dev.out / "jobs.txt").write_text(jobs)
    if not REGISTERED.search(jobs):
        dev.fail("a write waits for the homeserver but no outbox job is registered (jobs.txt)")
    log("the waiting write keeps an outbox job")

    # Closed: off the screen, then the process killed (as Android does to a background app).
    dev.shell("input keyevent KEYCODE_HOME")
    time.sleep(2)
    before = app_pid(dev)
    if not before:
        dev.fail("the app's process is not running before it is killed")
    starts_before = len(STARTED.findall(dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)))
    dev.shell(f"run-as {PKG} kill {before}")
    # A killed run of the job is run again after its backoff (30 s, doubling); a job that had not run
    # yet, or whose run had ended, runs when its backoff is over.
    deadline = time.time() + 240
    after = ""
    while time.time() < deadline:
        after = app_pid(dev)
        if after and after != before:
            break
        time.sleep(3)
    if not after or after == before:
        dev.fail(f"the app was not started again for its waiting write (pid before {before}, after {after!r})")
    # ActivityManager names why it started the process: "Start proc <pid>:<pkg>/... for service {<job
    # service>}", and the events log "am_proc_start: [<user>,<pid>,<uid>,<pkg>,service,{<job service>}]".
    log_lines = dev.adb("logcat", "-d", "-b", "all", check=False, timeout=120).splitlines()
    reasons = [line for line in log_lines
               if JOB_SERVICE in line and (f"Start proc {after}:" in line or f",{after}," in line)]
    (dev.out / "outbox-start.txt").write_text("\n".join(reasons) + "\n")
    if not reasons:
        dev.fail(f"process {after} was not started for the outbox job (outbox-start.txt, logcat.txt)")
    log(f"killed with a write waiting, the app was started again (pid {before} -> {after}) for its outbox job")
    jobs = dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)
    (dev.out / "jobs-after.txt").write_text(jobs)
    if len(STARTED.findall(jobs)) <= starts_before:
        dev.fail(f"no new run of the outbox job after the kill ({starts_before} before; jobs-after.txt)")
    if not REGISTERED.search(jobs):
        dev.fail("the outbox job is gone while the write still waits (jobs-after.txt)")
    dev.start_app()
    if dev.wait_for("Show my location", 60) is None:
        dev.fail("the map screen did not appear after the outbox job ran")
    dev.check_alive("map after the outbox job")


if __name__ == "__main__":
    try:
        main()
    except Failure as e:
        print(f"FAIL {e}", flush=True)
        sys.exit(1)
