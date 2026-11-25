#!/usr/bin/env bash
# Generates the Kotlin bindings of the Rust core with uniffi-bindgen (library mode).
#
# Usage: scripts/gen-kotlin-bindings.sh [OUT_DIR]   (default: core/bindings/kotlin)
# Env:   PROFILE=dev|<profile> (default dev), CARGO_TARGET_DIR (default ./target)
#        Use a profile that keeps the symbol table: uniffi reads the metadata from it, and
#        the release profile strips it.
#
# The bindings are read from the compiled host library, so they always match the code.
# Package: to.pubky.maps.core (see core/uniffi.toml).
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/core/bindings/kotlin}"
profile="${PROFILE:-dev}"
target_dir="${CARGO_TARGET_DIR:-$root/target}"
case "$profile" in
  dev) profile_dir=debug ;;
  *) profile_dir="$profile" ;;
esac
case "$(uname -s)" in
  Darwin) lib_ext=dylib ;;
  *) lib_ext=so ;;
esac

cargo build --manifest-path "$root/Cargo.toml" --locked -p pubky-maps-core --profile "$profile"
lib="$target_dir/$profile_dir/libpubky_maps_core.$lib_ext"

mkdir -p "$out"
cargo run --manifest-path "$root/Cargo.toml" --locked -q -p uniffi-bindgen -- \
  generate --library "$lib" --language kotlin --no-format --out-dir "$out"

find "$out" -name '*.kt' -print
