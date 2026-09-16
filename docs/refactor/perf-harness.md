# Performance measurement harness

Scripts under `infra/perf/` that produce the before/after numbers each Phase 2
perf slice must record in `02-progress.md`. All reproduce the Phase 0 baseline
within noise.

## Backend endpoint latency — `infra/perf/backend-latency.mjs`
Reads N days of Cloud Run request logs via ADC, reports per-endpoint p50/p95/p99
warm (cold starts excluded) and cold-start latency.
```
TOKEN=$(gcloud auth application-default print-access-token) \
  node infra/perf/backend-latency.mjs --since=2026-09-09
```
Baseline (7 d): `GET /api/me/sync` p50 1756 ms / p95 4126 ms; recent-activity
p50 609 ms; imported-history p50 1942 ms; cold-start p50 12.9 s. Targets: read
p95 ≤ 150 ms (sync ≤ 300 ms), write p95 ≤ 250 ms.

## Android cold start — `infra/perf/android-coldstart.sh`
Installs an APK on a running device/emulator, measures cold start to first frame
5× with `am start -W`, prints the median TotalTime.
```
infra/perf/android-coldstart.sh android/app/build/outputs/apk/release/app-release.apk
```
Baseline (emulator `hf_test`, release/R8, unauth → sign-in frame): median
~428 ms. Target ≤ 800 ms. **Caveat:** emulator on an M-series host ≠ mid-range
device, and this measures the unauthenticated first frame; the authenticated
dashboard cold start (mirror reads + real content) is the number the target is
really about — see the Macrobenchmark note below.

## Web bundle size — `infra/perf/web-bundle.mjs`
After `pnpm build`, reports gzipped shared first-load JS and the largest client
chunks.
```
(cd web && pnpm build) && node infra/perf/web-bundle.mjs web/.next
```
Baseline: shared first-load 127 KB gzipped. Target: initial JS ≤ 200 KB gzipped.

## Web LCP (authenticated Lighthouse) — documented procedure
No committed script yet (needs a live session cookie against a running stack;
see DEC-03). Procedure: run `bash infra/scripts/dev.sh`, obtain a session cookie
from the dev sign-in (`/auth/dev`), then:
```
pnpm dlx lighthouse http://localhost:3000/me \
  --preset=desktop --throttling-method=simulate \
  --extra-headers="{\"Cookie\":\"<session>\"}" \
  --only-categories=performance --output=json
```
with the 4G throttling profile (`--throttling.rttMs=150 --throttling.throughputKbps=1638`).
Target: repeat-view LCP ≤ 1.5 s, first-visit ≤ 2.5 s (see 01-plan target
adjustments).

## 24 h-offline full sync — `infra/perf/seed-fixture-user.mjs` + timing
Seeds a fixture user (~1 y: 730 nutrition days, 3 workouts/wk, daily metrics)
into a Firestore target, then time `SyncEngine.pull()` on a fresh device install.
Seeder writes to the emulator by default (`FIRESTORE_EMULATOR_HOST`) so it never
touches prod. Device-side timing is captured from the sync logs
(`adb logcat -s SyncEngine`), summed across pages. Target ≤ 5 s on Wi-Fi.
See DEC-03 for why the device leg is documented rather than a one-command script.

## Android Macrobenchmark — deferred, see DEC-04
The authenticated cold-start + DB-query timing wants a Macrobenchmark module.
The `am start -W` probe above covers cold start well enough to track the slice-8
and slice-9 deltas; a full Macrobenchmark module is deferred to avoid adding a
new Gradle module (and its ~minute of build time) mid-refactor. Revisit if the
800 ms target proves marginal on a real device.
