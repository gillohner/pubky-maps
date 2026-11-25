#!/usr/bin/env python3
"""Checks scripts/release/version.py: channels per event, versionCodes that only grow (edge builds,
then the release of that version, then the next version's edge builds), and the refusals. CI:
android.yml, build job.

Usage: version-check.py
"""
import importlib.util
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("version", Path(__file__).with_name("version.py"))
version = importlib.util.module_from_spec(spec)
spec.loader.exec_module(version)
compute = version.compute
failures = []


def expect(cond, what):
    if not cond:
        failures.append(what)


def refused(*args):
    try:
        compute(*args)
    except ValueError:
        return True
    return False


edge = compute("0.1.0", "refs/heads/main", "push", 12)
expect(edge == {"channel": "edge", "release_tag": "edge", "prerelease": "true", "version_code": "1000012",
                "version_name": "0.1.0-edge.12"}, f"edge: {edge}")
dev = compute("0.1.0", "refs/heads/s1/release", "workflow_dispatch", 13)
expect(dev["channel"] == "dev" and dev["release_tag"] == "dev" and dev["version_name"] == "0.1.0-dev.13", f"dev: {dev}")
expect(compute("0.1.0", "refs/heads/main", "workflow_dispatch", 14)["channel"] == "dev", "a manual run on main is not dev")
stable = compute("0.1.0", "refs/tags/v0.1.0", "push", 15)
expect(stable == {"channel": "stable", "release_tag": "v0.1.0", "prerelease": "false", "version_code": "1009999",
                  "version_name": "0.1.0"}, f"stable: {stable}")

codes = [int(compute("0.1.0", "refs/heads/main", "push", r)["version_code"]) for r in (1, 2, 500, 9998)]
codes.append(int(stable["version_code"]))
codes += [int(compute("0.2.0", "refs/heads/main", "push", r)["version_code"]) for r in (1, 20)]
codes.append(int(compute("0.2.0", "refs/tags/v0.2.0", "push", 21)["version_code"]))
codes.append(int(compute("1.0.0", "refs/heads/main", "push", 22)["version_code"]))
expect(codes == sorted(codes) and len(set(codes)) == len(codes), f"versionCodes do not only grow: {codes}")
expect(int(compute("20.99.99", "refs/tags/v20.99.99", "push", 1)["version_code"]) <= 2100000000,
       "the largest versionCode is past Android's limit")

expect(refused("0.1.0", "refs/tags/v0.2.0", "push", 1), "a tag that does not match the version was accepted")
expect(refused("0.1.0", "refs/heads/feature", "push", 1), "a push to a branch made a release")
expect(refused("0.1.0", "refs/heads/main", "pull_request", 1), "a pull request made a release")
expect(refused("0.1.0", "refs/heads/main", "push", 9999), "run 9999 was accepted (collides with the release)")
expect(refused("0.1", "refs/heads/main", "push", 1), "a two-part version was accepted")
expect(refused("21.0.0", "refs/heads/main", "push", 1), "a versionCode past the limit was accepted")

for f in failures:
    print("FAIL " + f)
if failures:
    sys.exit(1)
print("version: ok")
