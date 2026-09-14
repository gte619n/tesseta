# IMPL-QUICKWINS — Decision Log

> Straightforward audit fixes pulled in at operator request (3 of 4 offered
> batches: Security quick wins, Correctness + dead-code, Release/ops safety — the
> web session-cookie shortening SEC-010 was declined). Branch
> `feature/quick-wins`, **stacked on** `feature/deadline-migrations` (PR #250) →
> `feature/audit-remediation` (PR #249) → main.

## Correctness + dead-code

- **XPLAT-008 (web unit drift)** — web had `KG_TO_LB = 2.20462` copied into 4
  files, drifted from the backend/Android precise `2.2046226218`. Unified: made
  `web/lib/units.ts` export the single precise `KG_TO_LB = 2.2046226218` and
  routed `body-composition/page.tsx`, `BiometricsSection.tsx`,
  `body-composition-dashboard.ts` through it (deleted their local copies).
  Verified `tsc --noEmit` clean; no `2.20462` left in web.
  - **DEC-501 — flag, do NOT fix in this PR:** the backend also has a drifted
    `2.20462` in `RecentActivityService.java:66` (vs its own `2.2046226218`).
    Left out to keep this a web-scoped change; noted for a backend follow-up.
- **PROD-009 (Android dead code)** — removed `DashboardFlags.showVitalsFixtures`
  + `showTodayCardFixtures` (referenced only at declaration), the `Readiness`
  fixture vitals tile (`vitals[3]`, never indexed/rendered), and
  `vitalsShortLabels` (0 refs). **DEC-502 — kept `showRecentFeedFixtures`**: it's
  a live branch in PhoneTodayScreen/FoldableDashboardScreen — not dead.
- **Audit tracking** — marked findings already resolved by the Phase 5 doc pass
  but stale-open in `findings.json`: DX-003 (dev port docs), XPLAT-007
  (feature-catalog), ARCH-008 (backend package list), DX-011 (AGENTS.md).

## Release/ops safety

- **CICD-003 (versionCode landmine, `android/cloudbuild.yaml`)** — removed the
  minutes-since-anchor fallback (~1.49M) that, once emitted, permanently buries
  the commit-count scheme (all later ~634 builds look like downgrades) and the
  silent shallow-clone under-count. Now: unshallow, **fail loudly** if the repo
  is still shallow (`rev-parse --is-shallow-repository`), and take
  `rev-list --count HEAD` with no `|| echo 0` mask (`set -euo pipefail` fails on
  error). Single numbering scheme; never ships an untrustworthy code.
- **CICD-005 (`|| true` masks, `backend/cloudbuild.yaml`)** — the four
  `gcloud run jobs update … || true` steps hid real update failures (job stuck on
  a stale image). Preserved the Chesterton's fence (a not-yet-bootstrapped job
  must not break the service deploy) by guarding each with
  `gcloud run jobs describe … && update` — a **missing** job is skipped with a
  log, but an **existing** job that fails to update now fails the build.

## Security quick wins (backend)

- **SEC-009 (constant-time secret compare)** — new `config/SecretCompare.java`
  (`MessageDigest.isEqual` on UTF-8, null-safe); `NutritionJobController:68`
  swapped off `Objects.equals`. Also unified the two webhook secret gates
  (GoogleHealth, Withings — already constant-time) onto the helper for
  consistency (behavior unchanged).
- **SEC-008 (dev-login fail-closed in prod)** — `AuthController` now requires the
  enable-flag AND a non-prod project id; dev-login returns 404 under the prod
  project regardless of the flag, plus a startup ERROR log if the flag is set in
  prod. New `AuthControllerProdGuardTest` (404 under prod). **DEC-503 — detect
  prod via `app.gcp.project-id == health-fitness-160`, blank treated as prod**
  (fail-closed on unknown); UAT sets a different project so its dev-login path is
  preserved. **DEC-504 — log-only, not refuse-boot** — a stray flag must not take
  prod down; the 404 already neutralizes the endpoint.
- **SEC-011 (SSRF hardening)** — new `integrations/config/OutboundFetchGuard.java`
  (https-only + reject loopback/link-local/site-local/unique-local/any-local/
  multicast + explicit 169.254.169.254). **DEC-505 — allowlist mode for the drug
  reference-image sink, scheme+IP denylist mode for the grounding resolver**: the
  grounding scraper legitimately fetches arbitrary public CDNs (og:image), so a
  host allowlist there would break the happy path the finding says to preserve.
  **DEC-506 — de-flaked the one positive test to a public IP literal (8.8.8.8)**
  so it never needs live DNS in CI (the guard resolves IP literals without a
  lookup). **Caveat:** the guard resolves DNS at check time, not connect time, so
  it is not fully TOCTOU/DNS-rebind safe — documented in the class Javadoc; full
  safety needs a custom socket factory (out of scope).

## Final verification (lead agent)

- **Backend:** `compileJava` clean; OutboundFetchGuardTest 7/7, AuthControllerTest
  7/7, AuthControllerProdGuardTest 1/1, NutritionJobControllerTest 4/4,
  GoogleHealthWebhookControllerTest 13/13, WithingsWebhookControllerTest 6/6 — 0
  failures.
- **Android:** `:app` + `:wear` `compileDebugKotlin` SUCCESS (manifest/XML valid);
  dashboard unit tests pass.
- **Web:** `tsc --noEmit` clean; no truncated `2.20462` left.
- **CI config:** both `cloudbuild.yaml` files parse (ruby YAML); 0 `|| true` job
  updates remain (4 describe-guards); android versionCode fallback removed.
- **Stacking:** committed on `feature/quick-wins` → PR #3 targets
  `feature/deadline-migrations` (#250) → `feature/audit-remediation` (#249) → main.
- Backend security is code-complete but, like the rest, resolves on merge+deploy.

## Android security

- **SEC-005 (auth tokens excluded from backup)** — mirrored the existing
  encrypted-DB backup exclusion (ADR-0007) to the plain-text token DataStores:
  phone `hf-auth` and wear `hf-wear-auth` (DataStore Prefs live at
  `files/datastore/<name>.preferences_pb`, domain `file`). Added the missing
  wear backup-rules + data-extraction-rules files and referenced them from the
  wear manifest; set explicit `android:allowBackup="true"` on both so the posture
  is intentional. Closes the device-transfer/cloud-backup token-leak gap.

## Cherry-picked: workout-coach bug fixes (not an audit finding)

- **DEC-507 — cherry-picked `5e35722f` from `workout-coach-improvements`** onto
  this branch at operator request (→ new commit `0750f605`), folding it into
  PR #251. Three android workout-coach fixes: persist last-set RIR, key the rep
  prompt/cue to the engine target, unstale the post-rest announcement
  (`android/feature-workouts/session/*`). The source branch was one commit ahead
  of current `main` (merge-base = `b7e58b0b`), touching 5 files disjoint from the
  audit work, so cherry-pick applied cleanly and kept the stack linear (vs a
  merge). Verified: `:feature-workouts:compileDebugKotlin` SUCCESS;
  SessionFormatTest 28/28, WorkoutSessionViewModelTest 21/21.
