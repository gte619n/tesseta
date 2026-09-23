# iOS Parity Gap Report — IMPL-IOS-01 (Phase 4 audit)

> Adversarial audit of the Phase 3 authored iOS client against the Android
> source of truth. Audited **2026-09-23**. Companion to
> [`ios-parity-matrix.md`](ios-parity-matrix.md) (the row-by-row status).
>
> **Scope of audit:** all 8 feature verticals (Dashboard, Workouts, Nutrition,
> Medications, Blood, Body-Composition, Goals, Settings) + Auth/Sync/Infra +
> the 18 cross-cutting mechanisms. Paths are absolute where load-bearing.
>
> **Reading this report:** severities are **BLOCKER** (an Android
> screen/behavior with *no* working iOS equivalent — feature is absent or
> non-functional), **SHOULD** (partial parity — a view/VM exists but drops an
> intent, state field, or edge-case), **NIT** (cosmetic / low-risk).

---

## 0. The systemic caveat (read first)

**There is no `iosMain` source set and no built `SharedCore.xcframework`.**
`shared/core/src` contains only `commonMain` + `commonTest`. Consequently:

- Every iOS view carries `// import SharedCore` **commented out** and renders
  from a **local `@State` mirror** struct, not the shared `StateFlow`. The
  single bridge (`ios/HealthFitness/Presentation/ObservableBridge.swift`)
  documents this: "uncommented once the XCFramework is built (Phase 0D)."
- Every user-action intent across ~50 views is a `// Post-0D:` comment. So
  **today, nothing loads and nothing is interactive** on any screen.
- Every KMP `interface`-based transport (`SseClient`, HTTP client, DB driver,
  outbox, `NutritionOpQueue`) has **no iOS actual** — only `commonTest` fakes.

This is a *known, uniform staging state*, not a per-screen regression. It is
called out as **BLOCKER-0** below because it gates everything else. The gaps in
sections 2+ are the ones that **survive past 0D wiring** — dropped state
fields, missing intents, un-ported features, and stubbed platform services that
no amount of "flip the import on" will fix.

The good news the audit confirms: **the shared KMP ViewModels are, with a few
named exceptions, high-fidelity 1:1 ports** of the Android VMs (UiState fields
+ intents match), and several platform pieces are genuinely real (ActivityKit
Live Activity, Vision barcode+OCR, Swift Charts trends, Keychain token store,
markdown rendering). The parity losses concentrate in (a) the never-built
`iosMain` transports, (b) a handful of never-authored shared VMs, (c) view-layer
mirrors that structurally omit affordances, and (d) three named shared VMs that
explicitly dropped cross-domain behavior.

---

## 1. BLOCKERS

### B-0 — No `iosMain` / XCFramework: entire client is unwired
- **Android ref:** n/a (structural).
- **iOS:** `shared/core/src/` (no `iosMain`), `ios/HealthFitness/Presentation/ObservableBridge.swift`.
- **Absent:** the KMP iOS target, SKIE-generated framework, and the DI that
  hands real repos/transports to the shared VMs. Until this exists, no view
  observes a shared VM and no intent runs. **Everything downstream depends on
  this.**

### B-1 — Medication reminders entirely non-functional (D9)
- **Android ref:** `android/core-data/.../reminders/{ReminderEngine,ReminderReplanCoordinator,ReminderReceivers,ReminderNotifier,ReminderScheduler}.kt`
- **iOS:** `ios/HealthFitness/Notifications/LocalReminderScheduler.swift`, `NotificationDelegate.swift`, `ios/HealthFitness/App/HealthFitnessApp.swift`
- **Absent:** (a) `LocalReminderScheduler.plannedDoses()` is literally
  `return []` — nothing is ever scheduled; the real body (calling the ported
  `OutstandingDoses`/`ReminderPlanner` reducers) exists only as commented
  pseudocode. (b) `HealthFitnessApp` never calls `configure(delegate:)`, never
  sets the `UNUserNotificationCenter` delegate, never requests authorization —
  so the `MED_REMINDER` category + Take/Snooze/Dismiss actions are never
  registered and `NotificationDelegate` is dead code. (c) `handleTake` never
  logs a dose (`onTake` unassigned). (d) The `healthfitness://dose-checklist/{id}`
  deep link dead-ends (`AppState.handleDeepLink` is `_ = url`). (e) **None** of
  the four Android replan triggers (sync-completion, foreground, midnight, boot/
  timezone) are ported, plus no missed-dose reconciliation. The shared reducers
  are ported and tested — **only the wiring is missing, but the wiring is the
  whole feature.**

### B-2 — SSE transport unimplemented → Workout Designer + Goals Chat cannot stream
- **Android ref:** `android/feature-workouts/.../program/chat/WorkoutDesignerViewModel.kt`, `android/feature-goals/.../GoalsChatViewModel.kt` (OkHttp SSE)
- **iOS:** `Workouts/Designer/WorkoutDesignerView.swift` (static mock), `Goals/GoalsChatView.swift`
- **Shared:** `SseClient` is a `commonMain` interface (in `GoalsRepositories.kt`
  + `WorkoutDesignerGymRepositories.kt`) whose **only implementers are
  `commonTest` fakes**. No concrete Ktor/URLSession reader, no `iosMain`.
- **Absent:** the streamed proposal — the defining feature of both screens — has
  no runtime path on iOS. (Note: the shared VM *stream-folding logic* is real
  and tested; the transport under it is not.)

### B-3 — Workout Designer proposal editing + TRT safety panel dropped
- **Android ref:** `android/feature-workouts/.../program/chat/WorkoutDesignerViewModel.kt` (491 lines: `ProgramProposalEditState`, `editors` map, `TrtLabsPanel`, `editMode`/edit-active-program IMPL-18b)
- **iOS:** `Workouts/Designer/WorkoutDesignerView.swift` (`ProposalCard` is display-only)
- **Shared:** `shared/.../presentation/workouts/WorkoutDesignerViewModel.kt`
- **Absent:** the entire `ProgramProposalEdit/PhaseEdit/DayEdit/BlockEdit/PrescriptionEdit`
  edit-before-accept tree, plus `trt: TrtContext?`/`loadTrt()`. **`TrtLabsPanel`
  renders mandatory ADR-0015 danger/safety banners** — flag to owner; leans
  BLOCKER on safety grounds. `editMode` (edit an existing program) and the
  offline composer gate are also dropped.

### B-4 — Drink Mode / Drink Session entirely un-ported (IMPL-DRINK-01)
- **Android ref:** `android/feature-nutrition/.../DrinkSessionViewModel.kt` (+ `DrinkCard.kt`), mounted at `NutritionTodayScreen.kt:70`
- **iOS:** **MISSING** (`DrinkSessionView.swift` never authored)
- **Shared:** **MISSING** (`DrinkSessionViewModel` not in `shared/.../nutrition/`)
- **Absent:** the whole IMPL-DRINK-01 runtime — instant local tally, back-dated
  `sessionDay` across-midnight logging, `reconcileTally`, calorie-budget line,
  `startSession`/`endSession`, pour-size long-press, End-session summary, Undo —
  and the Today Drink-card entry point. (Note: `Settings/DrinkSettingsView.swift`
  = the *preferences* toggle only, not the session.)

### B-5 — Google Sign-In is a stub → nobody can sign in on iOS
- **Android ref:** `android/app/.../mobile/auth/{SignInScreen,AuthCoordinator}.kt` (Credential Manager → ID token → `POST /api/auth/exchange` → tokens; silent `/refresh`)
- **iOS:** `ios/HealthFitness/Auth/GoogleSignInService.swift`
- **Absent:** `signIn()` runs `assertionFailure("Phase 2B stub")` and returns;
  the GoogleSignIn SPM import is commented; the `SignInView` button action is
  commented. Tapping "Continue with Google" does nothing. (`KeychainTokenStore`
  is real; `AuthState` machine is real but has no token exchange feeding it.)

### B-6 — Sign-out data wipe missing (PHI leak across accounts)
- **Android ref:** `SignOutSideEffects` (FCM deregister, encrypted Room wipe,
  RecentActivity/TodaysDoses/UnitPrefs caches, OkHttp+Coil caches) + `LastAccountStore`
  account-switch guard.
- **iOS:** `ios/HealthFitness/Auth/AuthState.swift`
- **Absent:** `signOut()` only calls `tokenStore.clear()`; everything else is a
  `TODO(2B)`. **No `LastAccountStore`/`wipeIfAccountSwitched` equivalent exists.**
  Signing out or switching accounts leaves the previous user's mirror DB,
  outbox, and cached images on device — the exact multi-user leak class the
  project already fixed on Android.

### B-7 — Sync engine + first-sync gate + push all stubbed
- **Android ref:** core-data delta sync engine, `FirstSyncGate.kt`, `SyncStatusViewModel.kt`, `HfMessagingService.kt`
- **iOS:** `ios/HealthFitness/Sync/SyncBridge.swift`, `FirstSyncGateView.swift`, `Settings/SyncDiagnosticsView.swift`
- **Absent:** `SyncBridge` is fully stubbed — `firstSyncComplete` is a bare
  `UserDefaults` bool; `start()`/`pullOnForeground()`/`handleSilentSyncPush()`/
  `registerPushToken()` are all `TODO(2C)`. The first-sync gate can never clear
  from a real engine. **No shared `SyncStatusViewModel`** (diagnostics view is a
  local mirror; retry/refresh commented out). No Firebase/APNs registration in
  Swift (only `project.yml`/`Info.plist` strings).

### B-8 — Google Health connection un-ported
- **Android ref:** `android/feature-settings/.../googlehealth/GoogleHealthViewModel.kt` (5-state consent flow, `connect/disconnect/refresh/onConsentResult`, `IntentSender` channel)
- **iOS:** placeholder text in `Settings/DeviceConnectionsView.swift` ("Managed on Android for now.")
- **Shared:** **MISSING** (`GoogleHealthViewModel`).
- **Absent:** the entire Google Health connect flow — no shared VM, no UI.

### B-9 — PlanCoherence cross-domain overlay un-ported
- **Android ref:** `android/feature-goals/.../plan/{PlanCoherenceViewModel,PlanCoherenceSection,PlanCoherence}.kt`
- **iOS / Shared:** **MISSING** everywhere.
- **Absent:** the plan-reconciliation overlay (reads goals + workout-program +
  nutrition + progression, computes divergence flags, offers
  `reconcile(APPLY_PROGRAM_NUTRITION | SET_TARGET_FROM_MAINTENANCE)`), rendered
  inline on the roadmap for ACTIVE goals. Zero coverage.

### B-10 — Manual equipment add + spec-override dialogs un-ported
- **Android ref:** `android/feature-workouts/.../{AddEquipmentViewModel,EquipmentOverrideViewModel}.kt`
- **iOS / Shared:** **MISSING** everywhere (no VM, no UI, no entry point).
- **Absent:** manual add-equipment (catalog search + submit-new) and per-gym
  equipment spec overrides. On iOS a gym's equipment can only be scanned or
  removed — never added by hand or corrected.

### B-11 — Goals roadmap "Update nutrition" cross-domain action dropped
- **Android ref:** `android/feature-goals/.../GoalRoadmapViewModel.kt` (state
  `nutritionGuidance`/`applyingNutrition`/`appliedNutrition`; intents
  `applyNutrition()`/`consumeAppliedNutrition()`), surfaced as a header
  "Update nutrition" button + confirm/result dialogs in `GoalRoadmapScreen.kt`.
- **iOS:** `Goals/GoalRoadmapView.swift` (no trace)
- **Shared:** `shared/.../goals/GoalRoadmapViewModel.kt` **explicitly omits** it
  ("lands when those shared domains are ported").
- **Absent:** the state fields *and* intents don't exist in the shared VM, so it
  cannot appear post-0D. (This is the Phase-3 self-reported drop — **confirmed**.)

---

## 2. SHOULD (partial parity — dropped intents / state / edge-cases)

### Nutrition Today
- **Settle-poll dropped (shared VM).** Android `NutritionTodayViewModel` runs
  `pollWhileImagesGenerate` (~4 min budget, backoff) + a `SyncSignals.pushes`
  re-fetch so a photo meal that finalizes server-side swaps its name/macros/image
  in while the screen is open. Shared `load()` does one fetch. Reproduces the
  "generated on web, never popped in on mobile" bug the poll was built to fix.
  Ref: `android/feature-nutrition/.../NutritionTodayViewModel.kt`.
- **`withPendingOps` leftover/adjust row decoration dropped (shared VM).** The
  shared port filters out `REMOVE_LEFTOVERS`/`ADJUST_MEAL` op decoration →
  no "Analyzing leftovers…"/"Adjusting…" optimistic row state. Also drops the
  just-captured `localImagePath` thumbnail.
- **Edit sheet is a placeholder.** `NutritionTodayView.EditEntryPlaceholder` is
  read-only → `updateEntry`, `saveCompositeMeal`, `removeIngredient`,
  `undoRemoveIngredient`, `moveEntry`, `deleteEntry`, `regenerateEntryImage`,
  `reanalyzeEntry`, pull-to-refresh all have no iOS affordance.

### Nutrition Capture / op-rail
- **`NutritionOpQueue` has no iOS impl.** `shared/.../data/NutritionRepositories.kt`
  defines the interface; nothing implements it. `NutritionCaptureView.onPhotoCaptured`
  just `dismiss()`es (`// vm.wrapped.analyzeMeal(jpeg)`) — the JPEG is never
  parked/enqueued/uploaded. The durable op-rail (survives process death on
  Android) is absent. (Root cause = B-0/no outbox.)
- **Label-draft process-death resume dropped (shared VM).** Android persists the
  resolved `LabelCaptureFood` in `SavedStateHandle`; shared has no equivalent.

### Nutrition notification routing
- **No nutrition deep-links/actions on iOS.** `NotificationDelegate` handles the
  meds category only. `adjust-review`, `leftover-review`, `retake`, and the
  leftover **Apply** action are unrouted; a cold push can't open the (existing)
  `MealAdjustReviewView`/`LeftoverReviewView`. Shared `openAdjustReviewFor`/
  `openLeftoverReviewFor` exist but are unreachable.

### Workout Session
- **Exercise swap (#4) + progression re-ground (#3) dropped (shared VM).**
  Missing intents `loadSubstituteOptions`, `applyAdjustment`, `predictSwapLoad`,
  `applyPredictedTarget`; missing state `substituteOptions`/`substituteLoading`/
  `substituteError`; missing `PrescriptionAdjustment`. No iOS swap picker.
- **Owner demo-frame flag (#9) dropped (shared VM).** No `flagFrame`, `isOwner`,
  `OWNER_EMAILS`.
- **Spoken coach cues (TTS) + whistle have NO iOS engine.** No `AVSpeechSynthesizer`
  anywhere; the `RestCompleteChime` beep exists but the Android `CoachAnnouncer`
  (speaks exercise/weight/reps + "Workout complete") and `WhistleCue` (go
  whistle) have no iOS counterpart — while the **settings toggle is live and
  controls nothing**. Refs: Android `session/CoachAudioViewModel.kt` + announcer.

### Workout browse/detail/history/progression views (VMs are 1:1; views drop affordances)
- **WorkoutsLanding view** drops the month compliance grid, parked-restore
  banner, past-session picker, activation-issues surface, `deleteSession`.
- **ProgramDetail view** drops **Activate**, `saveEdit`, `applyNutrition`,
  parked-recovery banner, delete-session.
- **WorkoutHistory view** drops `deleteSession` (no swipe/menu) + `loadMore`
  pagination.
- **WorkoutDetail view** doesn't wire `startToday()` (no nav into logger).
- **ProgressionConsole view** doesn't wire `updateMode` (can't change block);
  re-implements the shared format math in Swift `ProgressionFormat` (drift risk).
- **WorkoutsHub** drops the "Design a new program" sparkle entry; `ProgramsList`
  `+` disabled.

### Gyms
- **`hours` field dropped from iOS `GymFormView.FormModel`** → per-day open/close
  hours can't be set on New or Edit.
- **`deleteCoverPhoto()` has no iOS control** (Edit can replace, not remove).
- **GymDetail** has no manual add-equipment or spec-override entry point (ties to
  B-10); cover photo not rendered on detail.
- **GymScan** drops the preview summary line (`total · matched · new`) + addable
  count on the confirm button. (Premise correction: Android GymScan uses a
  **gallery video picker + server-side CV**, not on-device Vision — iOS
  `PhotosPicker(.videos)` is correct parity, not a stub.)

### Dashboard / Today
- **Recent-activity (RecentFeed) dashlet not rendered on iOS** though shared VM
  loads `recentActivity` (loaded-but-orphaned).
- **Doses dashlet has no shared data field.** iOS `DoseDashlet` reads a local
  `DashboardModel.doses`, but `DashboardUiState` has no doses field — post-0D it
  can't populate.
- **Blood dashlet not rendered on iOS Today** (shared `DashboardViewModel.blood`
  exists; `TodayDashlets.swift` has zero blood refs; `TodaySplitView` BloodPanel
  never built — loaded-but-orphaned).
- **BodyCompositionHero not rendered on large layout** (folded into one vitals
  tile).
- **Per-card retry lost.** Whole-screen `.error` state discards the per-`CardState`
  retry granularity (`retryBlood`/`retryNutrition`/…) that is the point of the
  design.
- **Today-workout card:** `startedAt` dropped from iOS `resume` model → no live
  elapsed timer; card is non-interactive (no tap-to-resume/review).

### Goals
- **`resetStepToAuto` has no iOS UI** (shared VM has the intent; `StepRowView`
  has no "Reset to auto" affordance) → manual-override reset unreachable.
- **Roadmap metric readout dropped** (metricKey/comparator/target + "Auto" pill).
- **GoalsChat proposal card = dead placeholder** ("Save goal" has empty action);
  editable proposal form + `commit`/`discard` not built (shared VM supports them).
- **`isOnline` composer gate dropped** from shared `GoalsChatViewModel` (D17
  online-only); thread-list/delete UI not built (data path exists).

### Blood / Body-Comp (VMs faithful; platform glue + a few intents stubbed)
- **4 document pickers/QuickLook previews stubbed:** `UploadLabReportView`,
  `UploadDexaView` (pickers), `ReportDetailView`, `DexaScanDetailView` (View-PDF).
  `presentDocumentPicker()`/`viewPdf()` flip local phase / are empty; no
  `UIViewControllerRepresentable`.
- **Mutating intents stubbed in views:** `AddReadingView.submit`,
  `DexaScanDetailView.patchField`/`delete`, `ReportDetailView.delete`,
  `UploadLabReportView.cancel`.
- **No pull-to-refresh** on `BloodOverviewView` / `BodyCompositionView` (shared
  `refresh()`/`isRefreshing` unobserved).
- **`MarkerReferenceBar` reference-range viz dropped** on MarkerDetail (plain
  "Target" text instead). **Charts themselves are real** (Swift Charts).

### Settings / Auth
- **Withings `startWebAuth()` body empty** (VM real; ASWebAuthenticationSession
  referenced) → connect does nothing.
- **`WorkoutStreakSettingsViewModel` + `BiometricsSettingsViewModel` dropped** —
  no shared VM, no iOS surface (weekly-streak target; biometric visibility
  toggles).
- **Units/CoachAudio/WorkoutPreferences** folded into `SettingsView` (acceptable)
  but their `onChange`/toggle handlers are commented out (don't persist).
- **MedicationDetail view** missing edit-dose/edit-start/resume/delete dialog
  states + adherence sparkline; **ReminderSettings view** missing per-med
  custom-time picker/reset.

---

## 3. NIT

- WorkoutLibrary shared VM filters `archived` items; Android shows all (likely an
  intended fix, but a SoT divergence).
- More-bucket on iOS is a subset (`[.blood, .bodyComposition, .goals, .settings]`);
  verify the **Sync-log** entry is reachable somewhere.
- DEXA upload: iOS Failed state has no "Try Again" re-pick; Complete `dismiss()`es
  instead of routing to the new scan detail.
- TodaysDoses card: no collapse-when-all-taken animation; no `ON_RESUME` refresh.
- Snooze re-adds `id + ".snoozed"`; a second snooze could collide (once wired).
- Several shared VMs substitute Android's injected `SnackbarController` with a
  `message`/`consumeMessage` or `messages: SharedFlow` pattern — correct KMP
  adaptation, noted so it's not mistaken for a drop.
- **Verify `shared/.../settings/DrinkSettingsViewModel.kt` compiles** — sub-audit
  flagged `Food`/`DrinkProposal` referenced possibly without imports.

---

## 4. What's still missing across the whole client (completeness-critic view)

### Screens / features with NO working iOS equivalent
1. **Drink Mode / Drink Session** (B-4) — no view, no shared VM.
2. **PlanCoherence** cross-domain overlay (B-9) — nothing anywhere.
3. **Google Health** connection (B-8) — placeholder only, no shared VM.
4. **Manual add-equipment + equipment spec-override** dialogs (B-10) — nothing.
5. **Workout-streak settings** + **biometrics-visibility settings** — dropped.
6. **Goals roadmap "Update nutrition"** cross-domain action (B-11) — dropped from
   shared VM.

### Platform services stubbed / never implemented
1. **`iosMain` source set + XCFramework** (B-0) — the foundation; nothing runs
   without it.
2. **SSE transport** (B-2) — interface + test fakes only; designer + goal chat
   dead.
3. **Medication local-notification planner (D9)** (B-1) — `plannedDoses()` stub,
   unregistered delegate, no replan triggers, Take doesn't log.
4. **Sync engine + outbox + push (FCM→APNs) + BGTask background refresh** (B-7) —
   `SyncBridge` all `TODO(2C)`; no Firebase/APNs code; no `NutritionOpQueue` impl.
5. **Google Sign-In exchange** (B-5) + **sign-out wipe / account-switch guard**
   (B-6) — stub + PHI leak.
6. **Withings OAuth** (`ASWebAuthenticationSession`) — empty `startWebAuth()`.
7. **Deep-link router** — `handleDeepLink` is a TODO; only the (unregistered)
   meds category exists; no nutrition/withings routing.
8. **TTS / coach voice cues + whistle** — no `AVSpeechSynthesizer`; live toggle
   controls nothing.
9. **Document pickers + QuickLook PDF preview** (labs, DEXA) — 4 stubs.

### Cross-cutting items not started
- Version-negotiation handshake (no HTTP layer wired).
- Dynamic Type / VoiceOver (Phase F — not begun).
- iPad adaptive layouts partially built (`TodaySplitView` real) but BloodPanel +
  BodyCompositionHero absent on large layout.

### Bottom line for the remaining work
The **shared-VM layer is largely done and faithful** — the remaining work is
overwhelmingly **(1) build the `iosMain`/XCFramework + DI so views actually
observe the VMs**, then **(2) implement the ~9 stubbed/absent platform services**
(SSE, D9 notifications, sync/outbox/push, sign-in/sign-out, Withings, deep links,
TTS, document pickers), then **(3) author the ~6 missing verticals/VMs** (Drink,
PlanCoherence, Google Health, equipment dialogs, streak/biometrics settings,
roadmap "Update nutrition"), and finally **(4) close the view-layer affordance
drops** (activate/delete/edit/refresh/retry/metric-readouts across Workouts,
Nutrition, Dashboard, Goals, Blood, Body-Comp). **Nothing is on-device verified;
no row should move to `Verified` until B-0 is resolved and a real device round
is run.**
