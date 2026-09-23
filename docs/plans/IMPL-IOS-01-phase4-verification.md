# IMPL-IOS-01 — Phase 4 Verification & Hardening

> Status: **planned** · Created 2026-09-23 · Parent:
> [`IMPL-IOS-01-ios-client-parity.md`](IMPL-IOS-01-ios-client-parity.md)
> (Phase 4 section). This doc details the verification harness, budgets, drills,
> and human acceptance gates that close out the iOS parity build.

Phase 4 is the "prove it converges, prove it's fast enough, prove it survives
the nasty edges, then let the owner sign off on real hardware" gate. It has four
machine-verifiable pieces (A–C, the convergence harness + budgets + drills) and
one human piece (D, on-device acceptance). E is the security pass over the diff.

The screen-by-screen **parity audit** (multi-agent, loop-until-two-dry-rounds)
and the **parity matrix** (`docs/plans/ios-parity-matrix.md`) are owned by the
sibling parity-auditor workstream and are intentionally NOT re-specified here —
this doc covers everything else in the Phase 4 list.

---

## A — Cross-client convergence harness

### A.1 What it is

`shared/core/src/commonTest/.../sync/SyncConvergenceTest.kt` — a `commonTest`
that models **two clients** (A = Android-like, B = iOS-like) pulling from and
pushing to **one shared in-memory server**, and asserts they converge to
byte-identical mirror state. It is the fast, deterministic, per-PR guard that
sits *underneath* the heavyweight nightly emulator E2E (below).

It is deliberately fake-backed (in-memory `MirrorStore` / `OutboxStore` /
`SyncApi` from `OutboxAndEngine.kt`, plus a `FakeServer` map both clients share)
so that it runs on **both** the JVM (`shared-tests` job) and
`iosSimulatorArm64` (`shared-ios` job) with zero infrastructure — the same
orchestration code the two platforms will actually ship, exercised as one test
on both runtimes. The LWW policy under test is the real shared
`MergeConflictResolver`; routing is the real `CollectionRegistry`. The fakes
carry real behavior (a monotonic server clock mints LWW stamps, the server
dedupes idempotency keys, applies emit tombstones), so convergence is
*exercised*, not asserted trivially.

### A.2 Scenarios it covers

| # | Scenario | Asserts |
|---|---|---|
| 1 | Two clients pull the same server changes | Identical mirror snapshots; values land under the correct tables |
| 2 | LWW conflict — A and B both edit the same row offline, both push | Server LWW picks the later push; **both** clients converge to that winner after re-pull; neither mirror left dirty |
| 3 | Tombstone — server ARCHIVED change (doc=null) | Row removed on **both** clients; snapshots equal |
| 4 | schemaVersion bump | `wipeForSchemaBump()` fires once on each client → full resync → both converge on post-bump server state |
| 5 | Outbox idempotency — same op replayed twice (same idempotency key) | Applied exactly once (no second server stamp, one row) |
| 6 | 404-on-DELETE | Treated as success: outbox drains (nothing stuck), local row removed — no wedge |
| + | Slash-form routing convergence | Backend's `nutritionDays/entries` wire string and the canonical `nutritionEntries` table land in the same mirror table across clients (guards `nutrition-sync-slash-collection-bug`) |

The mapping back to the failure classes the plan names: #2 is the LWW /
`flaky-syncenginepulltest-lww` edge; #5/#6 are the `web-doses-outbox-drain-race`
lessons; #4 is the `schema-bump one-time full re-sync` path (#271).

### A.3 How it runs in ios-ci

Per the parent's `ios-ci.yml` design, this test is picked up by two jobs off the
same source, satisfying the "one test, both runtimes" contract:

- **`shared-tests`** (ubuntu): `./gradlew :shared:allTests` (JVM target) —
  runs on every PR touching `shared/**` or `ios/**`.
- **`shared-ios`** (macos-15): `./gradlew :shared:iosSimulatorArm64Test` — the
  KMP native target, same test source.

Both must be green for the `ios-ci` aggregate gate. `android-ci` also picks up
`shared/**` in its paths filter from Phase 1 onward, so a convergence regression
gates the Android app too. This is a **pure/common** test — no backend, no
emulator, no device — so it is cheap enough to be a per-PR gate, unlike A.4.

### A.4 Relationship to the nightly cross-client E2E

The parent's test plan also calls for a **live** cross-client E2E: boot the
backend + Firestore emulator (`dev-login` enabled), run scripted mutations from
the iOS-simulator suite and the Android-emulator suite against one user, and
assert mirror convergence including LWW, tombstones, resync bump, and outbox
drain after airplane mode. That harness is **scheduled/nightly + pre-release**
(it is slow and infra-heavy). `SyncConvergenceTest` is its fast in-process
sibling: if the shared orchestration is wrong, this test fails in seconds on
every PR rather than overnight. Divergence between the two (fake passes, live
fails) is itself a signal that a fake has drifted from real backend/Room
behavior — treat it as a bug in the harness, not a flake to retry.

---

## B — Performance budgets

iOS targets are anchored to the measured Android baselines from the parent plan
(release cold start ~430ms emulator; sync p50 1.76s warm). "iPad memory" has no
Android anchor (no Android tablet build ships); it is a fresh absolute budget.
Budgets are **regression gates for the release lane**, measured on the device
matrix below, not aspirational numbers.

| Metric | Android anchor | iOS target | How measured | Where |
|---|---|---|---|---|
| Cold start (launch → first frame) | ~430ms (release, emulator) | ≤ 600ms iPhone (newest), ≤ 750ms iPad | `XCTMetric` `XCTApplicationLaunchMetric` in a launch-perf test; TestFlight MetricKit `histogrammedApplicationLaunchTime` for field data | build-test (perf test) + owner MetricKit |
| Warm sync p50 (foreground delta pull, no-change page) | 1.76s warm | ≤ 1.9s p50 / ≤ 4.0s p95 | instrument the shared `SyncEngine.pull()` round-trip against the prod account on device; log p50/p95 to the sync-log debug screen | on-device (owner account) |
| First full sync (fresh install, prod account) | — (Android FirstSyncGate) | completes ≤ 20s p95 on Wi-Fi; gate screen shown throughout | XCUITest timing dev-login → dashboard; field-confirmed by owner | ui-smoke + owner |
| Steady-state memory (dashboard idle) | — | ≤ 180 MB iPhone | Instruments Allocations / `XCTMemoryMetric` | build-test |
| **iPad memory** (Split View / Slide Over multitasking, dashboard + detail) | — (no Android tablet) | ≤ 250 MB resident; no unbounded growth across 30 min multitasking soak | Instruments on iPad Pro 11"; owner overnight soak | build-test + owner |
| Live Activity update rate | — | ≤ ActivityKit budget; rest-timer overlay keyed to one self-ticking source (per `rest-timer-dual-state-gate`), no dual-clock drift | Live Activity state-mapping unit test + owner lock-screen check | build-test + owner |

Device matrix for perf runs: newest iPhone sim + iPad Pro 11" sim in CI
(per `ios-ci.yml` destinations), and the owner's physical iPhone + iPad for the
field/MetricKit numbers.

---

## C — Failure-mode drill checklist

Each drill maps a failure mode from the parent plan to: how to reproduce,
expected behavior, and where it is covered. 🤖 = automated (has a test); 👤 =
owner/human step on a TestFlight build.

| Failure mode | Reproduce | Expected behavior | Covered by |
|---|---|---|---|
| **Token family rotation race during outbox replay** (reuse-grace) | Fire two outbox pushes concurrently so a refresh lands mid-replay; or force a 401 on the first push of a drain | Silent refresh + retry; the benign lost-response retry does **not** burn the session family (`app.session.reuse-grace`, per `refresh-token-family-burn-logout-bug`). No forced sign-out; outbox eventually drains | 🤖 shared `commonTest` auth/refresh + reuse-grace suite (ported Phase 1C); 👤 on-device: background outbox drain after re-auth |
| **404-on-DELETE is success** | Enqueue a DELETE for a row already tombstoned server-side; drain | Push treated as success (`PushResult.ok`, `serverLastUpdate == null`); outbox drains, local row removed, no wedge | 🤖 `SyncConvergenceTest.deleteOfAlreadyGoneRowIsSuccess` (+ ported outbox-replay suite) |
| **Kill mid-sync** | Kill the app during a delta pull / during an outbox drain (Xcode stop, or `kill -9` on device via Instruments) | On relaunch: cursor was only advanced on a completed page, so a partial pull re-pulls cleanly (idempotent apply); un-pushed outbox ops survive (Room-persisted) and drain on next foreground. `WorkoutSessionDraft` restores an in-progress session (parity with Android) | 🤖 idempotent-apply + cursor-advance `commonTest`; 👤 on-device kill during first sync **and** during a live workout |
| **Airplane-mode CRUD → reconnect** | Enable airplane mode; create/edit/delete across meds, nutrition, workouts; disable airplane mode | Optimistic mirror writes are visible offline; on reconnect the outbox drains (idempotency-keyed, client-minted IDs) and the mirror converges; no duplicate rows, no lost edits | 🤖 `SyncConvergenceTest` scenarios 2/5/6 model this in-process; 🤖 nightly live E2E "outbox drain after airplane mode"; 👤 on-device airplane-mode pass |
| **schemaVersion bump mid-life** | Bump the server `schemaVersion` (staging), then pull from an existing install | `wipeForSchemaBump()` → one-time full resync (#271 path); no stale rows, both clients converge | 🤖 `SyncConvergenceTest.schemaVersionBumpTriggersFullResyncAndConverges` |
| **Silent-push throttling makes sync stale** (D7 risk) | Suspend the app; send N silent `sync` pushes faster than iOS's budget | Missed silent pushes are cosmetic: foreground-activation pull + BGAppRefreshTask floor catch up; user-visible pushes (leftover/adjust) carry their own sync trigger | 👤 on-device push-budget observation over a day (measured at the Phase 2 gate; re-confirmed here) |

---

## D — On-device / TestFlight acceptance checklist (owner / human)

> **These are human steps.** They cannot be automated — they require a real
> TestFlight build on the owner's physical iPhone and iPad, real APNs delivery,
> real camera, and overnight wall-clock. The owner runs them per release
> candidate and signs off before the build is promoted.

- [ ] 👤 **Push — silent**: with the app backgrounded, a server `sync` push
      triggers a delta pull; new data appears on next foreground without manual
      refresh. Confirm it survives the D7 throttle budget over a normal day.
- [ ] 👤 **Push — visible**: leftover-review / adjust-review / adjust-failed
      pushes arrive as visible notifications, deep-link to the right screen, and
      their actions (Apply, etc.) work.
- [ ] 👤 **Medication reminders**: pre-scheduled local notifications fire at the
      right times (48h window, carryover), Take/Snooze/Dismiss actions update
      the dose checklist, midnight re-plan works, deep link lands on the checklist.
- [ ] 👤 **Live Activity**: starting a workout shows the Live Activity on the
      **lock screen** and in the **Dynamic Island**; rest timer counts correctly
      (single source, no drift vs. the in-app overlay); rest-complete beep fires;
      it clears on session complete.
- [ ] 👤 **Camera capture**: meal photo capture works (incl. low light);
      barcode + label OCR (Vision) recognize a real product; the photo upload
      op-rail survives backgrounding and completes.
- [ ] 👤 **Background refresh overnight**: leave the app backgrounded overnight;
      next morning the mirror is reasonably fresh (BGAppRefreshTask floor);
      outbox that was pending at bedtime has drained.
- [ ] 👤 **iPad multitasking**: Split View + Slide Over across dashboard/detail;
      layout adapts at the size-class breakpoint; memory stays under budget over
      a 30-min multitasking soak (section B).
- [ ] 👤 **Offline relaunch**: airplane mode → force-quit → relaunch; the app
      opens to a cached-session dashboard rendering mirrored data (offline-first
      launch, no sign-out); edits made offline appear and drain on reconnect.
- [ ] 👤 **Sign-out / account-switch**: sign out wipes local mirror + Keychain
      tokens (SignOutSideEffects parity — the account-switch data-leak class);
      signing into a different account shows no residue.
- [ ] 👤 **First-sync gate**: fresh install → sign in → "Setting up" gate shown
      → full initial sync of the prod account → dashboard renders (within the
      section-B first-sync budget).

---

## E — Security review

Before promoting any Phase 4 release candidate, run `/security-review` over the
diff. Focus areas specific to this build:

- **Keychain**: token accessibility is `kSecAttrAccessibleAfterFirstUnlock`
  (needed so background sync reads tokens) and **not** looser; tokens are wiped
  on sign-out.
- **At-rest encryption** (D5): iOS relies on `NSFileProtectionComplete` for the
  Room-KMP DB — verify the protection class is actually applied to the store
  file, since this replaced app-layer SQLCipher.
- **OAuth audiences**: the iOS client ID is scoped in `OAUTH_ALLOWED_AUDIENCES`
  (config-only) and no wildcard crept in.
- **Deep links**: `healthfitness://` handlers (dose-checklist, adjust-review,
  withings-callback) validate their inputs and don't act on unauthenticated
  deep-link params.
- **No secrets in the diff**: App Store Connect API key / match passphrase live
  in GH Actions secrets + Secret Manager, never in-repo.

Run: `/security-review` on the branch diff; attach findings to the release-candidate checklist.

---

## Exit criteria (Phase 4 done)

1. `SyncConvergenceTest` green on both `shared-tests` (JVM) and `shared-ios`
   (`iosSimulatorArm64`); nightly live E2E green on the last pre-release run.
2. All section-B budgets met on the CI device matrix; no MetricKit regression on
   the owner's field data.
3. Every section-C drill either has a green automated test or an owner-checked
   on-device confirmation.
4. Section-D checklist fully signed off by the owner on a real iPhone **and**
   iPad for the release candidate.
5. `/security-review` run on the diff with no unresolved findings.
6. Parity audit (sibling workstream) reports two consecutive dry rounds.
