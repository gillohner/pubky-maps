#!/usr/bin/env python3
"""The channel, versionCode and versionName of a release build (release.yml, meta job).

- a push to main: channel `edge`, published as the `edge` pre-release, versionName <x.y.z>-edge.<run>;
- a `v<x.y.z>` tag: channel `stable`, published as that release, versionName <x.y.z>; the tag must
  match `pubkymaps.version` in gradle.properties;
- a manual run (any branch): channel `dev`, published as the `dev` pre-release, <x.y.z>-dev.<run>.

versionCode = (major * 10000 + minor * 100 + patch) * 10000 + n, where n is the workflow run number
minus `pubkymaps.versionRunOffset` for edge and dev builds (1..9998) and 9999 for a release. Run
numbers only grow, so every edge build installs over the one before; a release installs over every
edge build of its version, and the first edge build after the version is raised installs over the
release. Raising the version comes with setting the offset to the latest run number, so each version
has 9998 builds.

Usage: version.py <gradle.properties> <GITHUB_REF> <GITHUB_EVENT_NAME> <GITHUB_RUN_NUMBER>
Prints key=value lines for $GITHUB_OUTPUT.
"""
import re
import sys
from pathlib import Path


def compute(version: str, ref: str, event: str, run: int, offset: int = 0) -> dict:
    m = re.fullmatch(r"(\d+)\.(\d+)\.(\d+)", version)
    if not m:
        raise ValueError(f"pubkymaps.version {version!r} is not <major>.<minor>.<patch>")
    major, minor, patch = map(int, m.groups())
    if major > 20 or minor > 99 or patch > 99:
        raise ValueError(f"{version}: versionCode allows major <= 20, minor and patch <= 99")
    base = (major * 10000 + minor * 100 + patch) * 10000
    if event == "push" and ref.startswith("refs/tags/v"):
        tag = ref.removeprefix("refs/tags/")
        if tag != f"v{version}":
            raise ValueError(f"tag {tag} does not match pubkymaps.version {version}; raise the version first")
        return {"channel": "stable", "release_tag": tag, "prerelease": "false",
                "version_code": str(base + 9999), "version_name": version}
    if event == "push" and ref == "refs/heads/main":
        channel = "edge"
    elif event == "workflow_dispatch":
        channel = "dev"
    else:
        raise ValueError(f"no release for {event} on {ref}")
    n = run - offset
    if not 1 <= n <= 9998:
        raise ValueError(f"run {run} minus pubkymaps.versionRunOffset {offset} is not in 1..9998: raise "
                         "pubkymaps.version and set pubkymaps.versionRunOffset to the latest run number")
    return {"channel": channel, "release_tag": channel, "prerelease": "true",
            "version_code": str(base + n), "version_name": f"{version}-{channel}.{n}"}


def main() -> None:
    props, ref, event, run = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4])
    text = Path(props).read_text()
    m = re.search(r"^pubkymaps\.version=(.+)$", text, re.M)
    o = re.search(r"^pubkymaps\.versionRunOffset=(\d+)\s*$", text, re.M)
    if not m or not o:
        print(f"::error::version: no pubkymaps.version or pubkymaps.versionRunOffset in {props}")
        sys.exit(1)
    try:
        out = compute(m.group(1).strip(), ref, event, run, int(o.group(1)))
    except ValueError as e:
        print(f"::error::{e}")
        sys.exit(1)
    out["feed_name"] = {"edge": "update-edge.json", "stable": "update.json", "dev": "update-dev.json"}[out["channel"]]
    for k, v in out.items():
        print(f"{k}={v}")


if __name__ == "__main__":
    main()
