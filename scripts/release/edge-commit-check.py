#!/usr/bin/env python3
"""Checks scripts/release/edge-commit.py: an edge run builds main's head when main moved on with the
same release.yml, and keeps its own commit otherwise. CI: android.yml, build job.

Usage: edge-commit-check.py
"""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("edge_commit", Path(__file__).with_name("edge-commit.py"))
edge_commit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(edge_commit)
choose = edge_commit.choose
failures = []
old, new, workflow, other = "3320f35" + "a" * 33, "2f1fe8f" + "b" * 33, "w" * 40, "x" * 40


def expect(cond, what):
    if not cond:
        failures.append(what)


# 11-26: the run of 3320f35 started after main had moved on to 2f1fe8f (same release.yml).
sha, why = choose(old, new, "ahead", workflow, workflow)
expect(sha == new and why.startswith("notice::"), f"main moved on: {sha[:7]} {why}")
sha, why = choose(new, new, "identical", workflow, workflow)
expect(sha == new and why.startswith("notice::"), f"run of main's head: {sha[:7]} {why}")
sha, why = choose(old, new, "ahead", workflow, other)
expect(sha == old and why.startswith("warning::"), f"another release.yml at main's head: {sha[:7]} {why}")
for status in ("behind", "diverged"):
    sha, why = choose(old, new, status, workflow, workflow)
    expect(sha == old and why.startswith("warning::"), f"main's head {status}: {sha[:7]} {why}")

if failures:
    for f in failures:
        print(f"::error::edge-commit: {f}")
    sys.exit(1)
print("edge-commit: ok")
