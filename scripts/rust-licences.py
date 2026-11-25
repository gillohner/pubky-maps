#!/usr/bin/env python3
"""The Rust crates linked into libpubky_maps_core.so, with their licences, as an AboutLibraries
JSON document (the format of the app's generated aboutlibraries.json), so the app's open-source
licence screen lists them next to the Gradle libraries.

The crates are the normal (not build, not dev) dependencies of pubky-maps-core for the Android
targets the app ships, without proc-macros (they run in the compiler and are not linked). Every
crate gets its licence expression and the text of its own licence files (LICENSE*, COPYING*,
NOTICE*), which carry its copyright lines. The standard Apache-2.0 text is long and the same in
every crate, so it is stored once and shared.

Usage: rust-licences.py <out file>     (run from anywhere; needs cargo and Cargo.lock)
"""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGETS = ["aarch64-linux-android", "x86_64-linux-android"]
CORE = "pubky-maps-core"
LICENCE_FILE = re.compile(r"^(LICEN[CS]E|COPYING|NOTICE|UNLICENSE)", re.I)
APACHE_KEY = "Apache-2.0"


def metadata(target):
    out = subprocess.run(
        ["cargo", "metadata", "--format-version", "1", "--locked", "--filter-platform", target],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout
    return json.loads(out)


def shipped(meta):
    packages = {p["id"]: p for p in meta["packages"]}
    nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
    root = next(p["id"] for p in meta["packages"] if p["name"] == CORE)
    seen, stack = set(), [root]
    while stack:
        pid = stack.pop()
        if pid in seen:
            continue
        seen.add(pid)
        for dep in nodes[pid]["deps"]:
            if any(kind["kind"] is None for kind in dep["dep_kinds"]):
                stack.append(dep["pkg"])
    seen.discard(root)
    return [packages[pid] for pid in seen
            if not any("proc-macro" in t["kind"] for t in packages[pid]["targets"])]


def normalised(text):
    return re.sub(r"\s+", " ", text).strip()


def is_standard_apache(text):
    """The Apache-2.0 licence text alone, naming no copyright holder (a file that appends other
    licences or notices after the terms keeps its own text)."""
    t = normalised(text)
    if "Apache License Version 2.0, January 2004" not in t or "END OF TERMS AND CONDITIONS" not in t:
        return False
    tail = t.split("END OF TERMS AND CONDITIONS", 1)[1]
    tail = tail.replace("Copyright [yyyy] [name of copyright owner]", "").replace("Copyright {yyyy} {name of copyright owner}", "")
    return len(tail) < 2500 and "Copyright" not in tail


def main():
    out = Path(sys.argv[1])
    crates = {}
    for target in TARGETS:
        for package in shipped(metadata(target)):
            crates[(package["name"], package["version"])] = package

    licences = {APACHE_KEY: {"name": "Apache License 2.0", "url": "https://www.apache.org/licenses/LICENSE-2.0",
                             "spdxId": "Apache-2.0", "hash": APACHE_KEY}}
    apache_text = None
    libraries = []
    for (name, version), package in sorted(crates.items()):
        expression = package.get("license") or "see the crate's licence files"
        keys = []
        texts = []
        for f in sorted(Path(package["manifest_path"]).parent.iterdir()):
            if not f.is_file() or not LICENCE_FILE.match(f.name):
                continue
            text = f.read_text(errors="replace")
            if is_standard_apache(text):
                apache_text = apache_text or text
                if APACHE_KEY not in keys:
                    keys.append(APACHE_KEY)
            else:
                texts.append(text.strip())
        if "Apache-2.0" in expression and APACHE_KEY not in keys:
            keys.append(APACHE_KEY)
        if texts:
            content = "\n\n".join(texts)
            key = "rust-" + hashlib.sha256((expression + "\n" + content).encode()).hexdigest()[:16]
            licences.setdefault(key, {"name": expression, "url": "", "hash": key, "content": content})
            keys.insert(0, key)
        elif not keys:
            # The crate ships no licence file: name its licence and link the SPDX text.
            first = re.findall(r"[A-Za-z0-9.+-]+", expression)
            url = f"https://spdx.org/licenses/{first[0]}.html" if first else "https://spdx.org/licenses/"
            key = "rust-spdx-" + hashlib.sha256(expression.encode()).hexdigest()[:12]
            licences.setdefault(key, {"name": expression, "url": url, "hash": key})
            keys.append(key)
        website = package.get("homepage") or package.get("repository") or f"https://crates.io/crates/{name}"
        authors = [re.sub(r"\s*<[^>]*>", "", a).strip() for a in package.get("authors") or []]
        libraries.append({
            "uniqueId": f"crates.io:{name}",
            "artifactVersion": version,
            "name": name,
            "description": (package.get("description") or "").strip(),
            "website": website,
            "scm": {"url": package.get("repository") or website},
            "developers": [{"name": a} for a in authors if a],
            "licenses": keys,
        })
    if apache_text:
        licences[APACHE_KEY]["content"] = apache_text
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps({"libraries": libraries, "licenses": licences}, ensure_ascii=False, separators=(",", ":")))
    print(f"{len(libraries)} Rust crates, {len(licences)} licence texts, {out.stat().st_size} bytes -> {out}")


main()
