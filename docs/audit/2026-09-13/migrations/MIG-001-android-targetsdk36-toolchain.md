# MIG-001 — Android targetSdk 36 + toolchain leap (Kotlin 2.4 / AGP 9 / Compose BOM 2026.08)

Status: **URGENT — Play deadline passed 2026-08-31; extension wall 2026-11-01.**
Sources (fetched 2026-09-13): Play policy https://support.google.com/googleplay/android-developer/answer/11926878; AGP https://developer.android.com/build/releases/gradle-plugin (9.4.0, Sept 2026); Kotlin https://endoflife.date/api/kotlin.json (2.4.20, 2026-09-07); BOM https://developer.android.com/develop/ui/compose/bom/bom-mapping (2026.08.00 → ui 1.12.0, M3 1.4.0); Room/Work/Dagger/Retrofit pages as cited in the D12 table.

## Case for
- Google Play requires target API 36 for app updates since **2026-08-31** (Wear OS: API 35). App targets 35 (`android/app/build.gradle.kts:29`), wear targets 34 (`android/wear/build.gradle.kts:14`). Without this, **no fix ships to users** — including the pending push-cert and sync fixes.
- The rest of the stack is ~20 months old; every quarter of delay compounds (AGP 10 makes the new Variant API mandatory next).
- WorkManager 2.11.2 carries fixes for "network calls failing from background even when network constraints are met" — directly relevant to this app's sync workers.

## Case against
- Big-bang toolchain bumps on a 13-module build (`android/*/build.gradle.kts`) can burn days on convention-plugin breakage (`android/build-logic/`), KSP/Hilt/Room lockstep, and M3 1.4 visual diffs.
- Mitigation: phase it; phase 1 alone satisfies Play.

## Plan (incremental, 4 phases)
1. **Deadline fix (2–4 d):** file the Play extension request today (buys until 2026-11-01); bump compileSdk/targetSdk 35→36 (app + convention plugin `AndroidLibraryConventionPlugin.kt:20`), wear targetSdk 34→35. Review Android 16 behavior changes against the app's sensitive surfaces: foreground services (guided workout, sync), exact alarms (reminders — known fragile per reminder-fixes work), notifications (rest timer, FCM). Ship.
2. **Build toolchain (2–4 d):** Gradle → 9.6+, AGP 8.7.3 → 8.13 → 9.4.0, Kotlin 2.0.21 → 2.4.20 + matching KSP; fix convention plugins.
3. **Library train (3–5 d):** Compose BOM 2026.08.00 (ui 1.12, M3 1.4 — expect visual/API diffs), Hilt 2.60.1, Room 2.8.5 (keep SupportSQLite wrapper so the SQLCipher path — see D3 finding — is untouched until its own migration), WorkManager 2.11.2, Retrofit 3.0.0 (binary-compatible), OkHttp 5.5.0, Moshi 1.15.2, lifecycle/navigation/camerax current.
4. **Cleanup (1–2 d):** deprecation warnings, Robolectric/test-runner bumps, full unit + Robolectric suite (note known LWW flake, not a regression signal).

Total: 8–15 solo days across phases; only phase 1 is deadline-bound.

## Blast radius
13 Gradle modules; convention plugins in `android/build-logic/`; 95 test files (JUnit4 — intentionally untouched); Compose usage across all feature modules; no backend/web impact.

## Reversibility / rollback trigger
Each phase is a separate PR; revert = git revert (versions.toml is the single choke point). Rollback trigger: phase-2/3 PR red for >2 days → revert and re-slice. Phase 1 cannot be rolled back conceptually (Play requires it) — only fixed forward.

## User impact
Phase 1: none visible if behavior-change review is done (risk areas: exact-alarm reminders, FGS during workouts). Phase 3: minor M3 1.4 visual changes.

## Privacy re-verification (regulated mode)
targetSdk 36 tightens photo/media and FGS permissions — re-verify the camera/photo capture flows (nutrition capture) and health-data notifications still behave; no new data flows introduced.

## Do-nothing
Carrying cost: **all Android releases blocked by Play**; users keep known bugs (push dead in prod per memory, portion bugs) forever; security patches undeliverable. Untenable: **already** — absolute wall 2026-11-01 even with the extension. Do-nothing is not viable for phase 1; phases 2–4 could be deferred to ~mid-2027 at ~1–2 extra days of friction per quarter.
