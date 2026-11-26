#!/usr/bin/env python3
"""Checks scripts/release/publish.sh against a fake `gh` and `curl`: an edge build of a commit that
comes after the commit of edge's live build (or is that commit) publishes, and the release names its
commit only after the new feed is up; a build of an older or diverged commit publishes nothing; a
failing compare fails the run; a build with a versionCode that is not above the live one publishes
nothing; `dev` never compares commits. CI: android.yml, build job.

Usage: publish-check.py
"""
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

PUBLISH = Path(__file__).with_name("publish.sh")
live_sha, sha = "7d9785b" + "a" * 33, "f2351e5" + "b" * 33
failures = []

# Answers by environment: EXISTS (the release is there), LIVE_VC (the live feed's versionCode),
# STATUS (GitHub's compare of the live build's commit with this one; FAIL: the call fails, FLAKY: the
# first call fails with GitHub's error body on stdout, as `gh api` does, then it answers `ahead`).
FAKE_GH = f"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["CALLS"], "a") as log:
    log.write(json.dumps(args) + "\\n")
if args[:2] == ["release", "view"]:
    if "targetCommitish" in args:
        print("{live_sha}")
    elif "--json" in args:
        print("pubky-maps-old.apk 1" if "size" in args[-1] else "pubky-maps-old.apk")
    elif "EXISTS" not in os.environ:
        sys.exit("release not found")
elif args[0] == "api" and "-X" not in args:
    path = args[1]
    if path == "repos/o/r/compare/{live_sha}...{sha}":
        status = os.environ["STATUS"]
        if status == "FLAKY":
            flaky = os.environ["CALLS"] + ".flaky"
            status = "ahead" if os.path.exists(flaky) else "FAIL"
            open(flaky, "w").close()
        if status == "FAIL":
            print('{{"message":"Server Error","status":"502"}}')
            sys.exit("gh: Server Error (HTTP 502)")
        print(status)
    else:
        sys.exit("gh: no answer for " + path)
"""

FAKE_CURL = """#!/usr/bin/env python3
import json, os
print(json.dumps({"feed": json.dumps({"versionCode": int(os.environ["LIVE_VC"])})}))
"""


def publish(tag, *, status="ahead", live_vc=5, exists=True):
    """Runs publish.sh for a build with versionCode 7; returns (exit code, `published` output, gh calls, stderr)."""
    with tempfile.TemporaryDirectory() as tmp:
        bin_dir, dist = Path(tmp, "bin"), Path(tmp, "dist")
        bin_dir.mkdir()
        dist.mkdir()
        for name, text in (("gh", FAKE_GH), ("curl", FAKE_CURL), ("sleep", "#!/bin/sh\n")):
            Path(bin_dir, name).write_text(text)
            Path(bin_dir, name).chmod(0o755)
        feed = f"update-{tag}.json"
        Path(dist, feed).write_text(json.dumps({"feed": json.dumps({"versionCode": 7})}))
        Path(dist, "pubky-maps-new.apk").write_text("apk")
        Path(dist, "SHA256SUMS").write_text("sums")
        Path(tmp, "notes.md").write_text("notes")
        calls, output = Path(tmp, "calls"), Path(tmp, "output")
        calls.touch()
        output.touch()
        env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", "GITHUB_REPOSITORY": "o/r",
               "BUILD_SHA": sha, "GITHUB_OUTPUT": str(output), "CALLS": str(calls), "TAG": tag,
               "STATUS": status, "LIVE_VC": str(live_vc)}
        if exists:
            env["EXISTS"] = "1"
        out = subprocess.run(["bash", str(PUBLISH), tag, "Title", str(Path(tmp, "notes.md")), str(dist), feed],
                             capture_output=True, text=True, env=env, timeout=60)
        published = [line for line in output.read_text().splitlines() if line.startswith("published=")]
        log = [json.loads(line) for line in calls.read_text().splitlines()]
        return out.returncode, published, log, out.stdout + out.stderr


def expect(cond, what):
    if not cond:
        failures.append(what)


def index(log, pred):
    return next((i for i, call in enumerate(log) if pred(call)), -1)


def changes(log):
    """The calls that change the release or its tag."""
    return [c for c in log if c[:2] in (["release", "upload"], ["release", "edit"], ["release", "create"])
            or (c[0] == "api" and "-X" in c)]


# Edge, a newer commit (also when GitHub's compare first fails, and on a re-run of the same commit):
# tag moved, files, feed, then the target commit.
for status in ("ahead", "FLAKY", "identical"):
    code, published, log, out = publish("edge", status=status)
    feed_up = index(log, lambda c: c[:2] == ["release", "upload"] and any(a.endswith("/update-edge.json") for a in c))
    named = index(log, lambda c: c[:2] == ["release", "edit"] and "--target" in c and c[c.index("--target") + 1] == sha)
    moved = index(log, lambda c: c[0] == "api" and "PATCH" in c and f"sha={sha}" in c)
    expect(code == 0 and published == ["published=true"], f"edge {status}: {code} {published} {out}")
    expect(0 <= moved < feed_up < named, f"edge {status}: tag move {moved}, feed {feed_up}, target {named}: {log}")
    expect(all("--target" not in c for c in log[:named]), f"edge {status}: target named before the feed: {log}")

# Edge, an older or diverged commit: nothing changes.
for status in ("behind", "diverged"):
    code, published, log, out = publish("edge", status=status)
    expect(code == 0 and published == ["published=false"] and not changes(log) and "nothing published" in out,
           f"edge {status}: {code} {published} {changes(log)} {out}")

# Edge, GitHub's compare fails (three tries): the run fails and nothing changes.
code, published, log, out = publish("edge", status="FAIL")
compares = [c for c in log if c[0] == "api" and "/compare/" in c[1]]
expect(code != 0 and not published and not changes(log) and len(compares) == 3,
       f"edge, failing compare: {code} {published} {len(compares)} compares {changes(log)} {out}")

# A build whose versionCode is not above the live one: nothing changes, no compare.
code, published, log, out = publish("edge", live_vc=7)
expect(code == 0 and published == ["published=false"] and not changes(log) and not any("/compare/" in c[1] for c in log if c[0] == "api"),
       f"edge, same versionCode: {code} {published} {log} {out}")

# dev builds any branch: it never compares commits.
code, published, log, out = publish("dev", status="diverged")
expect(code == 0 and published == ["published=true"] and not any(c[0] == "api" and "/compare/" in c[1] for c in log),
       f"dev: {code} {published} {log} {out}")

# No edge release yet: it is made at this commit.
code, published, log, out = publish("edge", exists=False)
expect(code == 0 and published == ["published=true"]
       and any(c[:2] == ["release", "create"] and c[c.index("--target") + 1] == sha for c in log),
       f"first edge: {code} {published} {log} {out}")

if failures:
    for f in failures:
        print(f"::error::publish: {f}")
    sys.exit(1)
print("publish: ok")
