#!/usr/bin/env python3
"""StrictMode's reports in an emulator test's log (debug builds, androidApp/src/debug/.../StrictModeProvider.kt).

Each report is one line, "ours <kind> at <frame>" or "library <kind> at <frame>", tagged PubkyMapsStrictMode.
A report about our code fails the test: the main thread waited on disk or network there, or a resource leaked.
Library reports are listed and do not fail it.

Usage: strict_mode.py <logcat.txt>
"""
import collections
import re
import sys

REPORT = re.compile(r"PubkyMapsStrictMode: (ours|library) (\S+) at (.+?)\s*$")


def reports(logcat):
    """Counter of (owner, kind, frame) over the report lines of a logcat dump."""
    return collections.Counter(m.groups() for m in map(REPORT.search, logcat.splitlines()) if m)


def check(logcat):
    """Prints the reports; returns the error message when one is about our code, else None."""
    found = reports(logcat)
    for (owner, kind, frame), n in sorted(found.items()):
        print(f"StrictMode: {owner} {kind} at {frame} ({n}x)")
    ours = [f"{kind} at {frame}" for (owner, kind, frame) in found if owner == "ours"]
    if ours:
        return f"StrictMode caught our code: {'; '.join(sorted(ours))}; the stacks are in logcat.txt (tag PubkyMapsStrictMode)"
    return None


if __name__ == "__main__":
    with open(sys.argv[1], errors="replace") as f:
        error = check(f.read())
    if error:
        print(f"::error::{error}")
        sys.exit(1)
