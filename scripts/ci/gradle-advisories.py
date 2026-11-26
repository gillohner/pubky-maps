#!/usr/bin/env python3
"""Checks the Maven libraries the app ships against the advisories of OSV (https://osv.dev, keyless;
for Maven it carries GitHub's advisory database). CI: .github/workflows/gradle-advisories.yml.

The libraries are those of the release APK and bundle of both flavours, as Gradle resolved them
(`./gradlew :androidApp:listShippedLibraries`, group:name:version per line); test, lint and
build-tool libraries are not shipped and not checked. A library with an advisory fails, unless
gradle/advisory-ignores.toml ignores that advisory with a reason. An ignore that matches no shipped
library any more fails too, so it is dropped once its cause is gone. A withdrawn advisory does not
count. When OSV cannot be reached after a few tries the check fails: no answer is not a clean answer.

Usage: gradle-advisories.py LIBRARIES_TXT [IGNORES_TOML]
"""
import http.client
import json
import sys
import time
import tomllib
import urllib.error
import urllib.request
from pathlib import Path

OSV = "https://api.osv.dev/v1"
IGNORES = Path(__file__).resolve().parents[2] / "gradle" / "advisory-ignores.toml"
BATCH = 1000  # OSV's most queries in one querybatch request
TRIES = 4


def libraries(text):
    """(group:name, version) of each `group:name:version` line; blank lines and # comments skipped."""
    out = []
    for n, line in enumerate(text.splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(":")
        if len(parts) != 3 or not all(parts):
            raise ValueError(f"line {n}: '{line}' is not group:name:version")
        out.append((f"{parts[0]}:{parts[1]}", parts[2]))
    return out


def ignores(text):
    """{advisory id: reason} of the [[ignore]] tables; each needs an id and a reason, once."""
    out = {}
    data = tomllib.loads(text)
    unknown = set(data) - {"ignore"}
    if unknown:
        raise ValueError(f"unknown keys {sorted(unknown)}; only [[ignore]] tables")
    for entry in data.get("ignore", []):
        advisory, reason = entry.get("id"), entry.get("reason")
        if not isinstance(advisory, str) or not advisory.strip():
            raise ValueError(f"an ignore without an id: {entry}")
        if not isinstance(reason, str) or not reason.strip():
            raise ValueError(f"ignore {advisory} has no reason")
        if set(entry) - {"id", "reason"}:
            raise ValueError(f"ignore {advisory} has unknown keys {sorted(set(entry) - {'id', 'reason'})}")
        if advisory in out:
            raise ValueError(f"ignore {advisory} is there twice")
        out[advisory] = reason
    return out


def request(url, body=None, urlopen=urllib.request.urlopen, sleep=time.sleep):
    """The JSON answer of OSV, tried up to TRIES times on a 429/5xx, a lost connection or a cut-off answer."""
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json", "User-Agent": "pubky-maps-ci"}
    for attempt in range(1, TRIES + 1):
        try:
            with urlopen(urllib.request.Request(url, data=data, headers=headers), timeout=60) as r:
                return json.load(r)
        except urllib.error.HTTPError as e:
            if (e.code != 429 and e.code < 500) or attempt == TRIES:
                raise
        except (OSError, http.client.HTTPException, json.JSONDecodeError):
            if attempt == TRIES:
                raise
        sleep(5 * attempt)
    raise AssertionError("unreachable")


def advisory_ids(libs, post):
    """{(name, version): [advisory id, ...]} from OSV's querybatch, following its page tokens."""
    found = {}
    pending = [(lib, None) for lib in libs]
    while pending:
        chunk, pending = pending[:BATCH], pending[BATCH:]
        queries = []
        for (name, version), token in chunk:
            query = {"package": {"ecosystem": "Maven", "name": name}, "version": version}
            if token:
                query["page_token"] = token
            queries.append(query)
        results = post(f"{OSV}/querybatch", {"queries": queries}).get("results", [])
        if len(results) != len(chunk):
            raise ValueError(f"OSV answered {len(results)} results to {len(chunk)} queries")
        for (lib, _), result in zip(chunk, results):
            ids = [v["id"] for v in result.get("vulns", [])]
            if ids:
                found.setdefault(lib, []).extend(ids)
            if result.get("next_page_token"):
                pending.append((lib, result["next_page_token"]))
    return found


def evaluate(found, details, ignored):
    """(problems, notes): advisories of shipped libraries not ignored, and stale ignores, as lines."""
    problems, notes = [], []
    used = set()
    for (name, version), ids in sorted(found.items()):
        for advisory in sorted(set(ids)):
            vuln = details[advisory]
            if vuln.get("withdrawn"):
                notes.append(f"{name}:{version}: {advisory} is withdrawn")
                continue
            names = [advisory] + list(vuln.get("aliases") or [])
            hit = next((i for i in names if i in ignored), None)
            severity = (vuln.get("database_specific") or {}).get("severity") or "no severity"
            line = f"{name}:{version}: {advisory} ({severity}) {vuln.get('summary') or ''}".rstrip()
            if hit:
                used.add(hit)
                notes.append(f"{line} - ignored: {ignored[hit]}")
            else:
                problems.append(f"{line} - https://osv.dev/vulnerability/{advisory}")
    for advisory in sorted(set(ignored) - used):
        problems.append(f"gradle/advisory-ignores.toml: {advisory} matches no shipped library any more; drop it")
    return problems, notes


def check(libs, ignored, post, get):
    found = advisory_ids(libs, post)
    details = {a: get(f"{OSV}/vulns/{a}") for a in sorted({a for ids in found.values() for a in ids})}
    return evaluate(found, details, ignored)


def main(argv):
    if len(argv) not in (2, 3):
        print(__doc__.strip().splitlines()[-1], file=sys.stderr)
        return 2
    libs = libraries(Path(argv[1]).read_text())
    if not libs:
        print(f"{argv[1]} lists no library", file=sys.stderr)
        return 1
    ignore_file = Path(argv[2]) if len(argv) == 3 else IGNORES
    ignored = ignores(ignore_file.read_text()) if ignore_file.exists() else {}
    problems, notes = check(libs, ignored, request, request)
    for line in notes:
        print(line)
    for line in problems:
        print(f"::error::{line}")
    print(f"{len(libs)} libraries, {len(problems)} problems, {len(notes)} ignored or withdrawn")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
