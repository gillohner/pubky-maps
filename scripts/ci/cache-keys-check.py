#!/usr/bin/env python3
"""Checks that each Gradle and cargo cache of CI is saved by one job only, and that the job running
the tests caches what the tests download. CI: android.yml, build job.

A cache is saved once per key, by the first job that finishes with a miss; a job that hits the key
later gets only what that first job downloaded. When the build job (check: ktlint, lint, tests)
shared its Gradle key with update-apks (release APKs only), update-apks often saved first, and every
later build fetched ktlint, lint and the test libraries from Maven Central again, where GitHub's
runners get "429 Too Many Requests". So:
- a step that saves a Gradle cache (actions/cache) uses a key that starts with gradle-<workflow>-<job>-;
  other jobs read it with actions/cache/restore;
- a cargo cache name (rust-android cache-key, rust-cache shared-key) is saved by one job; others read
  it with save: false / save-if: false;
- a job that runs the Robolectric tests (gradlew check or test...) caches the Android jars Robolectric
  downloads into ~/.m2;
- that cache's key hashes the Robolectric version (gradle/libs.versions.toml) and the Android version
  the tests run (robolectric.properties), so a new jar gets a new key instead of being downloaded on
  every run under an old one; it has no restore key, which would carry every older jar into each new
  key;
- no test asks Robolectric for another Android version than robolectric.properties (@Config sdk,
  minSdk, maxSdk), and no other robolectric.properties exists, since the key would not see either.

Usage: cache-keys-check.py [workflow.yml ...]   (default: every file in .github/workflows)
"""
import re
import sys
from pathlib import Path

ROBOLECTRIC_JARS = "~/.m2/repository/org/robolectric"
ROBOLECTRIC_PROPERTIES = "androidApp/src/test/resources/robolectric.properties"
ROBOLECTRIC_KEY_FILES = ("gradle/libs.versions.toml", ROBOLECTRIC_PROPERTIES)
TEST_SOURCES = ("androidApp/src/test*/**/*.kt", "androidApp/src/test*/**/*.java")
COMMENT = r"(?:[ \t]+#.*)?"


def unquote(text):
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "'\"":
        return text[1:-1]
    return text


def jobs(text):
    """(job id, job text) of a workflow: the keys one level under `jobs:`."""
    m = re.search(rf"(?m)^jobs:{COMMENT}[ \t]*\n", text)
    if not m:
        return []
    body = text[m.end():]
    end = re.search(r"(?m)^[^\s#]", body)
    if end:
        body = body[:end.start()]
    first = re.search(r"(?m)^([ \t]+)[A-Za-z0-9_-]+:", body)
    if not first:
        return []
    indent = re.escape(first.group(1))
    parts = re.split(rf"(?m)^{indent}([A-Za-z0-9_-]+):{COMMENT}[ \t]*$", body)
    return list(zip(parts[1::2], parts[2::2]))


def steps(job_text):
    """The text of each step of a job, at whatever indentation its `steps:` list uses."""
    m = re.search(rf"(?m)^([ \t]*)steps:{COMMENT}[ \t]*\n", job_text)
    if not m:
        return []
    body = job_text[m.end():]
    first = re.search(r"(?m)^([ \t]*)- ", body)
    if not first:
        return []
    return re.split(rf"(?m)^{re.escape(first.group(1))}- ", body)[1:]


def field(step, name):
    """The value of `name:` in a step: one line, or the lines of a block scalar (|, |-, >, ...)."""
    m = re.search(rf"(?m)^([ \t]*){re.escape(name)}:[ \t]*(.*?){COMMENT}[ \t]*$", step)
    if not m:
        return []
    head = m.group(2)
    if head and head[0] not in "|>":
        return [unquote(head)]
    lines = []
    for line in step[m.end():].split("\n")[1:]:
        if line.strip() and len(line) - len(line.lstrip()) <= len(m.group(1)):
            break
        if line.strip():
            lines.append(unquote(line))
    return lines


def value(step, name):
    lines = field(step, name)
    return lines[0] if lines else None


def gradle_tasks(job_text):
    """The arguments of every ./gradlew command in a job's run steps, continuation lines joined."""
    joined = re.sub(r"\\[ \t]*\n", " ", job_text)
    return [re.sub(r"#.*", "", m) for m in re.findall(r"\./gradlew\b([^\n]*)", joined)]


def check(workflows):
    """Problems found in {workflow name: text}."""
    problems = []
    cargo = {}
    for workflow, text in workflows.items():
        for job, job_text in jobs(text):
            where = f"{workflow}.yml job {job}"
            runs_tests = any(re.search(r"(?<![\w:-])(:?[\w-]+:)*(check|test\w*)(?![\w-])", args)
                             for args in gradle_tasks(job_text))
            cached = []
            for step in steps(job_text):
                uses = value(step, "uses") or ""
                saves = (value(step, "save") or value(step, "save-if") or "true").lower() != "false"
                if re.search(r"actions/cache(/save|/restore)?@", uses):
                    paths = field(step, "path")
                    if any(ROBOLECTRIC_JARS.startswith(p.rstrip("/")) for p in paths):
                        key = " ".join(field(step, "key"))
                        for name in ROBOLECTRIC_KEY_FILES:
                            if not re.search(rf"hashFiles\([^)]*['\"]{re.escape(name)}['\"]", key):
                                problems.append(f"{where}: the Robolectric cache key does not hash {name}: {key}")
                        if field(step, "restore-keys"):
                            problems.append(f"{where}: the Robolectric cache has restore keys")
                if re.search(r"actions/cache(/save)?@", uses):
                    paths = field(step, "path")
                    cached += paths
                    if any(".gradle" in p for p in paths):
                        prefix = f"gradle-{workflow}-{job}-"
                        key = value(step, "key") or ""
                        if not key.startswith(prefix):
                            problems.append(f"{where}: saves the Gradle cache {key!r}, whose key does not start with {prefix!r}")
                        restore = field(step, "restore-keys")
                        if restore and restore[0] != prefix:
                            problems.append(f"{where}: the first restore key {restore[0]!r} is not {prefix!r}")
                elif "actions/cache/restore@" in uses:
                    cached += field(step, "path")
                name = None
                if "./.github/actions/rust-android" in uses:
                    name = value(step, "cache-key")
                elif "Swatinem/rust-cache@" in uses:
                    name = value(step, "shared-key")
                if name and saves:
                    cargo.setdefault(name, []).append(where)
            if runs_tests and not any(ROBOLECTRIC_JARS.startswith(p.rstrip("/")) for p in cached):
                problems.append(f"{where}: runs the tests but does not cache {ROBOLECTRIC_JARS}")
    for name, users in sorted(cargo.items()):
        if len(users) > 1:
            problems.append(f"the cargo cache {name!r} is saved by {', '.join(users)}")
    return problems


def config_blocks(text):
    """The argument text of each @Config(...) annotation in a Kotlin or Java file, string literals
    blanked so that a parenthesis inside one does not end the annotation."""
    text = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', text)
    blocks = []
    for m in re.finditer(r"@(?:org\.robolectric\.annotation\.)?Config\s*\(", text):
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        blocks.append(text[m.end():i - 1])
    return blocks


def sdk_problems(properties, sources, others=()):
    """Problems with the Android version of the Robolectric tests: robolectric.properties text,
    {path: Kotlin or Java text} of the tests and the paths of any other robolectric.properties."""
    problems = [f"{other}: sets Robolectric options outside {ROBOLECTRIC_PROPERTIES}, which the cache key does not hash"
                for other in others]
    m = re.search(r"(?m)^[ \t]*sdk[ \t]*[=: \t][ \t]*(\d+(?:[ \t]*,[ \t]*\d+)*)[ \t]*\r?$", properties or "")
    if not m:
        return problems + [f"{ROBOLECTRIC_PROPERTIES}: no sdk=<number> line"]
    sdk = m.group(1).replace(" ", "").replace("\t", "")
    allowed = set(sdk.split(","))
    for path, text in sorted(sources.items()):
        for block in config_blocks(text):
            for name, values in re.findall(r"\b(sdk|minSdk|maxSdk)\s*=\s*(\[[^\]]*\]|intArrayOf\([^)]*\)|[\w.]+)", block):
                found = re.findall(r"[\w.]+", re.sub(r"^intArrayOf", "", values))
                if not found or not set(found) <= allowed:
                    problems.append(f"{path}: @Config {name} = {values} is not sdk={sdk} of {ROBOLECTRIC_PROPERTIES}")
    return problems


SHARED = """on: push
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
      - uses: actions/cache@v6
        with:
          path: ~/.m2/repository/org/robolectric
          key: robolectric-${{ hashFiles('gradle/libs.versions.toml') }}
          restore-keys: robolectric-
      - run: ./gradlew --continue check
  release: # the release APKs
    steps:
    - name: cargo
      uses: ./.github/actions/rust-android
      with:
        cache-key: "android-app"
    - name: gradle
      uses: actions/cache@v6
      with:
        path: |-
          ~/.gradle/caches/modules-2
        key: >-
          gradle-${{ hashFiles('x') }}
    - uses: actions/cache/restore@v6
      with:
        path: ~/.m2/repository/org/robolectric
        key: r-${{ hashFiles('gradle/libs.versions.toml', 'androidApp/src/test/resources/robolectric.properties') }}
        restore-keys: r-
  tests:
    steps:
      - run: |
          ./gradlew \\
            :androidApp:testDirectDebugUnitTest
"""

OWN = """on: push
jobs: # one saver per cache
  build:
    steps:
      - uses: actions/cache@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
          key: "gradle-a-build-${{ hashFiles('x') }}"
          restore-keys: |
            gradle-a-build-
            gradle-
      - uses: actions/cache@v6
        with:
          path: ~/.m2/repository
          key: >-
            m2-${{ hashFiles('gradle/libs.versions.toml',
            'androidApp/src/test/resources/robolectric.properties') }}
      - run: ./gradlew --continue check verifyRoborazziDirectDebug  # all of it
      - uses: Swatinem/rust-cache@v2
        with:
          shared-key: core-host
  release:
    steps:
      - uses: ./.github/actions/rust-android
        with:
          cache-key: core-host
          save: false
      - uses: Swatinem/rust-cache@v2
        with:
          shared-key: core-host
          save-if: 'false'
      - uses: actions/cache/restore@v6
        with:
          path: |
            ~/.gradle/caches/modules-2
          key: gradle-a-build-${{ hashFiles('x') }}
      - run: ./gradlew assembleRelease -Pnot-a-check=1
"""


def self_check():
    """The check finds each problem in SHARED, written in several YAML forms, and none in OWN."""
    found = check({"a": SHARED})
    failures = []
    for expected in ("a.yml job build: saves the Gradle cache", "a.yml job release: saves the Gradle cache",
                     "a.yml job build: the first restore key", "a.yml job tests: runs the tests but does not cache",
                     f"a.yml job build: the Robolectric cache key does not hash {ROBOLECTRIC_PROPERTIES}",
                     "a.yml job build: the Robolectric cache has restore keys",
                     "a.yml job release: the Robolectric cache has restore keys",
                     "the cargo cache 'android-app' is saved by a.yml job build, a.yml job release"):
        if not any(expected in p for p in found):
            failures.append(f"self-check: not found: {expected} (found {found})")
    if len(found) != 8:
        failures.append(f"self-check: expected 8 problems, found {found}")
    wrong = check({"a": OWN})
    if wrong:
        failures.append(f"self-check: a correct workflow was refused: {wrong}")
    sources = {
        "A.kt": "@RunWith(X::class)\n@Config(qualifiers = \"w412dp-h915dp\", sdk = [36])\nclass A",
        "B.kt": "@Config(\n    sdk = [35],\n    qualifiers = q(\"x\"),\n)\nclass B",
        "C.kt": "@Config(minSdk = 34)\nclass C\n@Config(sdk = intArrayOf(36, 37))\nfun c() {}",
        "D.kt": "@Config(sdk = [Build.VERSION_CODES.BAKLAVA])\nclass D",
        "E.kt": "val sdk = [35]\n@Config(qualifiers = PHONE)\nclass E",
        "F.kt": "@org.robolectric.annotation.Config(qualifiers = \"a)b\", sdk = [35])\nclass F",
        "G.java": "@Config(sdk = 34)\nclass G {}",
    }
    found = sdk_problems("# tests\r\nsdk=36\r\n", sources, ["x/robolectric.properties"])
    expected = ["x/robolectric.properties: sets Robolectric options outside", "B.kt: @Config sdk = [35]",
                "C.kt: @Config minSdk = 34", "C.kt: @Config sdk = intArrayOf(36, 37)",
                "D.kt: @Config sdk = [Build.VERSION_CODES.BAKLAVA]", "F.kt: @Config sdk = [35]", "G.java: @Config sdk = 34"]
    found = [p if " is not " in p else p.split(" " + ROBOLECTRIC_PROPERTIES)[0] for p in found]
    if [p.split(" is not ")[0] for p in found] != expected:
        failures.append(f"self-check: Robolectric SDK problems {found}, expected {expected}")
    if sdk_problems("sdk=", {}) != [f"{ROBOLECTRIC_PROPERTIES}: no sdk=<number> line"]:
        failures.append("self-check: a robolectric.properties without an sdk was accepted")
    if sdk_problems("sdk = 35, 36", {"H.kt": "@Config(sdk = [35, 36])\nclass H"}):
        failures.append("self-check: tests on the SDKs of robolectric.properties were refused")
    return failures


def main(args):
    files = [Path(a) for a in args] or sorted(Path(".github/workflows").glob("*.yml"))
    properties = Path(ROBOLECTRIC_PROPERTIES)
    sources = {str(f): f.read_text() for pattern in TEST_SOURCES for f in sorted(Path(".").glob(pattern))}
    others = [str(f) for f in sorted(Path("androidApp/src").glob("**/robolectric.properties")) if f != properties]
    failures = (self_check() + check({f.stem: f.read_text() for f in files})
                + sdk_problems(properties.read_text() if properties.exists() else None, sources, others))
    for f in failures:
        print("FAIL " + f)
    if failures:
        return 1
    print(f"cache keys: ok ({len(files)} workflows)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
