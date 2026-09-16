#!/usr/bin/env bash
# Android cold-start probe. Installs an APK on a running device/emulator and
# measures cold start to first frame N times with `am start -W`, reporting the
# median TotalTime (ms). Records the before/after for any Android startup slice.
#
# Usage:
#   infra/perf/android-coldstart.sh <path-to.apk> [runs] [activity]
# Defaults: runs=5, activity=com.gte619n.healthfitness/.mobile.MainActivity
#
# Requires: adb on PATH, exactly one device/emulator online.
set -euo pipefail

APK="${1:?usage: android-coldstart.sh <apk> [runs] [activity]}"
RUNS="${2:-5}"
ACT="${3:-com.gte619n.healthfitness/.mobile.MainActivity}"
PKG="${ACT%%/*}"

adb install -r "$APK" >/dev/null
echo "# cold start: $APK  ($RUNS runs, activity $ACT)"

times=()
for i in $(seq 1 "$RUNS"); do
  adb shell am force-stop "$PKG"
  sleep 2
  t=$(adb shell am start -W -n "$ACT" | awk -F': ' '/TotalTime/{print $2}')
  echo "run $i: ${t}ms"
  times+=("$t")
done

median=$(printf '%s\n' "${times[@]}" | sort -n | awk '{a[NR]=$1} END{print (NR%2)?a[(NR+1)/2]:int((a[NR/2]+a[NR/2+1])/2)}')
echo "median: ${median}ms"
