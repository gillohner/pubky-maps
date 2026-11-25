#!/usr/bin/env python3
"""Checks scripts/release/update-feed.py with a throwaway key: the feed names the APK's size and
SHA-256, its signature verifies over the exact feed bytes and fails for a changed feed, and a
signing key that does not match the app's public key, an unknown channel or a plain-HTTP URL are
refused. CI: android.yml, build job.

Usage: update-feed-check.py
"""
import base64
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

SCRIPT = Path(__file__).with_name("update-feed.py")
SPKI = bytes.fromhex("302a300506032b6570032100")
failures = []


def expect(cond, what):
    if not cond:
        failures.append(what)


def keypair(d: Path, name: str):
    pem = d / f"{name}.pem"
    subprocess.run(["openssl", "genpkey", "-algorithm", "ed25519", "-out", str(pem)], check=True, capture_output=True)
    der = subprocess.run(["openssl", "pkey", "-in", str(pem), "-pubout", "-outform", "DER"], check=True,
                         capture_output=True).stdout
    pub = d / f"{name}.pub"
    pub.write_text(base64.b64encode(der[-32:]).decode() + "\n")
    return pem, pub


def run(d, apk, pem, pub, channel="edge", url="https://example.org/pubky-maps.apk"):
    out = d / "feed.json"
    out.unlink(missing_ok=True)
    r = subprocess.run([sys.executable, "-I", str(SCRIPT), "--apk", str(apk), "--channel", channel, "--url", url,
                        "--package", "to.pubky.maps", "--version-code", "1000012", "--version-name", "0.1.0-edge.12",
                        "--min-sdk", "26", "--signing-key", str(pem), "--public-key", str(pub), "--out", str(out)],
                       capture_output=True, text=True)
    return r.returncode, (out.read_text() if out.exists() else ""), r.stderr


def verifies(d: Path, pub: Path, message: bytes, signature: bytes) -> bool:
    (d / "m").write_bytes(message)
    (d / "s").write_bytes(signature)
    (d / "k.der").write_bytes(SPKI + base64.b64decode(pub.read_text()))
    r = subprocess.run(["openssl", "pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER", "-inkey",
                        str(d / "k.der"), "-in", str(d / "m"), "-sigfile", str(d / "s")], capture_output=True)
    return r.returncode == 0


with tempfile.TemporaryDirectory() as tmp:
    d = Path(tmp)
    apk = d / "pubky-maps.apk"
    apk.write_bytes(b"not really an apk, but bytes with a hash")
    pem, pub = keypair(d, "release")
    other_pem, _ = keypair(d, "other")

    code, text, err = run(d, apk, pem, pub)
    expect(code == 0, f"a good feed failed: {err}")
    if code == 0:
        outer = json.loads(text)
        expect(sorted(outer) == ["feed", "signature"], f"outer keys: {sorted(outer)}")
        feed = json.loads(outer["feed"])
        expect(feed["apk"] == {"url": "https://example.org/pubky-maps.apk", "size": apk.stat().st_size,
                               "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()}, f"apk entry: {feed['apk']}")
        expect({k: feed[k] for k in ("format", "app", "channel", "versionCode", "versionName", "minSdk")} ==
               {"format": 1, "app": "to.pubky.maps", "channel": "edge", "versionCode": 1000012,
                "versionName": "0.1.0-edge.12", "minSdk": 26}, f"feed: {feed}")
        sig = base64.b64decode(outer["signature"])
        expect(len(sig) == 64, "the signature is not 64 bytes")
        expect(verifies(d, pub, outer["feed"].encode(), sig), "the signature does not verify")
        tampered = outer["feed"].replace("0.1.0-edge.12", "0.1.0-edge.13").encode()
        expect(not verifies(d, pub, tampered, sig), "the signature verifies a changed feed")

    code, _, _ = run(d, apk, other_pem, pub)
    expect(code != 0, "a signing key that does not match the public key was accepted")
    code, _, _ = run(d, apk, pem, pub, channel="nightly")
    expect(code != 0, "an unknown channel was accepted")
    code, _, _ = run(d, apk, pem, pub, url="http://example.org/pubky-maps.apk")
    expect(code != 0, "a plain-HTTP APK URL was accepted")
    code, _, err = run(d, apk, pem, pub, channel="stable")
    expect(code == 0 and '\\"channel\\":\\"stable\\"' in (d / "feed.json").read_text(), f"stable feed: {err}")

for f in failures:
    print("FAIL " + f)
if failures:
    sys.exit(1)
print("update-feed: ok")
