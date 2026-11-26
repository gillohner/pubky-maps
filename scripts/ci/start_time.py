#!/usr/bin/env python3
"""The start time of the app's last start, from Android's own log lines: "Displayed" (its first
window drawn) and "Fully drawn" (the app's reportFullyDrawn, called once the map has drawn its first
whole view). Prints both as a GitHub notice, or an error when the map took longer than the budget.

Exit 0: within the budget. 1: over the budget. 3: no start in the log, or the last start has not
reported its map yet (call again later; the caller fails when it never comes). Checked by scripts/ci/start-time-check.py.

Usage: start_time.py <logcat file> <package> <name of the start> <budget for the map in ms>
"""
import re
import sys

DURATION = re.compile(r"(?:(\d+)d)?(?:(\d+)h)?(?:(\d+)m(?!s))?(?:(\d+)s)?(?:(\d+)ms)?$")


def millis(text):
    """Android's TimeUtils.formatDuration ("1s234ms", "850ms", "1m2s3ms") in milliseconds."""
    m = DURATION.match(text)
    if not text or not m:
        raise ValueError(f"not a duration: {text}")
    days, hours, minutes, seconds, ms = (int(g or 0) for g in m.groups())
    return (((days * 24 + hours) * 60 + minutes) * 60 + seconds) * 1000 + ms


def last_start(lines, package):
    """(window ms, map ms or None) of the last start of package's activities, or None."""
    line = re.compile(rf"(Displayed|Fully drawn) {re.escape(package)}/\S+(?: for user \d+)?: \+(\S+)")
    start = None
    for text in lines:
        m = line.search(text)
        if not m:
            continue
        if m.group(1) == "Displayed":
            start = (millis(m.group(2)), None)
        elif start is not None and start[1] is None:
            start = (start[0], millis(m.group(2)))
    return start


def main(path, package, name, budget):
    with open(path, errors="replace") as log:
        start = last_start(log, package)
    if start is None or start[1] is None:
        return 3
    window, drawn = start
    said = f"start {name}: window after {window} ms, map drawn after {drawn} ms (budget {budget} ms)"
    if drawn > budget:
        print(f"::error::{said}")
        return 1
    print(f"::notice::{said}")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])))
