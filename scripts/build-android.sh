#!/usr/bin/env bash
# Builds the Rust core for Android with cargo-ndk: libpubky_maps_core.so for arm64-v8a and
# x86_64, laid out as jniLibs/<abi>/ for the Gradle module.
# The advisory check covers these ABIs too: a new one goes into deny.toml's graph targets.
#
# Usage: scripts/build-android.sh [OUT_DIR]   (default: core/jniLibs)
# Needs: ANDROID_NDK_HOME (NDK r27 or newer), cargo-ndk 4, and the rustup targets
#        aarch64-linux-android and x86_64-linux-android.
# Env:   ANDROID_API_LEVEL (default 26, the app's minSdk)
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/core/jniLibs}"
api="${ANDROID_API_LEVEL:-26}"

# 16 KB page alignment, required for devices with 16 KB memory pages (Android 15+) and by
# Google Play for new apps.
page_size_flag="-C link-arg=-Wl,-z,max-page-size=16384"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_AARCH64_LINUX_ANDROID_RUSTFLAGS:-} $page_size_flag"
export CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS="${CARGO_TARGET_X86_64_LINUX_ANDROID_RUSTFLAGS:-} $page_size_flag"

# cargo-ndk copies every cdylib of the build (dependencies that are cdylibs too) into its
# output dir, so build into a staging dir and take only the core library from it.
staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

cd "$root"
cargo ndk \
  --target arm64-v8a \
  --target x86_64 \
  --platform "$api" \
  --output-dir "$staging" \
  build --release --locked -p pubky-maps-core

for abi in arm64-v8a x86_64; do
  mkdir -p "$out/$abi"
  cp "$staging/$abi/libpubky_maps_core.so" "$out/$abi/"
done

find "$out" -name '*.so' -exec ls -l {} \;
