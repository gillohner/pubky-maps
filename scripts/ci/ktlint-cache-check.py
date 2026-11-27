#!/usr/bin/env python3
"""Checks that ktlint gives each checkout of this repository its own results when checkouts share a
Gradle build cache, as several worktrees on one machine share ~/.gradle/caches/build-cache-1.
CI: android.yml, build job.

ktlint's lint and format tasks keep the absolute path of every file they read, and on their next run
read again each such file that exists. A result taken from the build cache names the files of the
checkout that stored it, so a check in one checkout failed on another checkout's half-written file,
and a format served from the cache left the files unformatted. build.gradle.kts keeps both tasks out
of the cache. This script copies the working tree twice (A and B, sharing a new, empty build cache),
puts the same badly formatted file into both, and:
1. formats and checks A;
2. formats and checks B: B's file must come out formatted, not as the cache left it;
3. breaks A's file and changes B's, still well formatted: B's check must pass and name no file of A.

Usage: ktlint-cache-check.py
"""
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PROBE = Path("shared/src/commonTest/kotlin/KtlintCacheProbe.kt")
FORMAT = ":shared:runKtlintFormatOverCommonTestSourceSet"
CHECK = ":shared:ktlintCommonTestSourceSetCheck"
BADLY_FORMATTED = "val ktlintCacheProbe   =    1\n"
FORMATTED = "val ktlintCacheProbe = 1\n"
CHANGED = "val ktlintCacheProbe = 2\n"
BROKEN = "val ktlintCacheProbe   =    3\n"
# local.properties is not in git but names the Android SDK on a machine without ANDROID_HOME.
UNTRACKED_NEEDED = ("local.properties",)


def fail(message, output=""):
    if output:
        print(output[-6000:], file=sys.stderr)
    print(f"ktlint-cache-check: {message}", file=sys.stderr)
    sys.exit(1)


def copy_tree(dest):
    listed = subprocess.run(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
        cwd=ROOT,
        check=True,
        capture_output=True,
    ).stdout.decode()
    names = [name for name in listed.split("\0") if name] + list(UNTRACKED_NEEDED)
    for name in names:
        source = ROOT / name
        if not source.is_file():
            continue  # deleted in the working tree, or no local.properties
        target = dest / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)


def gradle(checkout, init_script, *tasks):
    """Runs tasks in one checkout; returns (ok, output)."""
    result = subprocess.run(
        [
            str(checkout / "gradlew"),
            "--build-cache",
            "--init-script",
            str(init_script),
            "--console=plain",
            *tasks,
        ],
        cwd=checkout,
        capture_output=True,
        text=True,
    )
    return result.returncode == 0, result.stdout + result.stderr


def main():
    with tempfile.TemporaryDirectory(prefix="ktlint-cache-check-") as temp:
        temp = Path(temp)
        cache = temp / "build-cache"
        init_script = temp / "build-cache.init.gradle"
        init_script.write_text(
            "settingsEvaluated { settings ->\n"
            f"    settings.buildCache.local.directory = new File('{cache.as_posix()}')\n"
            "}\n"
        )
        a, b = temp / "a", temp / "b"
        for checkout in (a, b):
            copy_tree(checkout)
            (checkout / PROBE).write_text(BADLY_FORMATTED)

        ok, output = gradle(a, init_script, FORMAT)
        if not ok:
            fail("formatting checkout A failed", output)
        if (a / PROBE).read_text() != FORMATTED:
            fail(f"ktlint did not format A's {PROBE} as this script expects: {(a / PROBE).read_text()!r}")
        ok, output = gradle(a, init_script, CHECK)
        if not ok:
            fail("checking checkout A failed after its format", output)
        if not any(cache.iterdir()):
            fail("checkout A stored nothing in the build cache, so this check tests nothing", output)

        ok, output = gradle(b, init_script, FORMAT)
        if not ok:
            fail("formatting checkout B failed", output)
        if (b / PROBE).read_text() != FORMATTED:
            fail(
                f"B's {PROBE} was left unformatted: its format came from A's cache entry "
                f"({FORMAT} {'FROM-CACHE' if 'FROM-CACHE' in output else 'ran'})",
                output,
            )
        ok, output = gradle(b, init_script, CHECK)
        if not ok:
            fail("checking checkout B failed after its format", output)

        (a / PROBE).write_text(BROKEN)
        (b / PROBE).write_text(CHANGED)
        ok, output = gradle(b, init_script, CHECK)
        if not ok or f"{a}{os.sep}" in output:
            fail(f"B's check read A's {PROBE} (half-written in A, fine in B)", output)
    print("ktlint-cache-check: two checkouts sharing a build cache each lint and format their own files")


if __name__ == "__main__":
    main()
