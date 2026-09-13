# Artifact Reaping Plan — 2026-09-13 (Phase 4)

Read-only proposal. Nothing has been moved, edited, or deleted. Executable later as **one commit** per the change-set table in §7.

Scope actually found: **11** files in `docs/specs/`, **24** files in `docs/plans/` (the audit brief said 23 — the on-disk count is 24), **21** ADRs in `docs/decisions/` (two share the number 0020 — see §4), `AGENTS.md`, `docs/reference/*.md` spot-checks, `docs/architecture.md`, plus a secondary branch sweep (§5).

## 1. Summary counts by bucket

| Bucket | Count | Notes |
|---|---|---|
| IMPLEMENTED | 30 | 11 specs, 18 plans, 1 ADR-numbered decision log |
| SUPERSEDED | 3 | parity roadmap, dependency-migrations, ADR-0014 (in-place note already present) |
| STILL VALID | 27 | 3 plans, 18 ADRs, architecture.md, 5 reference docs |
| INVALID | 1 | AGENTS.md |
| EXPIRED | 0 | — |
| UNCLEAR | 2 | IMPL-STAB omnibus, ADR-0009 (questions in §6) |
| **Total** | **63** | |

Proposed archive moves: **32 files** → `docs/archive/2026-09/` (11 specs + 20 plans + AGENTS.md). ADRs are never archived (superseding notes in place instead). UNCLEAR items stay put.

## 2. Classification table — docs/specs/ and docs/plans/

Evidence cites implementing code (path:line), not commit messages. Confidence: [Certain] / [Likely].

### docs/specs/ (11 files — all IMPLEMENTED)

| File | Bucket | Evidence | Conf |
|---|---|---|---|
| IMPL-14-exercise-library.md | IMPLEMENTED | `backend/src/main/java/com/gte619n/healthfitness/core/exercise/ExerciseService.java:21`, `api/exercise/ExerciseController.java:25`, `api/admin/AdminExerciseController.java` | [Certain] |
| IMPL-15-workout-programs.md | IMPLEMENTED | `backend/.../core/workoutprogram/WorkoutProgramService.java`, `api/workoutprogram/WorkoutProgramController.java` | [Certain] |
| IMPL-16-decisions-log.md | IMPLEMENTED | Decision log for IMPL-16; ratified 2026-06-10. D-1/D-2 live in `backend/.../core/nutrition/Macros.java:8` (`withDerivedCalories()`), `NutritionService.java:31` | [Certain] |
| IMPL-16-med-reminders-and-nutrition-ux.md | IMPLEMENTED | `backend/.../core/medication/ReminderSettingsService.java`, `TimeSlot.java:15`; nutrition parts in `Macros.java`/`NutritionService.java`. Part A (reminders) further superseded by IMPL-21 rolling reminder — note in archive header | [Certain] |
| IMPL-18-conversational-program-designer.md | IMPLEMENTED | `backend/.../api/workoutprogram/WorkoutProgramChatController.java`, `integrations/workoutprogram/GeminiWorkoutProgramChatClient.java`, `web/components/workouts/WorkoutProgramChat.tsx` | [Certain] |
| IMPL-18b-conversational-active-program-editing.md | IMPLEMENTED | `backend/.../core/workoutprogram/chat/WorkoutProgramChatThread.java:17` (`programId` binding per E1); ADR-0016 records the accepted design | [Certain] |
| IMPL-19-decisions.md | IMPLEMENTED | `backend/.../core/exercise/FrameSpec.java:13`, `DemoFrame.java:16` (locked data contract as specified) | [Certain] |
| IMPL-19-dynamic-demo-frames.md | IMPLEMENTED | Same records + `web/components/admin/ExerciseDemoFrames.tsx` (admin frame viewer/editor) | [Certain] |
| IMPL-20-exercise-admin-redesign.md | IMPLEMENTED | `web/components/admin/AdminExerciseCatalog.tsx:30` (comment literally cites "IMPL-20: 'needs-review' … preset"), `AdminExerciseController.java` (reviewed flag, grounding) | [Certain] |
| IMPL-AND-01-dashboard-live-data.md | IMPLEMENTED | `android/app/src/main/java/com/gte619n/healthfitness/mobile/dashboard/DashboardViewModel.kt:4`, `PhoneTodayScreen.kt`, `FoldableDashboardScreen.kt` | [Certain] |
| IMPL-AND-15-workout-programs.md | IMPLEMENTED | `android/feature-workouts/.../program/ProgramsListScreen.kt`, `ProgramDetailScreen.kt`, `android/core-data/.../workouts/program/WorkoutProgramRepository.kt` | [Certain] |

**IMPL-16 collision (flagged per brief):** the two `docs/specs/IMPL-16-*` files are the *same* effort (spec + its companion decision log), unlike the operator-memory trap where "IMPL-16" also names an unrelated branch effort. Both archive together. Separately, "IMPL-16" as a branch name refers to active-workout-logging (a different effort) — the archive headers should say "IMPL-16 (med-reminders/nutrition-UX spec)" explicitly to defuse the name collision permanently.

### docs/plans/ (24 files)

| File | Bucket | Evidence | Conf |
|---|---|---|---|
| IMPL-17-decision-log.md | IMPLEMENTED | Active workout logging (ADR-0012): `LoggedSet` + completion upsert shipped; session UI at `android/feature-workouts/.../WorkoutSessionScreen.kt` (the audit's highest-churn file) | [Certain] |
| IMPL-18-decision-log.md | IMPLEMENTED | Same code as IMPL-18 spec above; ratified 2026-06-14 | [Certain] |
| IMPL-18b-decision-log.md | IMPLEMENTED | `WorkoutProgramChatThread.java:17` (`programId`); commit-path reuse per D1/D2 | [Certain] |
| IMPL-20-decision-log.md | IMPLEMENTED | `AdminExerciseCatalog.tsx:30`; ADR-0017 Cloud Function (thumbnails) deployed | [Certain] |
| IMPL-21-decision-log.md | IMPLEMENTED | `backend/.../core/medication/TimeSlot.java:15` (nullable explicit time), `missed` on dose log; ratified 2026-08-27 | [Certain] |
| IMPL-21-single-rolling-medication-reminder.md | IMPLEMENTED | Android `ReminderEngine.kt` (referenced at :288 in the spec itself), adherence-mirror observer via `LocalWriteBus` | [Certain] |
| IMPL-AND-20-offline-first-sync.md | IMPLEMENTED | `android/core-data/.../SyncEngine.kt`; Room+SQLCipher store; backend `GET /api/me/sync` delta endpoint; ADR-0007 records the decision | [Certain] |
| IMPL-AND-20-outstanding-questions.md | IMPLEMENTED | All 11 review items marked resolved in-doc (2026-06-02); sync engine shipped as above | [Certain] |
| IMPL-DRINK-01-drink-mode-and-session-logging.md | IMPLEMENTED | `backend/.../core/nutrition/DrinkService.java`, `api/.../DrinkController.java`, `web/app/me/drinks/page.tsx`, `android/core-data/.../DrinkApi.kt:28-49`, `DrinkSessionViewModel.kt` | [Certain] |
| IMPL-DRINK-01-decision-log.md | IMPLEMENTED | Same; IL-13 review changes verified in-code (search excludes drinks, `servingMacros`, etc.) | [Certain] |
| IMPL-GYM-002-bulk-equipment-import.md | IMPLEMENTED | `backend/.../BulkImportService.java` + `BulkImportController.java` (preview/confirm two-phase at `/api/me/gyms/{locationId}/equipment/import/*`) | [Certain] |
| IMPL-LEFTOVER-01-remove-leftovers.md | IMPLEMENTED | `backend/.../LeftoverService.java`, `LeftoverMath.java`, `api/nutrition/EntryResponse.java:51-52` (`leftover`, `adjustment` fields); Android `Leftover`/`LeftoverStatus` in `core-domain/Nutrition.kt` | [Certain] |
| IMPL-LEFTOVER-01-decision-log.md | IMPLEMENTED | Same code; code-complete 2026-09-12, shipped via #244 | [Certain] |
| IMPL-PROG-01-autoregulated-progression-engine.md | IMPLEMENTED | `backend/.../ProgressionEngine.java`, `ProgressionMath.java`, `ProgressionController`, `BodyweightClassifier.java`. **Doc's own status block still says 🟡 "no code yet" — false; fix in archive header** | [Certain] |
| IMPL-PROG-01-decision-log.md | IMPLEMENTED | Same; M1–M3/L1 decisions verified in code (Mifflin cold-start profile fields wired) | [Certain] |
| IMPL-PROG-02-prediction-display-rir-bodyweight.md | IMPLEMENTED | `BodyweightClassifier.java` (single source of truth), `SeedWeightResolver`, Android inline RIR on `ActiveRepCard`, delta chips. **Status block also stale ("no code yet")** | [Certain] |
| IMPL-PROG-02-decision-log.md | IMPLEMENTED | Same; F1–F6 fixes verified | [Certain] |
| refactor-best-practices.md | IMPLEMENTED | Documents three completed refactor passes; e.g. the extracted hook exists at `web/lib/use-chat-stream.ts`, consumed by `GoalsChat.tsx` and `WorkoutProgramChat.tsx` | [Likely] |
| android-web-parity-roadmap.md | SUPERSEDED | Self-marked STALE in its own header; successor is `docs/reference/feature-catalog.md`, which self-describes as "the durable replacement for the per-feature IMPL-* specs" (feature-catalog.md:4); active workout logging shipped (ADR-0012) contradicting its Phase 7 | [Certain] |
| dependency-migrations.md | SUPERSEDED | Same three migrations re-planned with current detail in this audit: `docs/audit/2026-09-13/migrations/MIG-002-nextjs-16.md`, `MIG-003-spring-boot-4-java-25.md` (+ MIG-001/MIG-004); none executed yet, so the *work* is live but this doc is no longer the plan of record | [Likely] |
| android-appfunctions-gemini-voice-logging.md | STILL VALID | No implementation (no `androidx.appfunctions` anywhere in gradle/code); correctly self-marked blocked on Google's Early Access. Keep until unblocked or abandoned | [Certain] |
| android-build-speed-roadmap.md | STILL VALID | Options catalog (2026-09-06) for future infra work; cheap wins shipped, heavier options not | [Certain] |
| state-management-robustness.md | STILL VALID | Phase 0 shipped (`TodaysDosesViewModel` observes `LocalWriteBus`); later phases (cross-module SSOT consistency) remain open and match live audit findings (reactive-vs-snapshot divergence) | [Likely] |
| IMPL-STAB-android-stability-omnibus.md | UNCLEAR | Diagnosis/plan from 2026-06-15; many items since fixed by later efforts (ReminderEngine, SyncEngine), but its operational exercise-media pipeline tasks may still be pending. Question in §6 | — |

## 3. Classification table — ADRs, AGENTS.md, reference docs, architecture.md

ADRs are **never archived**; buckets below drive in-place notes only.

| File | Bucket | Evidence / note | Conf |
|---|---|---|---|
| ADR-0001…0008, 0010…0013, 0016…0019 (16 ADRs) | STILL VALID | Accepted decisions all verifiable in code (e.g. ADR-0007 → `SyncEngine.kt`; ADR-0012 → workout session logging; ADR-0017 → thumbnail Cloud Function; ADR-0019 → successor-chain rotation in refresh-token service). No action | [Certain] |
| ADR-0014-designer-medical-context-scope.md | SUPERSEDED | Superseded same-day by ADR-0015; **the in-place superseding note already exists** in both files. No action needed — model of correct practice | [Certain] |
| ADR-0015-trt-decision-support.md | STILL VALID | `backend/.../core/trt/` (e.g. `TrtAdvisorContextService` + tests) | [Certain] |
| ADR-0009-exercise-demo-video-veo.md | UNCLEAR | Accepted (2026-06-01) but only prompt scaffolding exists: `integrations/exercise/ExerciseVideoPrompt.java:8`; `Exercise.java:34` — `videoUrl // RESERVED for future Veo; null in v1`. No `generateVideos` call in main source. Meanwhile IMPL-19/20 shipped dynamic *still* frames as the demo medium. Question in §6 | — |
| ADR-0020-implementation-decision-log.md | IMPLEMENTED | Not an ADR at all — a D1–D23 implementation decision log for the OAuth platform, which shipped: `backend/.../api/platform/OAuthAuthorizationController.java`, `OAuthTokenController.java`, `api/v1/V1NutritionController.java` et al., `platform/PlatformTokenService.java`. Collision fix in §4 | [Certain] |
| ADR-0020-third-party-oauth-platform-api.md | STILL VALID | The real ADR-0020. Stale detail: Status still "Proposed" though fully shipped (code above; prod-config per PR #156); zero registered third-party consumers to date. Propose in-place status → Accepted (Phase 5, not reaping) | [Certain] |
| AGENTS.md (repo root) | INVALID | Entire content is a 3-line placeholder comment: "Drop in the Google Health API Parity Tool context file here." Never filled; no parity-tool code exists anywhere. Extra hazard: `AGENTS.md` is a conventional agent-instructions filename, so tooling may ingest this stub as instructions. Archive | [Certain] evidence, [Likely] bucket |
| docs/architecture.md | STILL VALID | Live top-level doc. Self-admitted stale note at lines 115–117: "Active workout logging shipped since — ADR-0012; the roadmap doc itself is stale on that point." No last-updated marker. Fix belongs to Phase 5; archiving the parity roadmap (§2) requires updating this doc's `docs/plans/` pointer in the same pass | [Certain] |
| docs/reference/feature-catalog.md | STILL VALID | Canonical parity SSOT (~90% accurate per audit XPLAT-007) but **missing the shipped Drinks feature entirely** (zero matches for "drink"; code at `web/app/me/drinks/page.tsx:1-93`, `android/core-data/.../DrinkApi.kt:28-49`) and the Nutrition row omits Adjust-with-AI + Remove Leftovers (`EntryResponse.java:51-52`). Cited per D6 report `docs/audit/2026-09-13/domains/06-cross-platform.md:116-121` (XPLAT-007) | [Certain] |
| docs/reference/api-surface.md | STILL VALID | No date marker; no drink/leftover endpoints listed though `DrinkController.java` and leftover endpoints exist — update in Phase 5 | [Certain] |
| docs/reference/data-model.md | STILL VALID | No date marker; drink-related storage (e.g. drink orders — `FirestoreDrinkOrderRepository.java`) undocumented | [Likely] |
| docs/reference/deployment.md | STILL VALID | Spot-check only; correctly records Firebase-App-Distribution-only distribution (deployment.md:117, load-bearing for audit M4/SOTA-001) | [Likely] |
| docs/reference/patterns.md | STILL VALID | Spot-check only; no obvious falsehoods found | [Likely] |

## 4. ADR-0020 numbering collision — note + proposed fix

Two files claim ADR-0020:

- `ADR-0020-third-party-oauth-platform-api.md` — a genuine ADR (Proposed, 2026-07-27), the decision of record for the OAuth platform.
- `ADR-0020-implementation-decision-log.md` — **not an ADR**: a running D1–D23 implementation log for that same effort, exactly the genre of every `IMPL-*-decision-log.md` in `docs/plans/`.

**Proposed fix (do not execute in this phase):** move + rename the decision log to `docs/archive/2026-09/plans/IMPL-OAUTH-01-decision-log.md` (it is IMPLEMENTED, so it archives with the rest), leaving `ADR-0020` unambiguous. This beats the alternative of renumbering it to ADR-0021 because (a) it is not a decision record, so promoting it deepens the mislabel; (b) renumbering breaks any inbound links; (c) the repo already has the `*-decision-log.md` convention in `docs/plans/`. If the operator prefers keeping it under `docs/decisions/`, the fallback is renumber to ADR-0021 with a header note "companion log to ADR-0020" — but relocation is recommended. Additionally, flip the real ADR-0020's Status to Accepted (it shipped) in Phase 5.

## 5. Secondary table — branches (best-effort)

Remote branches are only `origin/main` + three dependabot branches; the operator's remembered feature stack (interactive_workout, program-applies-nutrition, food-logging-sync, etc.) no longer exists as refs local or remote — already merged/reaped. Local branches checked via `git log main..<branch>` + `git cherry main <branch>`:

| Branch | Status | Evidence |
|---|---|---|
| origin/dependabot/* (setup-java-5.5.0, upload-artifact-7.0.1, setup-android-4.0.1) | UNMERGED-LIVE | Open dependency-bump PRs; leave to dependabot | [Certain] |
| drink-phone-mgmt-and-fixes (local) | MERGED-DELETABLE | 1 commit ahead but `git cherry` reports it patch-equivalent to main (squash-merged) | [Certain] |
| drink-reorder-and-calorie-fix (local) | MERGED-DELETABLE | Same: ahead-commit patch-equivalent to main | [Certain] |
| fix-app-unit-test-log (local) | MERGED-DELETABLE | Its three commits landed on main as squashes #246 (`15ecf188`) + #247 (`29d8289c`); `git cherry` shows 2 non-equivalent patch-ids only because of the squash | [Likely] |
| meal-adjustment-notification (local) | MERGED-DELETABLE | Strict subset of fix-app-unit-test-log; content shipped in #246 | [Likely] |
| tesseta-refactoring (local, current) | UNMERGED-LIVE | 1 unmerged commit `4d147ef3` (per-user local-state wipe on account switch) — do not touch | [Certain] |
| interactive_workout, program-applies-nutrition stack | (no refs remain) | Operator memory says superseded/unmerged respectively, but neither exists in this clone; nothing to reap. If they survive in another clone: interactive_workout = SUPERSEDED (main re-did guided workout), program-applies-nutrition = UNMERGED-LIVE per memory | [Likely] |

Branch deletion is out of scope for the archive commit; listed for operator convenience only.

## 6. UNCLEAR — questions for the operator

1. **IMPL-STAB-android-stability-omnibus.md** — Workstream B's code items appear fixed by later work (ReminderEngine, SyncEngine, adherence reactivity), but Workstream A is *operational* (run the exercise-media pipeline). Q: have the operational exercise-library media tasks been run to completion, and is any Workstream B item still open? If "all done", reclassify IMPLEMENTED and archive; until answered it stays put.
2. **ADR-0009 (Veo demo videos)** — Only the prompt builder exists (`ExerciseVideoPrompt.java:8`); `Exercise.videoUrl` is still "RESERVED for future Veo; null in v1" (`Exercise.java:34`), and IMPL-19/20 shipped dynamic still frames instead. Q: is the Veo video direction still intended (ADR stays Accepted/valid), or did dynamic frames replace it (ADR gets an in-place "superseded by IMPL-19 approach" note)? ADR stays in place either way.

Soft-confirm (not blocking): treat `docs/audit/2026-09-13/migrations/MIG-00*` as the successor of record for `dependency-migrations.md`? If the operator wants a durable (non-audit) home for migration plans, copy-forward before archiving.

## 7. Change set — single reviewable commit

Rules encoded: archive to `docs/archive/2026-09/` (never delete), preserving `specs/`/`plans/` subdirs; every moved file gets a prepended archive header; ADRs untouched except in-place notes (deferred to Phase 5 where noted); UNCLEAR items untouched.

**Archive header template** (prepended to each moved file):

```markdown
> **ARCHIVED 2026-09-13** — Classification: <BUCKET>.
> Evidence: <one-line code citation from §2/§3>.
> Successor / current source of truth: <successor or "docs/reference/feature-catalog.md">.
> Archived by the 2026-09-13 audit reaping pass (docs/audit/2026-09-13/reaping-plan.md). Do not treat as current.
```

| # | File | Action | Destination | Header: classification / evidence / successor |
|---|---|---|---|---|
| 1–11 | `docs/specs/*.md` (all 11 files in §2 table 1) | move + header | `docs/archive/2026-09/specs/<same-name>` | IMPLEMENTED / per-file evidence from §2 / successor: `docs/reference/feature-catalog.md` (+ for IMPL-16-med-reminders: "Part A superseded by IMPL-21"; for both IMPL-16 files: "this is the med-reminders IMPL-16, not the active-workout-logging branch of the same name") |
| 12–20 | `docs/plans/IMPL-17|18|18b|20|21|DRINK-01|LEFTOVER-01|PROG-01|PROG-02-decision-log.md` (9 files) | move + header | `docs/archive/2026-09/plans/<same-name>` | IMPLEMENTED / per-file evidence from §2 / successor: corresponding archived spec + feature-catalog.md |
| 21–28 | `docs/plans/IMPL-21-single-rolling…`, `IMPL-AND-20-offline-first-sync`, `IMPL-AND-20-outstanding-questions`, `IMPL-DRINK-01-drink-mode…`, `IMPL-GYM-002…`, `IMPL-LEFTOVER-01-remove-leftovers`, `IMPL-PROG-01-autoregulated…`, `IMPL-PROG-02-prediction…` (8 files) | move + header | `docs/archive/2026-09/plans/<same-name>` | IMPLEMENTED / per-file evidence from §2 / successor: feature-catalog.md (+ for both PROG specs: header must add "in-doc status '🟡 no code yet' is wrong — shipped, see ProgressionEngine.java") |
| 29 | `docs/plans/refactor-best-practices.md` | move + header | `docs/archive/2026-09/plans/refactor-best-practices.md` | IMPLEMENTED / `web/lib/use-chat-stream.ts` et al. / successor: none (completed audit) |
| 30 | `docs/plans/android-web-parity-roadmap.md` | move + header | `docs/archive/2026-09/plans/android-web-parity-roadmap.md` | SUPERSEDED / self-marked stale; ADR-0012 shipped / successor: `docs/reference/feature-catalog.md` |
| 31 | `docs/plans/dependency-migrations.md` | move + header | `docs/archive/2026-09/plans/dependency-migrations.md` | SUPERSEDED / migrations re-planned / successor: `docs/audit/2026-09-13/migrations/MIG-001..004` (pending soft-confirm in §6) |
| 32 | `AGENTS.md` | move + header | `docs/archive/2026-09/AGENTS.md` | INVALID / 3-line never-filled placeholder for a Google Health "Parity Tool" context file; nothing implements it / successor: none |
| 33 | `docs/decisions/ADR-0020-implementation-decision-log.md` | **no move this commit** — flagged | (proposed, §4: relocate to `docs/archive/2026-09/plans/IMPL-OAUTH-01-decision-log.md`) | IMPLEMENTED / `api/platform/*Controller.java`, `api/v1/*Controller.java` / successor: ADR-0020 (the real one). Held out of the mechanical commit because it changes ADR numbering semantics — wants explicit operator sign-off |
| 34 | `docs/architecture.md` lines 115–117 | **no edit this commit** — Phase 5 | — | pointer to `docs/plans/` must be rewritten when items 30–31 move (the two docs it names are exactly the ones being archived) |
| 35 | `docs/decisions/ADR-0020-third-party-oauth-platform-api.md` Status field, `docs/reference/feature-catalog.md` Drinks/Adjust/Leftovers rows, `api-surface.md`/`data-model.md` drink endpoints | **no edit this commit** — Phase 5 | — | content edits, not reaping; listed so the reaping commit and the reconciliation commit stay disjoint |
| — | `docs/plans/android-appfunctions-gemini-voice-logging.md`, `android-build-speed-roadmap.md`, `state-management-robustness.md` | keep in place | — | STILL VALID |
| — | `docs/plans/IMPL-STAB-android-stability-omnibus.md` | keep in place (UNCLEAR) | — | pending §6 Q1 |
| — | all 21 ADRs | keep in place | — | ADRs never archived; ADR-0009 pending §6 Q2 |

Post-move, `docs/specs/` becomes empty and `docs/plans/` retains 4 files (3 STILL VALID + 1 UNCLEAR). Suggested commit message: `docs: archive implemented/superseded specs and plans to docs/archive/2026-09 (audit reaping, see docs/audit/2026-09-13/reaping-plan.md)`.
