#!/usr/bin/env python3
"""Checks scripts/release/edge-commit.py: an edge run builds main's head when main moved on with the
same release.yml, and keeps its own commit otherwise; its GitHub calls (against a fake `gh`) compare
in the right direction and fall back to its own commit when GitHub cannot be asked. CI: android.yml,
build job.

Usage: edge-commit-check.py
"""
import importlib.util
import os
import subprocess
import sys
import tempfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("edge_commit", Path(__file__).with_name("edge-commit.py"))
edge_commit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(edge_commit)
choose = edge_commit.choose
failures = []
old, new, workflow, other = "3320f35" + "a" * 33, "2f1fe8f" + "b" * 33, "w" * 40, "x" * 40


def expect(cond, what):
    if not cond:
        failures.append(what)


# 11-26: the run of 3320f35 started after main had moved on to 2f1fe8f (same release.yml).
sha, why = choose(old, new, "ahead", workflow, workflow)
expect(sha == new and why.startswith("notice::"), f"main moved on: {sha[:7]} {why}")
sha, why = choose(new, new, "identical", workflow, workflow)
expect(sha == new and why.startswith("notice::"), f"run of main's head: {sha[:7]} {why}")
sha, why = choose(old, new, "ahead", workflow, other)
expect(sha == old and why.startswith("warning::"), f"another release.yml at main's head: {sha[:7]} {why}")
for status in ("behind", "diverged"):
    sha, why = choose(old, new, status, workflow, workflow)
    expect(sha == old and why.startswith("warning::"), f"main's head {status}: {sha[:7]} {why}")

# main() against a fake `gh`: GitHub's answers for main at `new`, one commit after `old`.
FAKE_GH = f"""#!/usr/bin/env python3
import sys
path = sys.argv[2]
if "FAIL" in __import__("os").environ:
    sys.exit("gh: HTTP 503")
answers = {{
    "repos/o/r/commits/main": "{new}",
    "repos/o/r/compare/{old}...{new}": "ahead",
    "repos/o/r/compare/{new}...{old}": "behind",
    "repos/o/r/contents/.github/workflows/release.yml?ref={old}": "{workflow}",
    "repos/o/r/contents/.github/workflows/release.yml?ref={new}": "{workflow}",
}}
if path not in answers:
    sys.exit("gh: no answer for " + path)
print(answers[path])
"""


def run_main(run_sha, **env):
    with tempfile.TemporaryDirectory() as bin_dir:
        gh = Path(bin_dir, "gh")
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        out = subprocess.run([sys.executable, "-I", str(Path(__file__).with_name("edge-commit.py")), "o/r", run_sha],
                             capture_output=True, text=True, env={**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}", **env})
    return out.returncode, out.stdout, out.stderr


code, out, err = run_main(old)
expect(code == 0 and out == f"sha={new}\n" and "notice::" in err, f"main() of an older run: {code} {out!r} {err!r}")
code, out, err = run_main(new)
expect(code == 0 and out == f"sha={new}\n", f"main() of main's head: {code} {out!r} {err!r}")
code, out, err = run_main(old, FAIL="1")
expect(code == 0 and out == f"sha={old}\n" and "HTTP 503" in err, f"main() without GitHub: {code} {out!r} {err!r}")

if failures:
    for f in failures:
        print(f"::error::edge-commit: {f}")
    sys.exit(1)
print("edge-commit: ok")
