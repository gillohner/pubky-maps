#!/usr/bin/env python3
"""Checks scripts/release/publish.sh against a fake `gh` and `curl`: an edge build of a commit that
comes after the commit of edge's live build (or is that commit) publishes, and the release names its
commit only after the new feed is up; a build of an older or diverged commit publishes nothing; a
failing compare fails the run; a build with a versionCode that is not above the live one publishes
nothing; `dev` never compares commits; uploads, the release edits that publish it again after the
tag move and name its commit, and a v* draft's publish (found in GitHub's release list) are tried
again when GitHub fails them, and a run whose edit keeps failing fails without deleting the files of
the build before; the file of the build before is deleted, a delete that GitHub fails is tried again
while the release still lists the file, and one that keeps failing, or a listing of the release's
files that GitHub fails, only warns. CI: android.yml, build job.

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
# first call fails with GitHub's error body on stdout, as `gh api` does, then it answers `ahead`),
# DRAFT_FAILS / TARGET_FAILS / PUBLISH_FAILS / UPLOAD_FAILS / DELETE_FAILS / LIST_FAILS / SIZES_FAILS
# (the first n `release edit` calls that publish the release again, or that name its commit, the
# `api` calls that publish a v* draft, the uploads, the file deletes, the listings of the release's
# file names, or the listings of its files with their sizes at the end, fail), DELETE_LOST (the first
# n deletes delete the file, but GitHub's answer is lost). The release lists the files of this build
# and the old pubky-maps-old.apk until it is deleted. The v* release is a draft, which GitHub's
# releases/tags/<tag> does not find.
FAKE_GH = f"""#!/usr/bin/env python3
import json, os, sys
args = sys.argv[1:]
with open(os.environ["CALLS"], "a") as log:
    log.write(json.dumps(args) + "\\n")
def fails(kind):
    count = os.environ["CALLS"] + "." + kind
    n = int(open(count).read()) if os.path.exists(count) else 0
    open(count, "w").write(str(n + 1))
    return n < int(os.environ.get(kind, "0"))
if args[:2] == ["release", "upload"]:
    if fails("UPLOAD_FAILS"):
        sys.exit("gh: Bad Gateway (HTTP 502)")
elif args[:2] == ["release", "edit"]:
    kind = "TARGET_FAILS" if "--target" in args else "DRAFT_FAILS" if "--draft=false" in args else None
    if kind and fails(kind):
        sys.exit("gh: Bad Gateway (HTTP 502)")
elif args[:2] == ["release", "view"]:
    if "targetCommitish" in args:
        print("{live_sha}")
    elif "--json" in args:
        if fails("SIZES_FAILS" if "size" in args[-1] else "LIST_FAILS"):
            sys.exit("gh: Bad Gateway (HTTP 502)")
        gone = os.environ["CALLS"] + ".deleted"
        names = [n for n in ("pubky-maps-new.apk", "pubky-maps-old.apk", "SHA256SUMS")
                 if not (os.path.exists(gone) and n in open(gone).read().split())]
        print("\\n".join(n + " 1" if "size" in args[-1] else n for n in names))
    elif "EXISTS" not in os.environ:
        sys.exit("release not found")
elif args[:2] == ["release", "delete-asset"]:
    if fails("DELETE_FAILS"):
        sys.exit("gh: Bad Gateway (HTTP 502)")
    with open(os.environ["CALLS"] + ".deleted", "a") as gone:
        gone.write(args[3] + "\\n")
    if fails("DELETE_LOST"):
        sys.exit("gh: Bad Gateway (HTTP 502)")
elif args[:3] == ["api", "-X", "PATCH"] and args[3].startswith("repos/o/r/releases/"):
    if args[3] != "repos/o/r/releases/42":
        print('{{"message":"Not Found","status":"404"}}')
        sys.exit("gh: Not Found (HTTP 404)")
    if fails("PUBLISH_FAILS"):
        print('{{"message":"Server Error","status":"502"}}')
        sys.exit("gh: Server Error (HTTP 502)")
    print('{{"id":42}}')
elif args[0] == "api" and "-X" not in args:
    path = args[1]
    if path == "repos/o/r/releases/tags/v1.2.3":
        print('{{"message":"Not Found","status":"404"}}')
        sys.exit("gh: Not Found (HTTP 404)")
    elif path == "repos/o/r/releases?per_page=100":
        if "--jq" not in args or "v1.2.3" not in args[args.index("--jq") + 1]:
            sys.exit("gh: no answer for " + " ".join(args))
        if "LISTED" in os.environ:
            print("42")
    elif path == "repos/o/r/compare/{live_sha}...{sha}":
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


def publish(tag, *, status="ahead", live_vc=5, exists=True, draft_fails=0, target_fails=0, publish_fails=0,
            upload_fails=0, listed=True, delete_fails=0, delete_lost=0, list_fails=0, sizes_fails=0):
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
               "STATUS": status, "LIVE_VC": str(live_vc), "DRAFT_FAILS": str(draft_fails),
               "TARGET_FAILS": str(target_fails), "PUBLISH_FAILS": str(publish_fails),
               "UPLOAD_FAILS": str(upload_fails), "DELETE_FAILS": str(delete_fails),
               "DELETE_LOST": str(delete_lost), "LIST_FAILS": str(list_fails),
               "SIZES_FAILS": str(sizes_fails)}
        if listed:
            env["LISTED"] = "1"
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

# GitHub fails the edits after the tag move and after the feed a few times: they are tried again, and
# the release names this build's commit once its feed is up.
for tag in ("edge", "dev"):
    code, published, log, out = publish(tag, draft_fails=2, target_fails=4)
    drafts = [c for c in log if c[:2] == ["release", "edit"] and "--draft=false" in c]
    targets = [c for c in log if c[:2] == ["release", "edit"] and "--target" in c]
    feed_up = index(log, lambda c: c[:2] == ["release", "upload"] and any(a.endswith(f"/update-{tag}.json") for a in c))
    named = max((i for i, c in enumerate(log) if c in targets), default=len(log))
    expect(code == 0 and published == ["published=true"] and len(drafts) == 3 and len(targets) == 5
           and 0 <= feed_up < log.index(targets[0]) and targets[-1][targets[-1].index("--target") + 1] == sha
           and any(c[:3] == ["release", "delete-asset", tag] for c in log[named:]),
           f"{tag}, flaky edits: {code} {published} {len(drafts)} drafts {len(targets)} targets {log} {out}")

# GitHub fails an upload twice: it is tried again, with a warning on each failed try.
code, published, log, out = publish("edge", upload_fails=2)
uploads = [c for c in log if c[:2] == ["release", "upload"]]
expect(code == 0 and published == ["published=true"] and len(uploads) == 4 and out.count("::warning::gh release upload") == 2,
       f"edge, flaky upload: {code} {published} {len(uploads)} uploads {log} {out}")

# A v* release: GitHub fails the call that publishes the filled draft twice; it is tried again, after
# the feed, with a warning on each failed try, and nothing of GitHub's answer reaches the log.
code, published, log, out = publish("v1.2.3", publish_fails=2)
publishes = [c for c in log if c[:3] == ["api", "-X", "PATCH"]]
feed_up = index(log, lambda c: c[:2] == ["release", "upload"] and any(a.endswith("/update-v1.2.3.json") for a in c))
expect(code == 0 and published == ["published=true"] and len(publishes) == 3
       and 0 <= feed_up < log.index(publishes[0]) and "draft=false" in publishes[-1]
       and out.count("::warning::gh api -X PATCH") == 2 and '"message"' not in out and '"id"' not in out,
       f"v1.2.3, flaky publish: {code} {published} {len(publishes)} publishes {log} {out}")

# A v* release whose publish keeps failing, or that GitHub does not list: the run fails and says so.
code, published, log, out = publish("v1.2.3", publish_fails=99)
publishes = [c for c in log if c[:3] == ["api", "-X", "PATCH"]]
expect(code != 0 and not published and len(publishes) == 5 and "::error::gh api -X PATCH" in out,
       f"v1.2.3, failing publish: {code} {published} {len(publishes)} publishes {log} {out}")
code, published, log, out = publish("v1.2.3", listed=False)
expect(code != 0 and not published and not any(c[:3] == ["api", "-X", "PATCH"] for c in log)
       and "lists no release of v1.2.3" in out,
       f"v1.2.3, not listed: {code} {published} {log} {out}")

# The file of the build before is deleted once its build is replaced, and only that file.
code, published, log, out = publish("edge")
deletes = [c for c in log if c[:2] == ["release", "delete-asset"]]
expect(code == 0 and [c[3] for c in deletes] == ["pubky-maps-old.apk"] and "::warning::" not in out,
       f"edge, old file: {code} {deletes} {out}")

# GitHub fails a delete twice, or answers a delete that worked with an error (a lost answer): it is
# tried again while the release still lists the file, and the run is green.
code, published, log, out = publish("edge", delete_fails=2)
deletes = [c for c in log if c[:2] == ["release", "delete-asset"]]
expect(code == 0 and published == ["published=true"] and len(deletes) == 3
       and out.count("::warning::gh release delete-asset") == 2 and "could not be deleted" not in out,
       f"edge, flaky delete: {code} {published} {len(deletes)} deletes {log} {out}")
code, published, log, out = publish("dev", delete_lost=1)
deletes = [c for c in log if c[:2] == ["release", "delete-asset"]]
expect(code == 0 and published == ["published=true"] and len(deletes) == 1 and "could not be deleted" not in out,
       f"dev, lost delete answer: {code} {published} {len(deletes)} deletes {log} {out}")

# GitHub keeps failing a delete, or does not list the release's files: the publish is complete, so
# the run is green and warns that the old file stays until the next publish.
code, published, log, out = publish("edge", delete_fails=99)
deletes = [c for c in log if c[:2] == ["release", "delete-asset"]]
expect(code == 0 and published == ["published=true"] and len(deletes) == 3
       and "::warning::the old file pubky-maps-old.apk of the edge release could not be deleted" in out,
       f"edge, failing delete: {code} {published} {len(deletes)} deletes {log} {out}")
code, published, log, out = publish("edge", list_fails=99)
expect(code == 0 and published == ["published=true"] and not any(c[:2] == ["release", "delete-asset"] for c in log)
       and "::warning::GitHub did not list the edge release's files" in out,
       f"edge, failing listing: {code} {published} {log} {out}")

# GitHub fails the listing at the end, after a complete publish: it only warns.
for tag in ("edge", "v1.2.3"):
    code, published, log, out = publish(tag, sizes_fails=99)
    expect(code == 0 and published == ["published=true"]
           and f"::warning::GitHub did not list the {tag} release's files after the publish" in out,
           f"{tag}, failing listing at the end: {code} {published} {log} {out}")

# GitHub keeps failing the edit that names the commit: the run fails, says so, and keeps the files of
# the build before (whose commit the release still names).
code, published, log, out = publish("edge", target_fails=99)
targets = [c for c in log if c[:2] == ["release", "edit"] and "--target" in c]
expect(code != 0 and not published and len(targets) == 5 and "::error::" in out
       and not any(c[:2] == ["release", "delete-asset"] for c in log),
       f"edge, failing target edit: {code} {published} {len(targets)} targets {log} {out}")

if failures:
    for f in failures:
        print(f"::error::publish: {f}")
    sys.exit(1)
print("publish: ok")
