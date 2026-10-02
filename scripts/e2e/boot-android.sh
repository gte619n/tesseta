#!/usr/bin/env bash
# IMPL-E2E-01 — boot the arm64 Android emulator for E2E and wait until ready.
# Pairs with setup-mac.sh (which creates the e2e_pixel_arm64 AVD).
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
EMU="$(command -v emulator || echo "$SDK/emulator/emulator")"
AVD="${1:-e2e_pixel_arm64}"

if adb devices | grep -qw "device"; then echo "✓ emulator already running"; exit 0; fi
echo "▶ booting $AVD (headless, no-snapshot)…"
"$EMU" -avd "$AVD" -no-window -no-snapshot -no-boot-anim -gpu swiftshader_indirect &
adb wait-for-device
# Block until the framework reports boot complete.
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "✓ emulator ready"
