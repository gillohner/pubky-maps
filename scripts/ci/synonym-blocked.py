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
- (`synonym` case) a place saved in airplane mode waits in the outbox, which keeps a JobScheduler
  job; with the app closed (off screen, its process killed) and the network back, Android starts the
  app's process for that job (OutboxJobService). The homeserver stays unreachable, so no run can
  send: the app is closed again and its process killed during a run, and Android starts the app
  once more (for the run's guard job, which schedules the outbox job anew) and runs the job.

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
# An entry of the job history: "-5m10s376ms   START: #u0a209/1 to.pubky.maps/.android.OutboxJobService" (how
# long ago), or "... STOP: #u0a209/1 ... unexpectedly disconnected" for a run whose process died.
HISTORY = re.compile(r"^\s*-(?P<age>[\dmshd]+)\s+(?P<kind>START|STOP): #u\d+a\d+/(?P<id>\d+) \S*OutboxJobService(?P<rest>.*)$",
                     re.M)
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


def airplane(dev, on):
    dev.shell(f"cmd connectivity airplane-mode {'enable' if on else 'disable'}")
    want = "1" if on else "0"
    deadline = time.time() + 30
    while time.time() < deadline and dev.shell("settings get global airplane_mode_on").strip() != want:
        time.sleep(1)
    if dev.shell("settings get global airplane_mode_on").strip() != want:
        dev.fail(f"airplane mode did not turn {'on' if on else 'off'}")


def outbox_wakes_the_closed_app(dev):
    """A write made without a network keeps a job; when the network is back, Android starts the closed app for it."""
    airplane(dev, True)
    # To the app itself: on this image Google Maps also opens geo: links, so a plain VIEW asks which app.
    started = dev.adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"'{WAITING_PLACE}'",
                      "-p", PKG, check=False)
    if "Error" in started:
        dev.fail(f"the geo: link did not reach the app: {started.strip()}")
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
        dev.fail("a write waits for a connection but no outbox job is registered (jobs.txt)")
    log("the waiting write keeps an outbox job")

    # Closed: off the screen, then the process killed (as Android does to a background app).
    dev.shell("input keyevent KEYCODE_HOME")
    time.sleep(2)
    before = app_pid(dev)
    if not before:
        dev.fail("the app's process is not running before it is killed")
    starts_before = len(STARTED.findall(dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)))
    dev.shell(f"run-as {PKG} kill {before}")
    deadline = time.time() + 20
    while time.time() < deadline and app_pid(dev):
        time.sleep(1)
    if app_pid(dev):
        dev.fail(f"the app's process {before} did not end")
    log("the app is closed; the network comes back")

    airplane(dev, False)
    deadline = time.time() + 180
    after = ""
    while time.time() < deadline:
        after = app_pid(dev)
        if after:
            break
        time.sleep(3)
    jobs = dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)
    (dev.out / "jobs-after.txt").write_text(jobs)
    (dev.out / "standby-bucket.txt").write_text(dev.shell(f"am get-standby-bucket {PKG}") + "\n")
    if not after:
        dev.fail("with the network back, the closed app was not started for its waiting write (jobs-after.txt)")
    # ActivityManager names why it started the process: "Start proc <pid>:<pkg>/... for service {<job
    # service>}", and the events log "am_proc_start: [<user>,<pid>,<uid>,<pkg>,service,{<job service>}]".
    log_lines = dev.adb("logcat", "-d", "-b", "all", check=False, timeout=120).splitlines()
    reasons = [line for line in log_lines
               if JOB_SERVICE in line and (f"Start proc {after}:" in line or f",{after}," in line)]
    (dev.out / "outbox-start.txt").write_text("\n".join(reasons) + "\n")
    if not reasons:
        dev.fail(f"process {after} was not started for the outbox job (outbox-start.txt, logcat.txt)")
    if len(STARTED.findall(jobs)) <= starts_before:
        dev.fail(f"no new run of the outbox job after the network came back ({starts_before} before; jobs-after.txt)")
    log(f"with the network back, Android started the closed app (pid {before} -> {after}) for its outbox job")
    dev.start_app()
    if dev.wait_for("Show my location", 60) is None:
        dev.fail("the map screen did not appear after the outbox job ran")
    dev.check_alive("map after the outbox job")
    outbox_retries_the_closed_app(dev)


def history(jobs, kind, job_id=1):
    """How long ago (s) each START or STOP of the outbox job (or its guard, job 2) in the job history was."""
    ages = []
    for m in HISTORY.finditer(jobs):
        if m.group("kind") == kind and m.group("id") == str(job_id):
            parts = dict((unit, int(n)) for n, unit in re.findall(r"(\d+)(ms|d|h|m|s)", m.group("age")))
            ages.append((m.group("rest"), parts.get("d", 0) * 86400 + parts.get("h", 0) * 3600
                         + parts.get("m", 0) * 60 + parts.get("s", 0) + parts.get("ms", 0) / 1000))
    return ages


def active_run(jobs):
    """Whether `dumpsys jobscheduler` lists a run of the outbox job under "Active jobs"."""
    active = jobs.split("Active jobs:", 1)[1].split("Idle contexts", 1)[0] if "Active jobs:" in jobs else ""
    return re.search(r"Slot #\d+.*#u\d+a\d+/1 \S*OutboxJobService", active) is not None


def outbox_retries_the_closed_app(dev):
    """The homeserver stays unreachable: a run of the job killed with the app closed is run again."""
    # Every Synonym host is blocked, so no run can send and each one lasts its whole minute; Android
    # runs the job again after a backoff (30 s, doubling). Wait until a run is going: the bug this
    # checks is a process that dies during a run.
    deadline = time.time() + 150
    jobs = ""
    while time.time() < deadline and not active_run(jobs):
        time.sleep(2)
        jobs = dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)
    (dev.out / "jobs-retry-before.txt").write_text(jobs)
    if not active_run(jobs):
        dev.fail("no run of the outbox job is going while the write waits (jobs-retry-before.txt)")
    if "expeditedQuotaApproved: true" not in jobs:
        dev.fail("the outbox job is not an expedited one (jobs-retry-before.txt)")
    dev.shell("input keyevent KEYCODE_HOME")
    before = app_pid(dev)
    if not before:
        dev.fail("the app's process is not running before it is killed again")
    dev.shell(f"run-as {PKG} kill {before}")
    killed = time.time()
    deadline = killed + 20
    while time.time() < deadline and app_pid(dev) == before:
        time.sleep(1)
    if app_pid(dev) == before:
        dev.fail(f"the app's process {before} did not end")
    log("the app is closed during a run of its outbox job, while its write still cannot be sent")
    # Android starts the app again for the run's guard job (after 90 s), which schedules the outbox job
    # anew; the job's state is kept every 30 s, so a missed run can be read from the artifacts.
    after = ""
    restarted = []
    snapshot = 0
    while time.time() < killed + 300:
        if time.time() >= killed + 30 * snapshot:
            jobs = dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)
            (dev.out / f"jobs-retry-{30 * snapshot:03d}s.txt").write_text(jobs)
            snapshot += 1
        after = app_pid(dev)
        if after and after != before:
            jobs = dev.shell(f"dumpsys jobscheduler {PKG}", timeout=60)
            restarted = [age for _, age in history(jobs, "START") if age < time.time() - killed]
            if restarted:
                break
        time.sleep(3)
    (dev.out / "jobs-retry-after.txt").write_text(jobs)
    (dev.out / "standby-bucket.txt").write_text(dev.shell(f"am get-standby-bucket {PKG}") + "\n")
    since_kill = time.time() - killed
    died = [rest for rest, age in history(jobs, "STOP") if age < since_kill and "unexpectedly disconnected" in rest]
    if not died:
        dev.fail("the kill did not end a run of the outbox job ('unexpectedly disconnected'; jobs-retry-after.txt)")
    if not after or after == before:
        dev.fail(f"the killed app was not started again for its waiting write (pid {before}; jobs-retry-*.txt)")
    if not restarted:
        dev.fail(f"no run of the outbox job since the kill, {since_kill:.0f} s ago (jobs-retry-after.txt)")
    log_lines = dev.adb("logcat", "-d", "-b", "all", check=False, timeout=120).splitlines()
    reasons = [line for line in log_lines
               if JOB_SERVICE in line and (f"Start proc {after}:" in line or f",{after}," in line)]
    (dev.out / "outbox-retry.txt").write_text("\n".join(reasons) + "\n")
    if not reasons:
        dev.fail(f"process {after} was not started for the outbox job (outbox-retry.txt, logcat.txt)")
    if not REGISTERED.search(jobs):
        dev.fail("the outbox job is gone while the write still waits (jobs-retry-after.txt)")
    log(f"Android started the killed app again (pid {before} -> {after}) and ran its outbox job, "
        f"about {since_kill - min(restarted):.0f} s after the kill")


if __name__ == "__main__":
    try:
        main()
    except Failure as e:
        print(f"FAIL {e}", flush=True)
        sys.exit(1)
