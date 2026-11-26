"""Helpers for the emulator tests: adb, the UI as uiautomator sees it, screenshots, map checks.

The scripts that use it add this directory to sys.path themselves (they run with python3 -I).
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import strict_mode

PKG = "to.pubky.maps"
ACTIVITY = f"{PKG}/.android.MainActivity"
HERE = Path(__file__).resolve().parent


def log(msg):
    print(f"[{time.strftime('%H:%M:%S')}] {msg}", flush=True)


class Failure(Exception):
    pass


class Device:
    def __init__(self, out):
        self.out = Path(out)
        self.out.mkdir(parents=True, exist_ok=True)

    # --- adb -----------------------------------------------------------------------------------

    def adb(self, *args, check=True, timeout=120, stdin=None):
        r = subprocess.run(["adb", *args], capture_output=True, timeout=timeout, input=stdin)
        out = r.stdout.decode(errors="replace")
        if check and r.returncode != 0:
            raise Failure(f"adb {' '.join(args)}: {r.stderr.decode(errors='replace').strip() or out.strip()}")
        return out

    def shell(self, *args, check=False, timeout=60):
        return self.adb("shell", *args, check=check, timeout=timeout)

    def install(self, apk):
        self.adb("install", "-r", str(apk), timeout=600)

    def clear_logs(self):
        # Large enough to keep StrictMode's reports from the start until the end.
        self.adb("logcat", "-G", "16M", check=False)
        self.adb("logcat", "-c", check=False)
        self.adb("logcat", "-b", "crash", "-c", check=False)

    def save_logcat(self, name="logcat.txt"):
        (self.out / name).write_text(self.adb("logcat", "-d", check=False, timeout=120))

    def check_strict_mode(self, step):
        """Saves the log and fails when StrictMode caught our code (strict_mode.py)."""
        self.save_logcat()
        error = strict_mode.check((self.out / "logcat.txt").read_text())
        if error:
            self.fail(f"{error} ({step})")

    def fail(self, msg):
        self.save_logcat()
        print(f"::error::{msg}", flush=True)
        raise Failure(msg)

    def start_app(self):
        started = self.adb("shell", "am", "start", "-W", "-n", ACTIVITY, check=False)
        log(started.strip())
        if "Error" in started:
            self.fail(f"the app did not start: {started}")

    def open_link(self, url):
        """As another app opening `url` (Ring sending the user back)."""
        r = self.adb("shell", "am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"'{url}'", check=False)
        log(f"opened a link: {r.strip().splitlines()[0] if r.strip() else ''}")
        return r

    def write_app_file(self, name, data):
        """Writes `data` to the app's files/<name> through run-as (debug builds only)."""
        self.adb("shell", f"run-as {PKG} sh -c 'mkdir -p files && cat > files/{name}'", stdin=data)
        listed = self.shell(f"run-as {PKG} ls files")
        if name not in listed:
            self.fail(f"could not write files/{name} into the app ({listed.strip()})")

    def check_alive(self, step):
        crash = self.adb("logcat", "-d", "-b", "crash", check=False)
        (self.out / "crash.txt").write_text(crash)
        if re.search(rf"Process: {re.escape(PKG)}|>>> {re.escape(PKG)} <<<", crash):
            self.fail(f"the app crashed ({step}); see crash.txt")
        resumed = self.shell("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'")
        if f"{PKG}/" not in resumed:
            self.fail(f"the app is not the resumed activity ({step}): {resumed.strip()}")

    def screenshot(self, name):
        path = self.out / f"{name}.png"
        path.write_bytes(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
        return path

    # --- UI ------------------------------------------------------------------------------------

    def dump(self, name="ui"):
        self.shell("rm -f /sdcard/ui.xml")
        self.shell("uiautomator dump /sdcard/ui.xml > /dev/null 2>&1")
        xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True, timeout=60).stdout
        (self.out / f"{name}.xml").write_bytes(xml)
        try:
            root = ET.fromstring(xml)
        except ET.ParseError:
            return []
        nodes = list(root.iter("node"))
        self._handle_anr(nodes)
        return nodes

    def _handle_anr(self, nodes):
        for n in nodes:
            m = re.match(r"(.*) isn.t responding$", n.get("text") or "")
            if not m:
                continue
            if m.group(1) == "Pubky Maps":
                self.fail("Pubky Maps is not responding (ANR)")
            log(f"dismissing an ANR dialog of {m.group(1)}")
            for w in nodes:
                if w.get("text") == "Wait":
                    self.tap_node(w)

    @staticmethod
    def label(node):
        return (node.get("content-desc") or "") + "\n" + (node.get("text") or "")

    def find(self, nodes, text, exact=True):
        for n in nodes:
            values = (n.get("content-desc") or "", n.get("text") or "")
            if (text in values) if exact else any(text in v for v in values):
                return n
        return None

    def wait_for(self, text, timeout, exact=True):
        deadline = time.time() + timeout
        while time.time() < deadline:
            node = self.find(self.dump("wait"), text, exact)
            if node is not None:
                return node
            time.sleep(2)
        return None

    def tap_node(self, node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
        self.shell("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def tap(self, text, timeout=15, exact=True):
        node = self.wait_for(text, timeout, exact)
        if node is None:
            self.fail(f"nothing labelled {text!r} on screen")
        self.tap_node(node)

    def back(self):
        self.shell("input", "keyevent", "KEYCODE_BACK")

    def texts(self, nodes):
        return [t for n in nodes for t in (n.get("text"), n.get("content-desc")) if t]

    # --- map -----------------------------------------------------------------------------------

    def wait_for_map(self, name="map", tries=30):
        """Waits until the middle of the screen shows a drawn map: many colours, none covering
        almost all of it. Fails on the could-not-load notice."""
        stats = "0 1.000"
        for _ in range(tries):
            self.dump("map-wait")
            shot = self.screenshot(name)
            r = subprocess.run([sys.executable, "-I", str(HERE / "png_stats.py"), str(shot), "0.1", "0.2", "0.9", "0.8"],
                               capture_output=True, text=True)
            stats = r.stdout.strip() or "0 1.000"
            colours, dominant = stats.split()
            log(f"map: {colours} colours, most common {dominant}")
            if int(colours) >= 40 and float(dominant) < 0.9:
                break
            time.sleep(3)
        else:
            self.fail(f"the map did not draw ({stats} in the middle of the screen)")
        if self.find(self.dump("map"), "Retry") is not None:
            self.fail("the map could not load any style (Retry notice shown)")
        # Let the remaining tiles arrive: until two screenshots in a row are the same.
        for _ in range(10):
            time.sleep(3)
            nxt = self.screenshot(f"{name}-next")
            if nxt.read_bytes() == (self.out / f"{name}.png").read_bytes():
                break
            nxt.replace(self.out / f"{name}.png")
        (self.out / f"{name}-next.png").unlink(missing_ok=True)


def decode_qr(png):
    """The text of the QR code in `png` (zbarimg), or None."""
    r = subprocess.run(["zbarimg", "--quiet", "--raw", str(png)], capture_output=True, text=True)
    lines = [l for l in r.stdout.splitlines() if l.strip()]
    return lines[0] if lines else None
