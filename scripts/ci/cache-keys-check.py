#!/usr/bin/env python3
"""Checks that no two CI jobs share a Gradle or cargo cache, and that the jobs running the tests
cache what the tests download. CI: android.yml, build job.

A cache is saved once per key, by the first job that finishes with a miss; a job that hits the key
later gets only what that first job downloaded. A job may read another job's cache without saving
it (rust-cache with save-if: false). When the build job (check: ktlint, lint, tests)
shared its Gradle key with update-apks (release APKs only), update-apks often saved first, and every
later build fetched ktlint, lint and the test libraries from Maven Central again, where GitHub's
runners get "429 Too Many Requests". So each Gradle cache key starts with
gradle-<workflow>-<job>-, each cargo cache name belongs to one job, and a job that runs Robolectric
tests (gradlew check) caches the Android jars Robolectric downloads into ~/.m2.

Usage: cache-keys-check.py [workflow.yml ...]   (default: every file in .github/workflows)
"""
import re
import sys
from pathlib import Path

ROBOLECTRIC_JARS = "~/.m2/repository/org/robolectric"


def jobs(text):
    """(job id, job text) of a workflow, read by indentation (jobs at two spaces under `jobs:`)."""
    body = text.split("\njobs:\n", 1)
    if len(body) < 2:
        return []
    parts = re.split(r"(?m)^  ([A-Za-z0-9_-]+):[ \t]*$", body[1])
    return list(zip(parts[1::2], parts[2::2]))


def steps(job_text):
    """The text of each step of a job (steps at six spaces)."""
    return re.split(r"(?m)^      - ", job_text)[1:]


def value(step, name):
    m = re.search(rf"(?m)^\s+{re.escape(name)}:[ \t]*(.+?)[ \t]*$", step)
    return m.group(1) if m else None


def block(step, name):
    """The lines of a `name: |` block, or its single value."""
    m = re.search(rf"(?m)^(\s+){re.escape(name)}:[ \t]*\|?[ \t]*\n((?:\1\s+.*\n?)*)", step)
    if m:
        return [line.strip() for line in m.group(2).splitlines() if line.strip()]
    one = value(step, name)
    return [one] if one else []


def check(workflows):
    """Problems found in {workflow name: text}."""
    problems = []
    cargo = {}
    for workflow, text in workflows.items():
        for job, job_text in jobs(text):
            where = f"{workflow}.yml job {job}"
            runs_tests = re.search(r"\./gradlew\b[^\n]*\bcheck\b", job_text) is not None
            cached = []
            for step in steps(job_text):
                uses = value(step, "uses") or step.split("\n", 1)[0]
                if "actions/cache@" in uses:
                    paths = block(step, "path")
                    cached += paths
                    if not any(".gradle" in p for p in paths):
                        continue
                    prefix = f"gradle-{workflow}-{job}-"
                    key = value(step, "key") or ""
                    if not key.startswith(prefix):
                        problems.append(f"{where}: the Gradle cache key {key!r} does not start with {prefix!r}")
                    restore = block(step, "restore-keys")
                    if restore and restore[0] != prefix:
                        problems.append(f"{where}: the first restore key {restore[0]!r} is not {prefix!r}")
                name = None
                if "./.github/actions/rust-android" in uses:
                    name = value(step, "cache-key")
                elif "Swatinem/rust-cache@" in uses and value(step, "save-if") != "false":
                    name = value(step, "shared-key")
                if name:
                    cargo.setdefault(name, []).append(where)
            if runs_tests and ROBOLECTRIC_JARS not in cached:
                problems.append(f"{where}: runs the tests but does not cache {ROBOLECTRIC_JARS}")
    for name, users in sorted(cargo.items()):
        if len(users) > 1:
            problems.append(f"the cargo cache {name!r} is shared by {', '.join(users)}")
    return problems


def self_check():
    """The check itself finds a shared Gradle key, a shared cargo cache and uncached Robolectric jars."""
    shared = """on: push
jobs:
  build:
    steps:
      - uses: ./.github/actions/rust-android
        with:
          cache-key: android-app
      - uses: actions/cache@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
          key: gradle-${{ hashFiles('x') }}
          restore-keys: gradle-
      - run: ./gradlew check
  release:
    steps:
      - uses: ./.github/actions/rust-android
        with:
          cache-key: android-app
      - uses: actions/cache@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
          key: gradle-${{ hashFiles('x') }}
          restore-keys: gradle-
"""
    own = """on: push
jobs:
  build:
    steps:
      - uses: actions/cache@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
          key: gradle-a-build-${{ hashFiles('x') }}
          restore-keys: |
            gradle-a-build-
            gradle-
      - uses: actions/cache@v6
        with:
          path: ~/.m2/repository/org/robolectric
          key: robolectric-${{ hashFiles('x') }}
      - run: ./gradlew --continue check
      - uses: Swatinem/rust-cache@v2
        with:
          shared-key: core-host
  reader:
    steps:
      - uses: Swatinem/rust-cache@v2
        with:
          shared-key: core-host
          save-if: false
"""
    found = check({"a": shared})
    failures = []
    for expected in ("job build: the Gradle cache key", "job release: the Gradle cache key",
                     "job build: the first restore key", "job build: runs the tests but does not cache",
                     "the cargo cache 'android-app' is shared by a.yml job build, a.yml job release"):
        if not any(expected in p for p in found):
            failures.append(f"self-check: not found: {expected} (found {found})")
    if check({"a": own}):
        failures.append(f"self-check: a correct workflow was refused: {check({'a': own})}")
    return failures


def main(args):
    files = [Path(a) for a in args] or sorted(Path(".github/workflows").glob("*.yml"))
    failures = self_check() + check({f.stem: f.read_text() for f in files})
    for f in failures:
        print("FAIL " + f)
    if failures:
        return 1
    print(f"cache keys: ok ({len(files)} workflows)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
