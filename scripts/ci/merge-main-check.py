#!/usr/bin/env python3
"""Checks scripts/ci/merge-main.py against throwaway git repositories, and that every job of
android.yml merges the `changes` job's main commit right after its checkout. CI: android.yml, build
job.

Usage: merge-main-check.py
"""
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("merge-main.py")
WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/android.yml"
failures = []
ENV = {**os.environ, "GIT_CONFIG_GLOBAL": os.devnull, "GIT_CONFIG_NOSYSTEM": "1",
       "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@invalid", "GIT_COMMITTER_NAME": "t",
       "GIT_COMMITTER_EMAIL": "t@invalid"}


def expect(cond, what):
    if not cond:
        failures.append(what)


def git(cwd, *args):
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True, check=True, env=ENV).stdout.strip()


def commit(work, files, message):
    for name, text in files.items():
        Path(work, name).parent.mkdir(parents=True, exist_ok=True)
        Path(work, name).write_text(text)
    git(work, "add", "-A")
    git(work, "commit", "-q", "-m", message)
    return git(work, "rev-parse", "HEAD")


def run(cwd, *args):
    out = subprocess.run([sys.executable, "-I", str(SCRIPT), *args], cwd=cwd, capture_output=True, text=True, env=ENV)
    return out.returncode, out.stdout.strip()


with tempfile.TemporaryDirectory() as tmp:
    origin, work = Path(tmp, "origin.git"), Path(tmp, "work")
    git(tmp, "init", "-q", "--bare", "-b", "main", str(origin))
    git(tmp, "clone", "-q", str(origin), str(work))
    base = commit(work, {"a.txt": "a\n", "shared.txt": "base\n"}, "base")
    git(work, "push", "-q", "origin", "HEAD:main")
    git(work, "switch", "-q", "-c", "feature")
    feature = commit(work, {"feature.txt": "feature\n"}, "feature")
    git(work, "push", "-q", "origin", "feature")
    git(work, "switch", "-q", "-c", "clash", base)
    clash = commit(work, {"shared.txt": "clash\n"}, "clash")
    git(work, "push", "-q", "origin", "clash")
    git(work, "switch", "-q", "main")
    main = commit(work, {"main.txt": "main\n", "shared.txt": "main\n"}, "main moved on")
    git(work, "push", "-q", "origin", "main")
    git(work, "switch", "-q", "-c", "rebased")
    rebased = commit(work, {"rebased.txt": "rebased\n"}, "rebased on main")
    git(work, "push", "-q", "origin", "rebased")

    def checkout(branch):
        """A CI checkout of `branch`: one commit deep, as actions/checkout makes it."""
        path = Path(tempfile.mkdtemp(dir=tmp))
        git(tmp, "clone", "-q", "--depth", "1", "--branch", branch, origin.as_uri(), str(path))
        return path

    # A branch made before main moved: tested merged with main, both changes in the tree.
    ci = checkout("feature")
    code, line = run(ci, main)
    parents = git(ci, "log", "-1", "--format=%P").split()
    expect(code == 0 and parents == [feature, main], f"behind main: {code} {line} parents {parents}")
    expect((ci / "feature.txt").exists() and (ci / "main.txt").exists(), "behind main: tree lacks a side")
    expect(line == f"::notice::testing {feature[:7]} merged with main {main[:7]}", f"behind main: {line}")
    expect(git(ci, "status", "--porcelain") == "", "behind main: working tree not clean")

    # A branch rebased on main: tested as it is.
    ci = checkout("rebased")
    code, line = run(ci, main)
    expect(code == 0 and git(ci, "rev-parse", "HEAD") == rebased, f"contains main: {code} {line}")
    expect(line == f"::notice::testing {rebased[:7]} as it is: it contains main {main[:7]}", f"contains main: {line}")

    # Main's commit is an older one than the branch was made from: nothing to merge either.
    ci = checkout("rebased")
    code, line = run(ci, base)
    expect(code == 0 and git(ci, "rev-parse", "HEAD") == rebased, f"older main: {code} {line}")

    # A branch that conflicts with main fails, names the file and leaves the checkout as it was.
    ci = checkout("clash")
    code, line = run(ci, main)
    expect(code == 1 and line.startswith(f"::error::{clash[:7]} conflicts with main {main[:7]} (shared.txt)"),
           f"conflict: {code} {line}")
    expect(line.endswith("rebase it on origin/main"), f"conflict says what to do: {line}")
    expect(git(ci, "rev-parse", "HEAD") == clash and git(ci, "status", "--porcelain") == "",
           "conflict: checkout changed")

    # A branch with nothing of its own, behind main: the merge is main itself, and says so.
    git(work, "push", "-q", "origin", f"{base}:refs/heads/stale")
    ci = checkout("stale")
    code, line = run(ci, main)
    expect(code == 0 and git(ci, "rev-parse", "HEAD") == main, f"nothing of its own: {code} {line}")
    expect(line == f"::notice::testing main {main[:7]}: {base[:7]} has nothing main lacks", f"nothing of its own: {line}")

    # Histories that cannot be merged without a conflicting file are not called a conflict.
    git(work, "switch", "-q", "--orphan", "orphan")
    orphan = commit(work, {"other.txt": "other\n"}, "unrelated")
    git(work, "push", "-q", "origin", "orphan")
    ci = checkout("orphan")
    code, line = run(ci, main)
    expect(code == 1 and line.startswith(f"::error::{orphan[:7]} could not be merged with main {main[:7]} (")
           and "unrelated histories" in line and line.endswith("rebase it on origin/main"), f"unrelated: {code} {line}")
    expect(git(ci, "rev-parse", "HEAD") == orphan and git(ci, "status", "--porcelain") == "", "unrelated: checkout changed")

    # Main changed CI after the branch was made: merged, with a warning that the run's steps are the
    # branch's. Not for a branch that has the same CI, nor for CI the branch changed alone.
    git(work, "switch", "-q", "main")
    ci_main = commit(work, {".github/ci.yml": "new\n"}, "main's CI moved on")
    git(work, "push", "-q", "origin", "main")
    ci = checkout("feature")
    code, line = run(ci, ci_main)
    expect(code == 0 and git(ci, "rev-parse", "HEAD^2") == ci_main, f"CI moved: {code} {line}")
    expect(line == f"::notice::testing {feature[:7]} merged with main {ci_main[:7]}\n::warning::main {ci_main[:7]} "
           f"changed CI (.github/) since {feature[:7]} was made; this run's steps are {feature[:7]}'s, without main's: "
           "rebase it on origin/main for main's checks", f"CI moved: {line!r}")
    git(work, "switch", "-q", "-c", "same-ci", feature)
    same = commit(work, {".github/ci.yml": "new\n"}, "the same CI")
    git(work, "push", "-q", "origin", "same-ci")
    code, line = run(checkout("same-ci"), ci_main)
    expect(code == 0 and line == f"::notice::testing {same[:7]} merged with main {ci_main[:7]}", f"same CI: {line!r}")
    git(work, "switch", "-q", "-c", "own-ci", base)
    own = commit(work, {".github/own.yml": "own\n"}, "own CI")
    git(work, "push", "-q", "origin", "own-ci")
    code, line = run(checkout("own-ci"), main)
    expect(code == 0 and line == f"::notice::testing {own[:7]} merged with main {main[:7]}", f"own CI only: {line!r}")

    # No commit (main itself, a fork's pull request): the checkout is tested as it is.
    for args in ([], [""]):
        ci = checkout("feature")
        code, line = run(ci, *args)
        expect(code == 0 and git(ci, "rev-parse", "HEAD") == feature, f"no commit {args}: {code} {line}")
        expect(line == f"::notice::testing {feature[:7]} as it is (nothing to merge)", f"no commit {args}: {line}")

    # A commit origin does not have fails instead of testing the branch alone.
    ci = checkout("feature")
    code, line = run(ci, "0" * 40)
    expect(code == 1 and line.startswith("::error::could not fetch main's commit 0000000"), f"unknown: {code} {line}")

# The workflow: `changes` gives main's commit on a branch only, and every job that checks out the
# code merges it before any other step reads the tree.
text = WORKFLOW.read_text()
jobs = re.split(r"\n  (?=[a-z][a-z-]*:\n)", text.split("\njobs:\n", 1)[1])
names = []
for job in jobs:
    name = job.split(":", 1)[0].strip()
    names.append(name)
    if name == "changes":
        expect(re.search(r"\n      main: \$\{\{ steps\.pick\.outputs\.main \}\}", job), "changes: no main output")
        expect('echo "main=$main"' in job, "changes: pick step does not write main")
        continue
    steps = re.split(r"\n      - ", job)[1:]
    kinds = [re.search(r"(?:^|\n        )(uses|run): ([^\n]*)", step) for step in steps]
    checkouts = [i for i, k in enumerate(kinds) if k and k.group(1) == "uses" and k.group(2).startswith("actions/checkout@")]
    # A job that does not check out the code has no tree to merge into.
    expect(len(checkouts) <= 1, f"{name}: {len(checkouts)} checkouts")
    if not checkouts or checkouts[0] + 1 >= len(steps):
        continue
    after, kind = steps[checkouts[0] + 1], kinds[checkouts[0] + 1]
    expect(kind and kind.groups() == ("run", 'python3 -I scripts/ci/merge-main.py "$MAIN"'),
           f"{name}: the step after the checkout does not merge main: {after.splitlines()[0]}")
    expect("\n          MAIN: ${{ needs.changes.outputs.main }}" in after, f"{name}: MAIN is not the changes job's main")
    needs = re.search(r"\n    needs: (\[[^\]]*\]|\S+)", job)
    expect(needs and "changes" in re.findall(r"[a-z-]+", needs.group(1)), f"{name}: does not need changes")
expect({"build", "changes", "emulator-smoke", "synonym-blocked", "update-apks", "update-probe"} <= set(names),
       f"android.yml jobs not found: {names}")
# Main's commit only for a push or run of a branch of this repository, not of main or a tag.
pick = text.split("\n  changes:\n", 1)[1]
expect('if [ "$EVENT" != pull_request ] && [[ "$REF" == refs/heads/* ]] && [ "$REF" != refs/heads/main ]; then' in pick
       and "main=$(git rev-parse origin/main)" in pick, "changes: main's commit not limited to branches")

if failures:
    print("merge-main-check: FAILED", *failures, sep="\n  ")
    sys.exit(1)
print("merge-main-check: ok")
