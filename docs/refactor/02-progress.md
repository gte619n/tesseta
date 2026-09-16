# Phase 2 — Progress (before/after metrics per slice)

Baseline numbers are from `docs/refactor/00-baseline.md`. Each slice records the
metric it targeted, measured the same way.

---

## Slice 0 — perf-harness + revive dead integration suite
**Target:** enable measurement; make the backend emulator suite actually run.
- **Backend integration suite:** before = **0 tests executed** (NO-SOURCE, silently
  skipped locally + CI). After = **12 tests across 7 emulator-backed classes
  execute and pass** (RefreshTokenRotationConcurrency, AdherenceSameDayConcurrency,
  NutritionRollupConcurrency, StepRepositoryScoping, FirestoreScheduledWorkoutRange,
  ReminderSettingsRepositoryImpl, FirestoreEmulatorSmoke). Verified:
  `./gradlew integrationTest -Dfirestore.emulator.required=true` → 12/0/0.
- **Zero-test guard:** added — CI (`firestore.emulator.required=true`) now fails
  the build if the suite ever resolves to 0 tests again.
- **Perf probes:** `infra/perf/{backend-latency.mjs, android-coldstart.sh,
  web-bundle.mjs, seed-fixture-user.mjs}` committed; backend-latency and
  web-bundle verified to reproduce the Phase 0 baseline (sync p50 ~1.1–1.8 s,
  shared JS 127 KB gz). Full harness doc: `perf-harness.md`.
- **Builds:** backend `build jacocoTestReport` green; jacoco now aggregates unit
  + integration execution data. Android/web untouched (still green from Phase 0).

