#!/usr/bin/env python3
"""Writes the signed update feed for one release APK.

The file holds the feed document as a string and an Ed25519 signature over its exact UTF-8 bytes:

    {"feed": "{\"format\":1,\"app\":\"to.pubky.maps\",\"channel\":\"edge\",...}", "signature": "<base64>"}

The app (shared/.../update/UpdateFeed.kt) checks the signature with the public key it embeds
(release/update-feed-key.pub), then the feed: app id, channel, versionCode, and the APK's size and
SHA-256 before it installs anything. Signing uses OpenSSL (Ed25519, raw input); the signature is
checked against the public key before the file is written, so a signing key that does not match the
key in the app fails the release instead of publishing a feed no app accepts.

Usage: update-feed.py --apk <signed apk> --channel edge|stable|dev --url <APK download URL>
         --package <id> --version-code <n> --version-name <name> --min-sdk <api>
         --signing-key <Ed25519 PEM file> --public-key <base64 public key file> --out <feed file>
"""
import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

# DER prefix of an Ed25519 SubjectPublicKeyInfo; the raw 32-byte key follows.
ED25519_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
FEED_NAMES = {"edge": "update-edge.json", "stable": "update.json", "dev": "update-dev.json"}


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def openssl(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["openssl", *args], capture_output=True)


def main() -> None:
    p = argparse.ArgumentParser()
    for name in ("apk", "channel", "url", "package", "version-code", "version-name", "min-sdk",
                 "signing-key", "public-key", "out"):
        p.add_argument(f"--{name}", required=True)
    a = p.parse_args()

    if a.channel not in FEED_NAMES:
        sys.exit(f"update-feed: unknown channel {a.channel!r}")
    if not re.fullmatch(r"[1-9][0-9]*", a.version_code) or not re.fullmatch(r"[1-9][0-9]*", a.min_sdk):
        sys.exit("update-feed: --version-code and --min-sdk must be positive numbers")
    if not (a.url.startswith("https://") or a.url.startswith("http://127.0.0.1:")):
        sys.exit(f"update-feed: the APK URL must be HTTPS: {a.url}")
    apk = Path(a.apk)
    public = base64.b64decode(Path(a.public_key).read_text().strip(), validate=True)
    if len(public) != 32:
        sys.exit("update-feed: the public key is not 32 bytes")

    feed = {
        "format": 1,
        "app": a.package,
        "channel": a.channel,
        "versionCode": int(a.version_code),
        "versionName": a.version_name,
        "minSdk": int(a.min_sdk),
        # For people reading the feed; the app ignores it.
        "published": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "apk": {"url": a.url, "size": apk.stat().st_size, "sha256": sha256(apk)},
    }
    document = json.dumps(feed, separators=(",", ":"), ensure_ascii=False)

    with tempfile.TemporaryDirectory() as tmp:
        msg, sig, pub = Path(tmp, "feed"), Path(tmp, "sig"), Path(tmp, "pub.der")
        msg.write_bytes(document.encode("utf-8"))
        pub.write_bytes(ED25519_SPKI_PREFIX + public)
        r = openssl("pkeyutl", "-sign", "-rawin", "-inkey", a.signing_key, "-in", str(msg), "-out", str(sig))
        if r.returncode != 0:
            sys.exit("update-feed: signing failed: " + r.stderr.decode(errors="replace").strip())
        r = openssl("pkeyutl", "-verify", "-rawin", "-pubin", "-keyform", "DER", "-inkey", str(pub),
                    "-in", str(msg), "-sigfile", str(sig))
        if r.returncode != 0:
            sys.exit("update-feed: the signing key does not match the public key the app embeds")
        signature = base64.b64encode(sig.read_bytes()).decode()

    out = Path(a.out)
    if out.name != FEED_NAMES[a.channel]:
        print(f"update-feed: note: the {a.channel} feed is published as {FEED_NAMES[a.channel]}", file=sys.stderr)
    out.write_text(json.dumps({"feed": document, "signature": signature}, indent=2) + "\n")
    os.chmod(out, 0o644)
    print(f"update-feed: {out} ({a.channel}, {a.version_name}, versionCode {a.version_code}, "
          f"{feed['apk']['size']} bytes, sha256 {feed['apk']['sha256']})")


if __name__ == "__main__":
    main()
