#!/usr/bin/env python3
"""Merges main into the checkout of a branch, so its CI tests what main becomes when it merges.

A branch's CI used to test the branch alone. Two pull requests, each green on its own head, then made
main red once both were merged: a test that no longer compiled against the other's new parameter
(main cc4ac7e, 11-27) and screenshots recorded before the other's change to the same screen (main
6236636, 11-27; d8c50b1 and 21a4ba3, 11-28). Every later branch made from main was red too until one
of them re-recorded the shots. Tested merged with main, a run of a branch that main has moved under
goes red, and running all its jobs again after a merge to main tests it against the new main ("Re-run
failed jobs" keeps the main commit of the first attempt). A run says nothing about a later main: the
"merge main" notice names the main commit a run tested.

android.yml's `changes` job picks main's commit once per run (its `main` output); every job merges
that same commit right after its checkout, so the APK the build job makes and the scripts the
emulator jobs run come from one tree. Main itself, tags and pull requests from forks (GitHub already
checks out their merge with the base) pass no commit and test their checkout as it is.

A branch that already contains that commit is tested as it is. A branch that conflicts with main
fails here: it has to be rebased before anything else can say whether it works. A merge that brings
in main's newer CI (.github/) is made but warned about: GitHub runs the workflow of the branch's own
commit, so the run has the branch's older steps and leaves out any check main added since. Such a run
fails on its own when main changed how a script is called; failing every one would make each open
branch rebase whenever main's CI changes (about one merge in six), so the warning names it instead.

Usage: merge-main.py [<main's commit>]   (no commit or an empty one: nothing to merge)
"""
import subprocess
import sys

# GitHub reads a run's workflow and local actions from the commit that started it.
CI = ".github/"


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=check)


def merge_main(main: str) -> tuple[int, str]:
    """Merges `main` into HEAD. Returns (exit code, GitHub annotation lines saying what was tested)."""
    head = git("rev-parse", "HEAD").stdout.strip()
    if not main:
        return 0, f"::notice::testing {head[:7]} as it is (nothing to merge)"
    # The checkout is shallow: the merge needs both histories down to where they meet.
    shallow = git("rev-parse", "--is-shallow-repository").stdout.strip() == "true"
    fetch = git("fetch", "--no-tags", "--quiet", *(["--unshallow"] if shallow else []), "origin", main, check=False)
    if fetch.returncode != 0:
        return 1, f"::error::could not fetch main's commit {main[:7]}: {fetch.stderr.strip()}"
    main = git("rev-parse", "FETCH_HEAD").stdout.strip()
    if git("merge-base", "--is-ancestor", main, head, check=False).returncode == 0:
        return 0, f"::notice::testing {head[:7]} as it is: it contains main {main[:7]}"
    # Main changed its CI since the branch was made, and the branch has not got the same CI.
    base = git("merge-base", head, main, check=False).stdout.strip()
    ci_moved = bool(base) and all(
        git("diff", "--quiet", a, main, "--", CI, check=False).returncode != 0 for a in (base, head)
    )
    merge = git("-c", "user.name=CI", "-c", "user.email=ci@invalid", "merge", "--no-edit",
                "-m", f"CI: {head[:7]} merged with main {main[:7]}", main, check=False)
    if merge.returncode != 0:
        conflicts = git("diff", "--name-only", "--diff-filter=U", check=False).stdout.split()
        git("merge", "--abort", check=False)
        if conflicts:
            return 1, f"::error::{head[:7]} conflicts with main {main[:7]} ({', '.join(conflicts)}): rebase it on origin/main"
        why = (merge.stderr.strip() or merge.stdout.strip()).replace("\n", " ")
        return 1, f"::error::{head[:7]} could not be merged with main {main[:7]} ({why}): rebase it on origin/main"
    if git("rev-parse", "HEAD").stdout.strip() == main:
        line = f"::notice::testing main {main[:7]}: {head[:7]} has nothing main lacks"
    else:
        line = f"::notice::testing {head[:7]} merged with main {main[:7]}"
    if ci_moved:
        line += (f"\n::warning::main {main[:7]} changed CI ({CI}) since {head[:7]} was made; this run's steps are "
                 f"{head[:7]}'s, without main's: rebase it on origin/main for main's checks")
    return 0, line


if __name__ == "__main__":
    code, line = merge_main(sys.argv[1] if len(sys.argv) > 1 else "")
    print(line)
    sys.exit(code)
