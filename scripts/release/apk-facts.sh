#!/usr/bin/env bash
# Prints an APK's package, versionCode, versionName and minSdk as key=value lines (aapt2 badging).
# Usage: apk-facts.sh <apk>
set -euo pipefail
bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
"$bt/aapt2" dump badging "$1" | python3 -I -c '
import re, sys
text = sys.stdin.read()
pkg = re.search(r"^package: name=\x27([^\x27]+)\x27 versionCode=\x27(\d+)\x27 versionName=\x27([^\x27]*)\x27", text, re.M)
sdk = re.search(r"^(?:minSdkVersion|sdkVersion):\x27(\d+)\x27", text, re.M)
if not pkg or not sdk:
    sys.exit("apk-facts: cannot read the badging")
print(f"package={pkg.group(1)}\nversion_code={pkg.group(2)}\nversion_name={pkg.group(3)}\nmin_sdk={sdk.group(1)}")
'
