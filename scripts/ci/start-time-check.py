#!/usr/bin/env python3
"""Checks scripts/ci/start_time.py on logcat lines as Android 15 writes them: the last start counts,
its map must come after its own window, a start without a map (or no start yet) waits, and the budget fails a slow map.
CI: android.yml, build job.

Usage: start-time-check.py
"""
import contextlib
import importlib.util
import io
import sys
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("start_time", Path(__file__).with_name("start_time.py"))
start_time = importlib.util.module_from_spec(spec)
spec.loader.exec_module(start_time)
failures = []
pkg = "to.pubky.maps"


def expect(cond, what):
    if not cond:
        failures.append(what)


for text, ms in (("850ms", 850), ("3s558ms", 3558), ("1m2s3ms", 62003), ("2s", 2000), ("1h0m0s1ms", 3600001)):
    expect(start_time.millis(text) == ms, f"millis({text}) = {start_time.millis(text)}, not {ms}")
for text in ("", "fast", "3s558"):
    try:
        start_time.millis(text)
        failures.append(f"millis({text!r}) did not refuse")
    except ValueError:
        pass

displayed = "11-26 06:42:28.530  1234  1260 I ActivityTaskManager: Displayed {}/.android.MainActivity for user 0: +{}"
drawn = "11-26 06:42:31.120  1234  1260 I ActivityTaskManager: Fully drawn {}/.android.MainActivity: +{}"
first = [displayed.format(pkg, "3s558ms"), "I MapLibre: noise", drawn.format(pkg, "8s120ms")]
restart = [displayed.format(pkg, "2s226ms"), drawn.format(pkg, "4s5ms")]

expect(start_time.last_start(first, pkg) == (3558, 8120), f"first start: {start_time.last_start(first, pkg)}")
expect(start_time.last_start(first + restart, pkg) == (2226, 4005), "the restart is the last start")
# The restart has drawn its window but not its map yet: the first start's map does not count.
expect(start_time.last_start(first + restart[:1], pkg) == (2226, None), "a start without its map yet")
# Another app's start, and a map report before any window of ours, are not ours.
other = [displayed.format("com.android.settings", "500ms"), drawn.format("com.android.settings", "600ms")]
expect(start_time.last_start(first + other, pkg) == (3558, 8120), "another app's lines counted")
expect(start_time.last_start(restart[1:], pkg) is None, "a map report without a start counted")


def run(lines, budget):
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False) as log:
        log.write("\n".join(lines) + "\n")
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = start_time.main(log.name, pkg, "first", budget)
    Path(log.name).unlink()
    return code, out.getvalue()


code, out = run(first, 10000)
expect(code == 0 and out.startswith("::notice::") and "8120 ms" in out and "3558 ms" in out, f"within budget: {code} {out}")
code, out = run(first, 8000)
expect(code == 1 and out.startswith("::error::"), f"over budget: {code} {out}")
code, out = run(first + restart[:1], 10000)
expect(code == 3 and out == "", f"map not drawn yet: {code} {out!r}")
# A log read that failed or came too early: ask again.
code, out = run(["I MapLibre: noise"], 10000)
expect(code == 3 and out == "", f"no start: {code} {out!r}")

if failures:
    for failure in failures:
        print(f"::error::start-time-check: {failure}")
    sys.exit(1)
print("start-time-check: ok")
