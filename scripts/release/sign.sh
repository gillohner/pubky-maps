#!/usr/bin/env bash
# Signs a release APK (zipalign + apksigner, v2/v3 signatures) and, when given, an .aab (jarsigner),
# and prints the signing certificate's SHA-256. Gradle never sees the key.
# Key from the environment: ANDROID_KEYSTORE_B64 (PKCS12, base64), ANDROID_KEYSTORE_PASSWORD
# (store and key password), ANDROID_KEY_ALIAS. Nothing secret is printed.
# Usage: sign.sh <unsigned apk> <signed apk out> [<unsigned aab> <signed aab out>]
set -euo pipefail
apk_in=$1 apk_out=$2 aab_in=${3:-} aab_out=${4:-}
for v in ANDROID_KEYSTORE_B64 ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS; do
  [ -n "${!v:-}" ] || { echo "::error::sign: $v is not set" >&2; exit 1; }
done
bt=$(ls -d "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
(umask 077 && printf '%s' "$ANDROID_KEYSTORE_B64" | base64 -d > "$work/release.p12")

"$bt/zipalign" -P 16 -f 4 "$apk_in" "$work/aligned.apk"
"$bt/apksigner" sign --ks "$work/release.p12" --ks-type PKCS12 \
  --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEYSTORE_PASSWORD \
  --ks-key-alias "$ANDROID_KEY_ALIAS" --v4-signing-enabled false --out "$apk_out" "$work/aligned.apk"
"$bt/apksigner" verify --print-certs "$apk_out" | grep -m1 'SHA-256 digest'

if [ -n "$aab_in" ]; then
  cp "$aab_in" "$aab_out"
  jarsigner -keystore "$work/release.p12" -storetype PKCS12 \
    -storepass:env ANDROID_KEYSTORE_PASSWORD -keypass:env ANDROID_KEYSTORE_PASSWORD \
    -sigalg SHA256withRSA -digestalg SHA-256 "$aab_out" "$ANDROID_KEY_ALIAS" > /dev/null
  jarsigner -verify "$aab_out" | tail -1
fi
