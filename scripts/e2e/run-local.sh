#!/usr/bin/env bash
# IMPL-E2E-01 — run the synthetic-user E2E suite locally on this Mac (M4).
#
#   scripts/e2e/run-local.sh [--platform ios|android|web|all] [--subset] [--flow <path>]
#
#   --platform   which client(s) to test (default: all)
#   --subset     only the per-PR "smoke"-tagged spine (fast); default runs full
#   --flow       run a single Maestro flow file (overrides --platform to mobile)
#
# Cost: $0 — Maestro CLI + Playwright are free/OSS and run on hardware you own.
# Mobile flows are the SAME YAML on iOS + Android (shared accessibility ids); only
# MAESTRO_APP_ID differs per platform. Web uses the existing Playwright suite.
# NB: no `set -u` — macOS ships bash 3.2, where expanding an empty array
# (`"${arr[@]}"`) under nounset aborts the script. We use empty arrays for the
# optional tag/grep flags, so nounset would break the common case.
set -eo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PLATFORM="all"; SUBSET=0; SINGLE_FLOW=""
IOS_APP_ID="com.gte619n.healthfitness"
ANDROID_APP_ID="com.gte619n.healthfitness"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --platform) PLATFORM="$2"; shift 2;;
    --subset)   SUBSET=1; shift;;
    --flow)     SINGLE_FLOW="$2"; shift 2;;
    *) echo "unknown arg: $1"; exit 2;;
  esac
done

# Maestro (via ~/.maestro/bin) needs a JVM; use the sdkman JDK if present.
export PATH="$HOME/.maestro/bin:$PATH"
[ -s "$HOME/.sdkman/bin/sdkman-init.sh" ] && source "$HOME/.sdkman/bin/sdkman-init.sh" >/dev/null 2>&1 || true
export JAVA_HOME="${JAVA_HOME:-$HOME/.sdkman/candidates/java/current}"

MAESTRO_TAGS=()
[ "$SUBSET" -eq 1 ] && MAESTRO_TAGS=(--include-tags=smoke)

run_maestro() {  # $1 = app id
  export MAESTRO_APP_ID="$1"
  if [ -n "$SINGLE_FLOW" ]; then
    maestro test "$SINGLE_FLOW"
  else
    maestro test "${MAESTRO_TAGS[@]}" "$ROOT/maestro/"
  fi
}

run_ios() {
  echo "▶ iOS (simulator) — MAESTRO_APP_ID=$IOS_APP_ID"
  # The app must be built + installed on a booted simulator first. Until the
  # SwiftUI app builds (IMPL-IOS-01 Phase 0D → XCFramework), this leg is skipped.
  if ! xcrun simctl list devices booted 2>/dev/null | grep -qi "iphone"; then
    echo "  ⚠ no booted iPhone simulator — boot one and install the app, then re-run. Skipping." ; return 0
  fi
  run_maestro "$IOS_APP_ID"
}

run_android() {
  echo "▶ Android (emulator) — MAESTRO_APP_ID=$ANDROID_APP_ID"
  if ! adb devices 2>/dev/null | grep -qw "device"; then
    echo "  ⚠ no running emulator/device — run scripts/e2e/boot-android.sh first. Skipping."; return 0
  fi
  run_maestro "$ANDROID_APP_ID"
}

run_web() {
  echo "▶ Web (Playwright)"
  local args=(); [ "$SUBSET" -eq 1 ] && args=(--grep @smoke)
  ( cd "$ROOT/web" && pnpm test:e2e "${args[@]}" )
}

case "$PLATFORM" in
  ios) run_ios;;
  android) run_android;;
  web) run_web;;
  all) run_android; run_ios; run_web;;   # mobile legs run in sequence; web last
  *) echo "unknown platform: $PLATFORM"; exit 2;;
esac
echo "✓ e2e run complete"
