#!/usr/bin/env python3
"""The commit an `edge` build is made from (release.yml, meta job): main's head when the run starts.

GitHub keeps one pending run per concurrency group and cancels the one that was pending when another
is queued, in the order the push events arrive, not the order of the commits. Three merges within
20 seconds once queued the run of the middle commit last: it cancelled the pending run of the newest
commit and published edge from the older one, while main had moved on (11-26, edge 3320f35 while main
was 2f1fe8f). So an edge run builds main's head, not the commit that started it. Run numbers, and with
them versionCodes (version.py), then only grow with the commit they build, and the last run to start
publishes main's head.

The run keeps its own commit when main's head is that commit, when the head is not a descendant of
it (main was rewritten), or when release.yml differs between the two (this run's steps are the older
release.yml, which might not build the newer commit). When GitHub cannot be asked, it keeps its own
commit and warns. publish.sh then publishes such a build only when its commit comes after the one
edge has, so edge never goes back; it can stay behind main until the next push to main.

When edge already has a build of the chosen commit (the release's target commit, which publish.sh
sets once that build's update feed is up, so it names the build of the last complete publish), there
is nothing to build: the run of a commit that an earlier run already built as main's head would
otherwise publish the same code again under a higher versionCode, and edge installs would be offered
it as an update.

Usage: edge-commit.py <owner/repo> <this run's commit>   (GH_TOKEN for `gh api`)
Prints `sha=<commit>` and `build=true|false` for $GITHUB_OUTPUT; notices and warnings go to stderr.
"""
import re
import subprocess
import sys

WORKFLOW = ".github/workflows/release.yml"
TAG, FEED = "edge", "update-edge.json"


def choose(run_sha: str, head_sha: str, status: str, run_workflow: str, head_workflow: str) -> tuple[str, str]:
    """Returns (commit to build, why). `status` is GitHub's compare status of head against run_sha;
    the workflows are the blob ids of release.yml at each commit."""
    if head_sha == run_sha:
        return run_sha, f"notice::{run_sha[:7]} is main's head"
    if status != "ahead":
        return run_sha, (f"warning::main's head {head_sha[:7]} does not come after {run_sha[:7]} ({status}); "
                         f"building {run_sha[:7]}")
    if run_workflow != head_workflow:
        return run_sha, (f"warning::main moved on to {head_sha[:7]} with another {WORKFLOW}; building "
                         f"{run_sha[:7]}; edge can stay behind main until the next push to main")
    return head_sha, f"notice::main moved on to {head_sha[:7]} since this run of {run_sha[:7]} was queued; building {head_sha[:7]}"


def gh(path: str, jq: str) -> str:
    out = subprocess.run(["gh", "api", path, "--jq", jq], check=True, capture_output=True, text=True, timeout=60)
    return out.stdout.strip()


def reason(e: Exception) -> str:
    lines = (getattr(e, "stderr", None) or str(e)).strip().splitlines()
    return lines[-1] if lines else type(e).__name__


def main() -> None:
    repo, run_sha = sys.argv[1], sys.argv[2]
    try:
        head_sha = gh(f"repos/{repo}/commits/main", ".sha")
        if not re.fullmatch(r"[0-9a-f]{40}", head_sha):
            raise ValueError(f"main's head is {head_sha!r}")
        status = gh(f"repos/{repo}/compare/{run_sha}...{head_sha}", ".status")
        run_workflow = gh(f"repos/{repo}/contents/{WORKFLOW}?ref={run_sha}", ".sha")
        head_workflow = gh(f"repos/{repo}/contents/{WORKFLOW}?ref={head_sha}", ".sha")
    except (subprocess.SubprocessError, OSError, ValueError) as e:
        print(f"::warning::could not ask GitHub for main's head ({reason(e)}); building {run_sha[:7]}", file=sys.stderr)
        sha = run_sha
    else:
        sha, why = choose(run_sha, head_sha, status, run_workflow, head_workflow)
        print(f"::{why}", file=sys.stderr)
    print(f"sha={sha}")
    try:
        live = gh(f"repos/{repo}/releases/tags/{TAG}", f'.target_commitish + " " + (any(.assets[]; .name == "{FEED}") | tostring)')
    except (subprocess.SubprocessError, OSError) as e:
        # No edge release yet, or GitHub cannot be asked: build; publish.sh decides.
        print(f"::notice::no live {TAG} build to compare with ({reason(e)})", file=sys.stderr)
        live = ""
    if live == f"{sha} true":
        print(f"::notice::the {TAG} release already has the build of {sha[:7]}; nothing to build", file=sys.stderr)
        print("build=false")
    else:
        print("build=true")


if __name__ == "__main__":
    main()
