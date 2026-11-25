#!/usr/bin/env python3
"""The .aab (for app stores) has no updater: its manifest does not ask for REQUEST_INSTALL_PACKAGES
and has no update FileProvider, and its code has none of the updater's strings. The direct APK
given for comparison must have all of them, so the check can see them at all (R8 renames classes,
so the check looks for strings the updater cannot work without). CI: android.yml and release.yml.

Usage: aab-check.py <store .aab> <direct APK built the same way>
"""
import sys
import zipfile
from pathlib import Path

aab, apk = Path(sys.argv[1]), Path(sys.argv[2])
PERMISSION = "android.permission.REQUEST_INSTALL_PACKAGES"
# The update FileProvider's authority (androidApp/src/direct/AndroidManifest.xml).
PROVIDER = "to.pubky.maps.updates"
# The installer MIME type and the updater's log tag (androidApp/src/direct/.../ApkUpdater.kt).
CODE_MARKERS = ["application/vnd.android.package-archive", "PubkyMapsUpdates"]


def die(msg):
    print(f"::error::aab-check: {msg}")
    sys.exit(1)


def contains(blob: bytes, text: str) -> bool:
    # Binary XML (APK) keeps strings as UTF-16 or UTF-8; the bundle's protobuf manifest as UTF-8.
    return text.encode() in blob or text.encode("utf-16-le") in blob


with zipfile.ZipFile(aab) as z:
    aab_manifest = z.read("base/manifest/AndroidManifest.xml")
    aab_dex = b"".join(z.read(n) for n in z.namelist() if n.startswith("base/dex/") and n.endswith(".dex"))
with zipfile.ZipFile(apk) as z:
    apk_manifest = z.read("AndroidManifest.xml")
    apk_dex = b"".join(z.read(n) for n in z.namelist() if n.endswith(".dex"))

if not contains(aab_manifest, "android.permission.INTERNET"):
    die("cannot read the .aab's manifest (no INTERNET permission found)")
if not aab_dex:
    die("the .aab has no dex files")
for blob, what in ((apk_manifest, PERMISSION), (apk_manifest, PROVIDER)):
    if not contains(blob, what):
        die(f"{apk.name} has no {what} either: this check cannot see it")
for marker in CODE_MARKERS:
    if not contains(apk_dex, marker):
        die(f"{apk.name} has no '{marker}' in its code either: this check cannot see it")

found = [what for what in (PERMISSION, PROVIDER) if contains(aab_manifest, what)]
found += [marker for marker in CODE_MARKERS if contains(aab_dex, marker)]
if found:
    die(f"the .aab has the updater: {found}")
print(f"aab-check: {aab.name} has no updater ({apk.name} has {PERMISSION}, the update FileProvider and its code)")
