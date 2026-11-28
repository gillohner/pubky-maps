#!/usr/bin/env python3
"""The app updating itself, on an emulator (CI: android.yml, update-probe).

Two release builds (R8 on) of the `direct` APK, signed with one throwaway key and built with
-Ppubkymaps.updateProbe=true, a throwaway feed key and the feed http://127.0.0.1:18480/update-edge.json:
build N (versionCode 1000) and N+1 (1001). The feed for N+1 is written and signed by
scripts/release/update-feed.py, as a release does, and served here on the runner; `adb reverse`
makes it the phone's localhost. Nothing reaches GitHub.

1. Tampered feed: N starts and its start-up check gets the feed with one field changed after
   signing. No update is offered; Settings shows why.
2. Tampered APK: the feed is right, but the APK served has one byte changed. "Check for updates",
   Install: the app must refuse the download, say why, and Android must keep N.
2b. Restart the same day: the app is stopped and started again; the update found in 2 is ready at
   once (the map's Settings button has its dot, Settings its card), read again from its kept signed
   feed and confirmed by the start-up check, and the map does not offer it again.
3. Update: automatic checks are turned off (data that must survive), the right APK is served.
   Install; "Install unknown apps" is off, so the app explains and opens its settings page; the
   setting is allowed (appops), back to the app, which hands the APK to Android; Android's
   confirmation is accepted. N+1 must be installed, open from Android's "App installed" dialog, start
   with automatic checks still off, drop the kept feed (it is that version: no dot), and find itself
   the newest version.

Usage: update-probe.py <apk N> <apk N+1> <feed key PEM> <feed public key file> <out dir>
"""
import hashlib
import http.server
import json
import re
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

PKG = "to.pubky.maps"
PORT = 18480
OLD_CODE, NEW_CODE = 1000, 1001
NEW_NAME = "0.0.1-probe.2"
apk_old, apk_new, feed_key, feed_pub, out = map(Path, sys.argv[1:6])
out.mkdir(parents=True, exist_ok=True)
served = []
state = {"feed": "tampered", "apk": "tampered"}


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


def adb(*args, check=True, timeout=180):
    r = subprocess.run(["adb", *args], capture_output=True, text=True, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)}: {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def sh(*args, timeout=30):
    """An adb shell command whose failure is an answer, not an error."""
    try:
        r = subprocess.run(["adb", "shell", *args], capture_output=True, text=True, timeout=timeout)
        return r.stdout + r.stderr
    except subprocess.TimeoutExpired:
        return ""


def settle(steady=30, limit=600):
    """Boot complete, the package service answers, and system_server kept its pid for `steady` s."""
    adb("wait-for-device", check=False, timeout=300)
    deadline = time.time() + limit
    pid, since = None, time.time()
    while time.time() < deadline:
        booted = sh("getprop", "sys.boot_completed").strip() == "1"
        now = sh("pidof", "system_server").strip() or None
        pm_ok = "package:android" in sh("pm", "path", "android")
        if now != pid:
            pid, since = now, time.time()
        if booted and pm_ok and pid and time.time() - since >= steady:
            return
        time.sleep(3)
    log(f"the system did not settle in {limit} s; going on")


def version_code():
    m = re.search(r"versionCode=(\d+)", sh("dumpsys", "package", PKG))
    return int(m.group(1)) if m else None


def build_feeds(work):
    """update-edge.json for N+1 as a release writes it, and a copy changed after signing."""
    r = subprocess.run([sys.executable, "-I", str(Path(__file__).parent.parent / "release" / "update-feed.py"),
                        "--apk", str(apk_new), "--channel", "edge", "--url", f"http://127.0.0.1:{PORT}/pubky-maps.apk",
                        "--package", PKG, "--version-code", str(NEW_CODE), "--version-name", NEW_NAME,
                        "--min-sdk", "26", "--signing-key", str(feed_key), "--public-key", str(feed_pub),
                        "--out", str(work / "update-edge.json")], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"update-feed.py: {r.stderr}")
    good = (work / "update-edge.json").read_bytes()
    outer = json.loads(good)
    feed = json.loads(outer["feed"])
    assert feed["apk"]["sha256"] == hashlib.sha256(apk_new.read_bytes()).hexdigest(), feed
    outer["feed"] = outer["feed"].replace(NEW_NAME, "0.0.1-probe.9")
    tampered = json.dumps(outer, indent=2).encode()
    (out / "update-edge.json").write_bytes(good)
    (out / "update-edge-tampered.json").write_bytes(tampered)
    return good, tampered


class Fixture(http.server.BaseHTTPRequestHandler):
    feeds = {}

    def do_GET(self):
        served.append({"path": self.path, **state, "at": time.strftime("%H:%M:%S")})
        if self.path == "/update-edge.json":
            body, kind = self.feeds[state["feed"]], "application/json"
        elif self.path == "/pubky-maps.apk":
            body = apk_new.read_bytes()
            if state["apk"] == "tampered":
                i = len(body) // 2
                body = body[:i] + bytes([body[i] ^ 0xFF]) + body[i + 1:]
            kind = "application/vnd.android.package-archive"
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("content-type", kind)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


def ui_nodes():
    sh("rm", "-f", "/sdcard/ui.xml")
    sh("uiautomator", "dump", "/sdcard/ui.xml", timeout=60)
    xml = adb("exec-out", "cat", "/sdcard/ui.xml", check=False)
    return re.findall(r"<node [^>]*>", xml)


def center(node):
    x1, y1, x2, y2 = map(int, re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node).groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def shot(name):
    png = subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout
    (out / f"{name}.png").write_bytes(png)


def find(pred, timeout, what):
    deadline = time.time() + timeout
    while time.time() < deadline:
        nodes = ui_nodes()
        anr = next((n for n in nodes if re.search(r"isn\S{0,6}t responding", n)), None)
        if anr:
            if "Pubky Maps" in anr:
                shot("app-not-responding")
                raise RuntimeError("Pubky Maps is not responding")
            wait = next((n for n in nodes if re.search(r'text="Wait"', n)), None)
            if wait:
                adb("shell", "input", "tap", *map(str, center(wait)))
                log("another app was not responding: tapped Wait")
                time.sleep(2)
                continue
        hit = next((n for n in nodes if pred(n)), None)
        if hit:
            return hit
        time.sleep(2)
    shot("missing-" + re.sub(r"[^a-z0-9]+", "-", what.lower()).strip("-"))
    raise RuntimeError(f"timed out waiting for {what}")


def scroll_to(pred, what, swipes=6):
    """Scrolls the Settings list down until a node matching `pred` is well inside the screen (About
    is below the fold on a phone; a row cut by the bottom edge would be tapped on the gesture bar),
    then waits for it like find."""
    for _ in range(swipes):
        nodes = ui_nodes()
        bottom = max((int(b) for n in nodes for b in re.findall(r'bounds="\[\d+,\d+\]\[\d+,(\d+)\]"', n)), default=0)
        hit = next((n for n in nodes if pred(n)), None)
        if hit is not None and center(hit)[1] < bottom * 0.8:
            break
        adb("shell", "input", "swipe", "540", "1800", "540", "900", "300")
        time.sleep(1)
    return find(pred, 60, what)


def has(text):
    """A node whose text or content description contains `text` (Compose merges a row's texts)."""
    return lambda n: re.search(r'(text|content-desc)="[^"]*' + re.escape(text), n) is not None


def is_(text):
    return lambda n: re.search(r'(text|content-desc)="' + re.escape(text) + '"', n) is not None


def tap(node, what):
    x, y = center(node)
    adb("shell", "input", "tap", str(x), str(y))
    log(f"tapped {what} at {x},{y}")


def launch():
    adb("shell", "monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1")


def main():
    work = Path(tempfile.mkdtemp())
    good, tampered = build_feeds(work)
    Fixture.feeds = {"good": good, "tampered": tampered}
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Fixture)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    report = {"android": sh("getprop", "ro.build.version.release").strip(), "steps": {}}
    failures = []
    settle()
    adb("install", "-r", str(apk_old), timeout=600)
    adb("reverse", f"tcp:{PORT}", f"tcp:{PORT}")
    logfile = out / "logcat.txt"
    adb("logcat", "-c")
    lc = subprocess.Popen(["adb", "logcat", "-v", "time", "PubkyMapsUpdates:V", "PackageInstaller:V",
                           "AndroidRuntime:E", "ActivityManager:I", "*:S"],
                          stdout=open(logfile, "w"), stderr=subprocess.STDOUT)

    def logtext():
        return logfile.read_text(errors="replace")

    def wait_log(pattern, timeout):
        deadline = time.time() + timeout
        while time.time() < deadline:
            m = re.search(pattern, logtext())
            if m:
                return m
            time.sleep(2)
        return None

    def confirm_update():
        """Taps Android's Update once its dialog has settled (same place in two dumps 2 s apart)."""
        def pred(n):
            return ("packageinstaller" in n and 'enabled="true"' in n
                    and re.search(r'text="(Update|Install|UPDATE|INSTALL)"', n))
        node = find(pred, 240, "Android's install confirmation")
        for _ in range(10):
            time.sleep(2)
            again = find(pred, 60, "Android's install confirmation (again)")
            if center(again) == center(node):
                break
            node = again
        shot("3-android-confirm")
        tap(again, "Android's Update")

    try:
        if version_code() != OLD_CODE:
            failures.append(f"build N installed as versionCode {version_code()}, not {OLD_CODE}")

        # 1. A feed changed after signing: refused, nothing offered, Settings says why.
        launch()
        refused = wait_log(r"updates: automatic check: refused: BadSignature", 180)
        report["steps"]["tampered_feed_refused"] = bool(refused)
        if not refused:
            failures.append("tampered feed: the start-up check did not refuse it")
        find(is_("Settings"), 120, "the map screen")
        time.sleep(3)
        nodes = ui_nodes()
        if not any(is_("Settings")(n) for n in nodes):
            failures.append("tampered feed: the screen could not be read to check that nothing was offered")
        elif any(has("Update available")(n) or has("Update ready")(n) for n in nodes):
            failures.append("tampered feed: an update was offered")
        tap(find(is_("Settings"), 120, "the Settings button"), "Settings")
        scroll_to(has("not signed with the Pubky Maps key"), "the refused feed's reason in Settings")
        shot("1-tampered-feed")

        # 2. The right feed, a changed APK: the download is refused and N stays.
        state["feed"] = "good"
        tap(find(has("Check for updates"), 30, "Check for updates"), "Check for updates")
        find(has("Update available"), 120, "the update offer")
        shot("2-offer")
        tap(find(is_("Install"), 30, "Install"), "Install")
        find(has("Update not installed"), 240, "the refusal of the tampered APK")
        shot("2-tampered-apk")
        refusal = wait_log(r"updates: not installed: .*SHA-256", 30)
        report["steps"]["tampered_apk_refused"] = bool(refusal)
        if not refusal:
            failures.append("tampered APK: no SHA-256 refusal in the log")
        if "handed to the installer" in logtext():
            failures.append("tampered APK: the app handed it to Android")
        if not any(s["path"] == "/pubky-maps.apk" and s["apk"] == "tampered" for s in served):
            failures.append("tampered APK: the app never downloaded it")
        time.sleep(5)
        report["steps"]["tampered_version_code"] = version_code()
        if version_code() != OLD_CODE:
            failures.append(f"tampered APK: versionCode is {version_code()} afterwards, not {OLD_CODE}")
        tap(find(is_("OK"), 30, "OK"), "OK")

        # 2b. The app stops and starts again the same day: the update found in 2 is ready at once, read
        # again from its kept signed feed, stays so while the start-up check confirms it (it runs while
        # an update waits, even when the daily one is not due), and is not offered on the map again.
        adb("shell", "am", "force-stop", PKG)
        launch()
        kept = wait_log(r"updates: \S+ \(\d+\), found before, is still ready", 120)
        report["steps"]["kept_after_restart"] = bool(kept)
        if not kept:
            failures.append("restart: the update found before was not read again from its kept feed")
        if not wait_log(r"updates: automatic check: \S+ \(\d+\) is available", 120):
            failures.append("restart: no start-up check confirmed the kept update")
        find(is_("Settings, update ready"), 120, "the Settings button with its update dot after a restart")
        for _ in range(2):
            time.sleep(3)
            nodes = ui_nodes()
            if any(has("Update ready")(n) or has("Update available")(n) for n in nodes):
                failures.append("restart: the update was offered again over the map after the dialog had offered it")
                break
        tap(find(is_("Settings, update ready"), 30, "the Settings button with its dot"), "Settings")
        card = f"Pubky Maps {NEW_NAME} is ready"
        find(lambda n: has(card)(n) and not has(card + " to install")(n), 60,
             "the update card at the top of Settings after a restart")
        shot("2b-ready-after-restart")

        # 3. The real update.
        state["apk"] = "good"
        tap(scroll_to(has("Check automatically"), "Check automatically"), "Check automatically (off)")
        time.sleep(1)
        tap(find(has("Check for updates"), 30, "Check for updates"), "Check for updates")
        find(has("Update available"), 120, "the update offer again")
        tap(find(is_("Install"), 30, "Install"), "Install")
        node = find(is_("Open settings"), 240, "the explanation for Install unknown apps")
        shot("3-allow-explained")
        tap(node, "Open settings")
        time.sleep(4)
        focus = "\n".join(l.strip() for l in sh("dumpsys", "window", timeout=60).splitlines()
                          if "mCurrentFocus" in l or "mFocusedApp" in l)
        shot("3-android-settings")
        opened = "settings" in focus.lower() or "ManageAppExternalSources" in focus
        report["steps"]["settings_opened"] = {"opened": opened, "focus": focus}
        if not opened:
            failures.append(f"Install unknown apps: its settings page did not open ({focus!r})")
        sh("appops", "set", PKG, "REQUEST_INSTALL_PACKAGES", "allow")
        log("Install unknown apps: allowed (appops)")
        if opened:
            adb("shell", "input", "keyevent", "KEYCODE_BACK")
        confirm_update()
        deadline = time.time() + 240
        while time.time() < deadline and version_code() != NEW_CODE:
            time.sleep(3)
        report["steps"]["updated_version_code"] = version_code()
        if version_code() != NEW_CODE:
            failures.append(f"update: versionCode is {version_code()}, not {NEW_CODE}")
        else:
            if "handed to the installer" not in logtext():
                failures.append("update: no hand-over to the installer in the log")
            # Android's "App installed" dialog: Open, as a user would; else start the app directly.
            start_line = rf"updates: {re.escape(NEW_NAME)} \({NEW_CODE}\) on edge; automatic checks (on|off)"
            try:
                node = find(lambda n: "packageinstaller" in n and re.search(r'text="(Open|OPEN)"', n), 60,
                            "Android's Open button")
                shot("3-android-installed")
                tap(node, "Open")
            except RuntimeError:
                log("no Open button after the install; starting the app directly")
            if not wait_log(start_line, 60):
                adb("shell", "am", "start", "-W", "-n", f"{PKG}/.android.MainActivity", timeout=120)
            started = wait_log(start_line, 180)
            report["steps"]["new_build_started"] = started.group(0) if started else None
            if not started:
                failures.append("update: build N+1 did not start")
            elif started.group(1) != "off":
                failures.append("update: the app's data did not survive (automatic checks are on again)")
            if not wait_log(r"updates: the update found before is no longer offered: this is the newest version", 30):
                failures.append("update: N+1 did not drop the kept feed of the update it is")
            tap(find(is_("Settings"), 120, "the Settings button in N+1 (no update dot)"), "Settings")
            tap(scroll_to(has("Check for updates"), "Check for updates in N+1"), "Check for updates")
            find(has("Up to date"), 120, "N+1 finding itself the newest")
            shot("4-up-to-date")
    except Exception as e:  # noqa: BLE001 (reported with the evidence)
        failures.append(f"harness: {e}")
    finally:
        time.sleep(1)
        lc.terminate()
        srv.shutdown()
        try:
            r = subprocess.run(["adb", "logcat", "-d", "-v", "time", "-b", "crash"], capture_output=True,
                               text=True, timeout=60)
            (out / "crash.txt").write_text(r.stdout)
            if f"Process: {PKG}" in r.stdout:
                failures.append("the app crashed (crash.txt)")
        except subprocess.TimeoutExpired:
            pass
    report["served"] = served
    report["failures"] = failures
    (out / "update-probe.json").write_text(json.dumps(report, indent=1))
    print(json.dumps(report, indent=1))
    for f in failures:
        log("FAIL " + f)
    if failures:
        sys.exit(1)
    log("ok: a tampered feed and a tampered APK are refused; the update installs over the app and keeps its data")


main()
