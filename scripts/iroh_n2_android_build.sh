#!/usr/bin/env bash
# W6-N2 iroh spike — Android leg: NDK build of iroh-ffi (arm64-v8a + armeabi-v7a).
# Everything E:-resident: RUSTUP_HOME/CARGO_HOME (E:\pocket_realm_toolchain\rust),
# cargo target dir, temp dirs, NDK. Nothing may write to C:.
# Source of truth: docs/NOSTR-IROH-BUILD-PLAN.md stage 2 + docs/COMPLETION-PLAN.md N2.
set -euo pipefail

LOG_STAMP() { date -u +%Y-%m-%dT%H:%M:%SZ; }

echo "=== $(LOG_STAMP) iroh-ffi NDK build start ==="

# --- E:-pinned environment (no C: writes anywhere) -------------------------
export RUSTUP_HOME='E:\pocket_realm_toolchain\rust\rustup'
export CARGO_HOME='E:\pocket_realm_toolchain\rust\cargo'
export TMPDIR='E:\pocket_realm_toolchain\tmp'
export TEMP='E:\pocket_realm_toolchain\tmp'
export TMP='E:\pocket_realm_toolchain\tmp'
export ANDROID_SDK_ROOT='E:\pocket_realm_toolchain\android-sdk'
export ANDROID_NDK_ROOT='E:\pocket_realm_toolchain\android-sdk\ndk\30.0.15729638'
export ANDROID_NDK_HOME="$ANDROID_NDK_ROOT"
export NDK_HOME="$ANDROID_NDK_ROOT"
# Rust toolchain binaries invoked directly from the E:-resident rustup home
# (avoids the user-profile cargo proxy launchers entirely).
TC=/e/pocket_realm_toolchain/rust/rustup/toolchains/stable-x86_64-pc-windows-msvc/bin
# cargo-ndk must be invoked as `cargo ndk` (a cargo subcommand found on PATH),
# never as a bare cargo-ndk.exe.
export PATH="$TC:/e/pocket_realm_toolchain/rust/cargo/bin:$PATH"

CARGO="$TC/cargo.exe"
RUSTC="$TC/rustc.exe"

# CARGO_TARGET_DIR under the toolchain tmp (outside the repo; spike scratch).
# Kept OUTSIDE the clone dir so its mere existence never blocks `git clone`.
SRC=/e/pocket_realm_toolchain/tmp/iroh-ffi-n2
TARGET=/e/pocket_realm_toolchain/tmp/iroh-ffi-n2-target
JNILIBS_OUT=$TARGET/jniLibs
BINDGEN_OUT=$TARGET/bindgen-kotlin
export CARGO_TARGET_DIR="$TARGET"

"$RUSTC" --version
"$CARGO" --version

echo "=== $(LOG_STAMP) step 1: rustup target add (writes go to RUSTUP_HOME on E:) ==="
"$CARGO_HOME/bin/rustup" target add aarch64-linux-android armv7-linux-androideabi
"$CARGO_HOME/bin/rustup" target list --installed

echo "=== $(LOG_STAMP) step 2: cargo install cargo-ndk 3.5.4 (CARGO_HOME on E:) ==="
if [ ! -x "$CARGO_HOME/bin/cargo-ndk.exe" ]; then
  "$CARGO" install cargo-ndk --version 3.5.4 --locked
else
  echo "cargo-ndk already installed at $CARGO_HOME/bin"
fi
"$CARGO" ndk --version

echo "=== $(LOG_STAMP) step 3: clone iroh-ffi v1.1.0 ==="
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch v1.1.0 https://github.com/n0-computer/iroh-ffi "$SRC"
fi
git -C "$SRC" rev-parse HEAD
git -C "$SRC" describe --tags --exact-match
cd "$SRC"

echo "=== $(LOG_STAMP) step 4: host lib build (uniffi-bindgen metadata + windows native) ==="
"$CARGO" build --lib -p iroh-ffi
ls -la "$CARGO_TARGET_DIR/debug/" | grep -iE 'iroh|\.dll' || true

echo "=== $(LOG_STAMP) step 5: cargo-ndk release build arm64-v8a + armeabi-v7a ==="
"$CARGO" ndk \
  --target arm64-v8a --target armeabi-v7a --platform 29 \
  -o "$JNILIBS_OUT" \
  build --release --lib -p iroh-ffi

echo "=== $(LOG_STAMP) step 6: jniLibs inventory + sha256 ==="
find "$JNILIBS_OUT" -type f -name '*.so' -exec ls -la {} \;
find "$JNILIBS_OUT" -type f -name '*.so' -exec sha256sum {} \;

echo "=== $(LOG_STAMP) step 7: uniffi-bindgen Kotlin generation ==="
"$CARGO" run --bin uniffi-bindgen -- \
  generate --language kotlin \
  --out-dir "$BINDGEN_OUT" \
  --config "$SRC/uniffi.toml" \
  --library "$CARGO_TARGET_DIR/debug/iroh_ffi.dll"
find "$BINDGEN_OUT" -type f | head -50

echo "=== $(LOG_STAMP) iroh-ffi NDK build COMPLETE ==="
