#!/usr/bin/env bash
# IMPL-E2E-01 — one-time local E2E toolchain setup for this Mac (Apple Silicon).
# Idempotent: safe to re-run. Installs Maestro, an arm64 Android emulator image,
# and the Playwright browsers. Xcode + iOS simulators are assumed already present
# (full Xcode, not just Command Line Tools) for the iOS leg.
set -euo pipefail

echo "== Maestro CLI =="
if ! command -v maestro >/dev/null 2>&1 && [ ! -x "$HOME/.maestro/bin/maestro" ]; then
  curl -Ls "https://get.maestro.mobile.dev" | bash
fi
export PATH="$HOME/.maestro/bin:$PATH"
maestro --version

echo "== Android SDK: arm64 emulator image =="
# Apple Silicon needs arm64-v8a system images (x86 images won't accelerate).
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
if command -v sdkmanager >/dev/null 2>&1; then SM=sdkmanager; else SM="$SDK/cmdline-tools/latest/bin/sdkmanager"; fi
IMAGE="system-images;android-35;google_apis;arm64-v8a"
if [ -x "$SM" ] || command -v sdkmanager >/dev/null 2>&1; then
  yes | "$SM" --install "platform-tools" "emulator" "$IMAGE" >/dev/null || \
    echo "  ⚠ sdkmanager install failed — install '$IMAGE' via Android Studio > SDK Manager"
  # Create the AVD the boot script expects (no-op if it exists).
  if command -v avdmanager >/dev/null 2>&1 || [ -x "$SDK/cmdline-tools/latest/bin/avdmanager" ]; then
    AM="$(command -v avdmanager || echo "$SDK/cmdline-tools/latest/bin/avdmanager")"
    echo no | "$AM" create avd -n e2e_pixel_arm64 -k "$IMAGE" -d pixel_7 --force >/dev/null 2>&1 || true
  fi
else
  echo "  ⚠ Android cmdline-tools not found — install Android Studio + SDK, then re-run."
fi

echo "== Playwright browsers (web) =="
( cd "$(dirname "$0")/../../web" && pnpm install --frozen-lockfile && pnpm exec playwright install --with-deps chromium )

echo "✓ setup complete. Next: scripts/e2e/boot-android.sh (Android) / boot an iOS sim, then scripts/e2e/run-local.sh"
