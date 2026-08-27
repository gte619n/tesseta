# IMPL-21 — Single Rolling Medication Reminder

**Status:** 🟢 Implemented — all phases code-complete, unit/functional suites green (see §5 dashboard + §8 verification results). Pending: human review of the decision log, manual owner walkthrough (§6.6), and merge/deploy.
**Branch:** `medicine-reminders`
**Author / PM:** Evan Ruff (interview) + engineering
**Created:** 2026-08-27
**Supersedes:** the multi-notification behavior introduced in [IMPL-16 Part A](../specs/IMPL-16-med-reminders-and-nutrition-ux.md) (per-window notifications, `notificationId(plannedAtMillis)` → one shade entry per fire time). This spec **replaces that path outright** (no dual-run flag).

---

## 1. Problem statement

Today the app pops a **separate notification per dose window** (morning at 06:00, afternoon at 12:00, …), each with its own stable-per-firing notification id (`ReminderEngine.notificationId(plannedAtMillis)` — [ReminderEngine.kt:288](../../android/core-data/src/main/java/com/gte619n/healthfitness/data/reminders/ReminderEngine.kt)). The reminder only reflects doses marked from the notification's own action buttons; marking a dose off **inside the app** does not update the notification, and multiple windows produce multiple competing shade entries.

We want a **single, always-at-most-one** medication reminder that:

1. Shows **only what the user still needs to take right now** — overdue + currently-due doses, most-overdue first.
2. **Updates live** as doses are marked off *anywhere* (in-app Today's Doses screen, notification action, or another device sync), even while the app is backgrounded.
3. **Accumulates across windows**: 5 morning doses → take 4 → shows 1; at 1:00 pm the 2 afternoon doses come due and the still-untaken morning dose remains → shows 3.
4. Lets the user **schedule the dose time inside drug setup** (window preset + optional explicit time), in addition to the existing global reminder-settings screen.

### The canonical scenario (the "acceptance story")

> Morning window has 5 doses due at 07:00. Afternoon window has 2 doses due at 13:00.
> - 07:00 — notification appears, re-alerts, lists 5 doses.
> - User marks 4 taken (in-app). Notification silently updates → **1 dose** shown.
> - 13:00 arrives, morning dose #5 still not taken. Afternoon batch crosses into due → notification **re-alerts** and lists **3 doses** (1 overdue morning + 2 afternoon), most-overdue first.
> - User takes all 3 → notification clears.
> - Any dose left untaken at local midnight → recorded as **missed** (synced), notification clears, day resets.

---

## 2. Design decisions (locked via interview 2026-08-27)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Schedule source of truth | **Window + optional explicit per-slot time.** Windows stay the backbone; a slot may carry an explicit `LocalTime` override. |
| D2 | Time precedence (most→least specific) | **drug-setup explicit slot time → per-med window override (settings screen) → global window default.** |
| D3 | What the notification lists | **Overdue + currently-due only.** Later-today doses stay hidden until their time. |
| D4 | Re-alert policy | **Re-alert (sound/vibrate/heads-up) when a new batch crosses into due; silent in-place update on decrement** (marking off). |
| D5 | Overdue lifetime | **Until local midnight**, then untaken doses are recorded **missed** and the notification clears. |
| D6 | Dismiss behavior | **Swipeable**; reappears with a re-alert when the next batch crosses into due. Not a foreground service. |
| D7 | Live-update mechanism | **Lightweight long-lived background observer** on the adherence/medication data flow re-posts the single notification on any change. |
| D8 | Notification actions | **Per-med "✓ <name>" buttons when ≤3 due; single "✓ Take all" when >3** (keep existing action model). Body tap deep-links to Today's Doses. |
| D9 | Body layout | **Flat list, most-overdue first**, each line `med — dose unit — <scheduled time>`. |
| D10 | Repeat/nag | **No periodic re-nag.** Only re-alert on new-due (D4). |
| D11 | Missed record | **Recorded silently (no retro-edit UI), synced to backend** so web adherence history/stats are accurate. |
| D12 | Rollout | **Replace outright.** Delete the per-window multi-notification path; no dual-run flag. |
| D13 | PRN + snooze | **PRN stays excluded; no snooze action.** |
| D14 | Web parity | Explicit-time field editable on **both web and Android** drug setup. |
| D15 | Missed authority | **Android local**, at device-local midnight; reconciles on next launch if the device was off. |
| D16 | Drug-setup time UX | **Window preset, tap to refine** — choosing a window shows its default time; an optional "Set specific time" control overrides that one slot. |

---

## 3. Current state (grounded in code)

Read before implementing — these are the pieces this feature rewires:

| Concern | File | Note |
|---------|------|------|
| Per-firing notification id (the thing to collapse) | [ReminderEngine.kt:288](../../android/core-data/src/main/java/com/gte619n/healthfitness/data/reminders/ReminderEngine.kt) `notificationId(plannedAtMillis)` | **Change to a single fixed id.** |
| Notification build / actions / body | ReminderEngine.kt:156–199 (`postNotification`) | Rewrite for flat overdue-first list + fixed id. |
| Alarm arm/chain, `onAlarmFired`, `onDosesTaken` | ReminderEngine.kt (top half, ~59–105) | Re-arm at each distinct due time; all firings target the single notification. |
| Planner (pure due/overdue logic) | [ReminderPlanner.kt](../../android/core-domain/src/main/java/com/gte619n/healthfitness/domain/medications/ReminderPlanner.kt) `plan()`, `isDueOn()` | Extend to compute overdue-vs-due at time `T`; excludes PRN. |
| Reminder domain models | [ReminderModels.kt](../../android/core-domain/src/main/java/com/gte619n/healthfitness/domain/medications/ReminderModels.kt) | `ReminderDose`, `PlannedReminder`. |
| Receivers (alarm / action / boot / periodic) | [ReminderReceivers.kt](../../android/core-data/src/main/java/com/gte619n/healthfitness/data/reminders/ReminderReceivers.kt) | Add midnight-rollover trigger; keep boot/tz re-plan. |
| Replan coordinator (mirror + push triggers) | `ReminderReplanCoordinator.kt` | Extend to re-post notification on adherence change (D7). |
| Adherence logging (offline outbox + mirror) | [AdherenceRepository.kt](../../android/core-data/src/main/java/com/gte619n/healthfitness/data/medications/AdherenceRepository.kt) | Add `markMissed`; observe flow for D7. |
| Today's doses overlay | [MedicationRepository.kt](../../android/core-data/src/main/java/com/gte619n/healthfitness/data/medications/MedicationRepository.kt) `todaysDoses()` | Source of the live remaining list. |
| **TimeSlot (Android domain)** | [MedicationModels.kt:61](../../android/core-domain/src/main/java/com/gte619n/healthfitness/domain/medications/MedicationModels.kt) `TimeSlot(window, dose)` | **Add `time: LocalTime?`.** |
| **TimeSlot (backend record)** | [TimeSlot.java](../../backend/src/main/java/com/gte619n/healthfitness/core/medication/TimeSlot.java) `record TimeSlot(window, dose)` | **Add nullable `time` ("HH:mm").** |
| Reminder settings model / resolution | [ReminderSettings.java](../../backend/src/main/java/com/gte619n/healthfitness/core/medication/ReminderSettings.java) `timeFor()` | Stays; explicit slot time takes precedence above it (D2). |
| Reminder settings screen (kept) | [ReminderSettingsScreen.kt](../../android/feature-medical/src/main/java/com/gte619n/healthfitness/feature/medical/reminders/ReminderSettingsScreen.kt) | Unchanged; coexists with drug setup. |
| Web med types / form | [medication.ts](../../web/lib/types/medication.ts), [me/meds/page.tsx](../../web/app/me/meds/page.tsx) | Add optional explicit time to `TimeSlot` + form. |
| Prior spec / decisions | [IMPL-16 spec](../specs/IMPL-16-med-reminders-and-nutrition-ux.md), [IMPL-16 decisions](../specs/IMPL-16-decisions-log.md) | Context for what we're replacing. |

**Distinction reminder:** "prescription" in this repo also means an *exercise* prescription (`LoggedPrescription`). This spec is strictly about **medications/drugs**.

---

## 4. Target behavior specification

### 4.1 Dose resolution at time `T` (pure logic — the reducer)

For the user's local date `today` and clock `T`, compute the **outstanding set**:

```
outstanding(T) = { dose d in scheduledDoses(today)
                   | d.frequency != PRN
                   | d.isDueOn(today)                    // WEEKLY/MONTHLY/CYCLE honored
                   | resolvedTime(d) <= T                // due or overdue (D3)
                   | not takenToday(d.medicationId, d.window) }
sorted by resolvedTime(d) ascending   // most-overdue first (D9)
```

- `resolvedTime(d)` applies **D2 precedence**: slot explicit time → per-med settings override → global window default.
- `takenToday` reads the adherence overlay (mirror + outbox), so in-app marks and undo are reflected immediately.
- Doses with `resolvedTime(d) > T` are **not shown** (hidden until their time) — this is what makes the afternoon batch "appear" at 13:00.

### 4.2 Notification rendering

- **At most one** notification, fixed id (e.g. `MED_REMINDER_NOTIFICATION_ID = <constant>`), replacing the per-firing id.
- Title: `"<n> medication(s) to take"` (count of `outstanding(T)`).
- Body: flat `InboxStyle` list, most-overdue first, each line `"<name> — <dose> <unit> · <h:mm a>"`. (No window headers — D9.)
- Actions (D8): ≤3 outstanding → one `✓ <name>` per dose; >3 → single `✓ Take all`. Body tap → `healthfitness://medications/today`.
- `setAutoCancel(false)`, swipeable (`setOngoing(false)` — D6).
- **Alerting (D4):** when `outstanding(T)` **gains** a dose that wasn't present in the last posted set (a new batch crossed into due), post with full alerting (default channel importance HIGH, `setOnlyAlertOnce(false)` for that post). When it only **shrinks** (decrement), post with `setOnlyAlertOnce(true)` / no re-alert.
- When `outstanding(T)` is empty → `cancel(MED_REMINDER_NOTIFICATION_ID)`.

### 4.3 Triggers that re-evaluate & re-post

| Trigger | Source | Alert? |
|---------|--------|--------|
| A dose's `resolvedTime` arrives | AlarmManager exact alarm, re-armed to the next distinct outstanding time | Re-alert (new-due) |
| Dose marked taken/undone in-app | Background observer on adherence flow (D7) | Silent |
| Dose marked from notification action | `ReminderActionReceiver` → `onDosesTaken` | Silent |
| Remote change (other device) | `ReminderReplanCoordinator` sync-push hint | Silent (unless it introduces a newly-due dose) |
| Boot / timezone / time change | `ReminderBootReceiver` | Re-evaluate; alert only if newly-due |
| Local midnight rollover | New midnight alarm | Mark missed + clear |
| 12h periodic safety net | `ReminderPlanWorker` | Re-evaluate |

### 4.4 Missed handling (D5, D11, D15)

- At **device-local midnight**, an alarm fires: every still-outstanding scheduled dose for the day just ended is written as a **missed** adherence record (`taken=false`, a distinct "missed" marker) via the normal outbox rail so it syncs to backend and surfaces in web history/stats.
- No retroactive-edit UI (D11). The existing Today's Doses screen still allows marking *today's* doses normally.
- If the device was powered off across midnight, **reconcile on next launch/boot**: any prior-day outstanding scheduled doses with no taken/missed record get a missed record.

### 4.5 Scheduling in drug setup (D1, D14, D16)

- `TimeSlot` gains an optional explicit time (`LocalTime?` Android / nullable `"HH:mm"` string on the wire).
- **UX (D16):** pick a window → default time shown (read-only preview); an optional "Set specific time" affordance reveals a time picker that sets the slot's explicit time. Clearing it reverts to the window default.
- Editable on **Android drug setup and web med form** (D14). Web stores/round-trips the field but shows no notifications.

### 4.6 Migration

- Additive field; default `null` (no explicit time) → behavior identical to today's window-default resolution. **No backfill required.**
- Existing `ReminderSettings` doc (global + per-med overrides) is untouched and still consulted per D2.

---

## 5. Phased development plan & tracking

Status legend per task: `[ ]` not started · `[~]` in progress · `[x]` code complete · `[T]` tested (automated proof green) · `[P]` pushed/merged.
A task is **Done** only at `[T]` (tested) and recorded `[P]` after merge. See §6 (Testing) and §7 (Definition of Done) for what "tested" means per phase.

### Phase 0 — Foundations: data model & time resolution (backend + shared)
Goal: the explicit-time field exists end-to-end and the precedence rule is one well-tested function.

- [T] P0.1 Add nullable `time` to backend `TimeSlot` record (2-arg compat ctor) + Firestore (de)serialization (`MedicationRepositoryImpl`).
- [T] P0.2 `ReminderSettings.resolveDoseTime(medId, slot)` applies **D2 precedence** (slot time → per-med override → window time → default).
- [T] P0.3 Web `TimeSlot.time` type + optional per-window time input in `AddMedicationButton`; round-trips through create; `tsc` + `next lint` clean.
- [T] P0.4 Android domain `TimeSlot(window, dose, time)` + `TimeSlotDto.time` + `MedicationMapper` parse/format. Also `DoseLog.missed` / `AdherenceMirrorPayload.missed` (D-2).
- [T] P0.5 Backend tests: `ReminderSettingsResolveTest` (precedence, 3) + `TodaysDosesServiceTest` missed-not-taken (D-2). Adherence `missed` persist path added.

**Phase 0 exit gate:** backend + web build green; precedence unit tests pass; a med created on web with an explicit slot time round-trips to the Android model.

### Phase 1 — Pure reducer: outstanding-dose logic
Goal: `outstanding(T)` is a pure, framework-free function with exhaustive tests. No Android, no notification.

- [T] P1.1 New `OutstandingDoses` reducer + `DueDose` model + shared `DoseTimeResolver` (D2). Computes overdue+due at injected clock, excludes PRN/muted/not-due.
- [T] P1.2 Sorted most-overdue-first; each `DueDose` carries the resolved `LocalTime` for rendering; `nextDueTime` for alarm arming.
- [T] P1.3 `takenToday` set excludes taken doses; `scheduledFor` feeds the missed rollover.
- [T] P1.4 `OutstandingDosesTest` (12): canonical 5→1→3→0 at 07:00/07:05/13:00, later-today hidden, PRN/muted/weekly excluded, explicit-time precedence, nextDueTime boundaries.

**Phase 1 exit gate:** ✅ `OutstandingDosesTest` (12) + trimmed `ReminderPlannerTest` (4) green; the 5→1→3 sequence asserted purely.

### Phase 2 — Single rolling notification engine (Android)
Goal: one notification, fixed id, driven by the reducer; correct alert-vs-silent semantics.

- [T] P2.1 Fixed `MED_REMINDER_NOTIFICATION_ID`; per-firing `notificationId(plannedAtMillis)` deleted (grep-confirmed no callers).
- [T] P2.2 `AndroidReminderNotifier.post` builds flat overdue-first body (`name — dose unit · time`) + count title + D8 actions.
- [T] P2.3 Alert-vs-silent (D4): engine diffs new key set vs `lastPostedKeys`; `setOnlyAlertOnce(!alert)`.
- [T] P2.4 `AndroidReminderScheduler.armDue` → next `OutstandingDoses.nextDueTime`; `ReminderAlarmReceiver`(DUE) → `onAlarmFired` → `refresh`.
- [T] P2.5 `onDosesTaken` logs then `refresh()` — same path as alarm/observer (D-6).
- [T] P2.6 `postOrCancel` cancels + resets `lastPostedKeys` when empty.

**Phase 2 exit gate:** ✅ `ReminderEngineTest` F1/F3/F4 + arming/disabled green (JVM+fakes, decision D-5).

### Phase 3 — Live in-app updates (background observer)
Goal: marking off in the app updates the notification while backgrounded (D7).

- [T] P3.1 `ReminderReplanCoordinator` observes the `medication_adherence` Room flow (debounced) → `engine.refresh()`.
- [T] P3.2 Coordinator started from `HealthFitnessApp.onCreate` (existing); application-lifetime scope, no foreground service (D6).
- [T] P3.3 Remote-sync path already re-plans; new-due detection is the same `lastPostedKeys` diff (D-7).

**Phase 3 exit gate:** ✅ `ReminderEngineTest.f5_inAppMark_decrementsSilently` + `ReminderReplanCoordinatorTest.replansOnAdherenceMirrorChange` green.

### Phase 4 — Missed rollover (D5/D11/D15)
Goal: local-midnight missed recording + boot reconciliation, synced.

- [T] P4.1 MIDNIGHT alarm (`AndroidReminderScheduler.armMidnight`) → `onMidnight` → `markMissedFor(endedDay)` via `AdherenceRepository.markMissed` (outbox → `missed:true`).
- [T] P4.2 `onMidnight` cancels the notification + resets, then `refresh()` for the new day (re-arms next midnight).
- [T] P4.3 Boot → `reconcileMissed()` marks yesterday (idempotent via `recordedWindowsFor`), decision D-9.
- [T] P4.4 Backend `DoseLog.missed` persists; `LogDoseRequest.missed`/`DoseLogResponse.missed`; adherence summary + today exclude missed (D-2).

**Phase 4 exit gate:** ✅ `ReminderEngineTest` F6 (midnight) + F8 (boot) assert `markMissed` calls + clear; backend `TodaysDosesServiceTest.missedDoseDoesNotCountAsTaken`.

### Phase 5 — Drug-setup scheduling UI (D16)
Goal: window-preset + tap-to-refine time on Android and web.

- [T] P5.1 `TimeSlotEditor` per-slot time row: window default hint + "Set time" (reuses `ReminderTimePickerDialog`) + clear; persists `slot.time`.
- [T] P5.2 Web `AddMedicationButton` optional per-window `<input type="time">`; builds `TimeSlot.time`.
- [T] P5.3 Reminder-settings screen untouched (coexists); precedence proven by `ReminderSettingsResolveTest` + `OutstandingDosesTest.explicitSlotTime_overridesWindowDefault`.

**Phase 5 exit gate:** ✅ `feature-medical` + `app` compile green; precedence asserted by unit tests. (Visual walkthrough is §6.6, pending human run.)

### Phase 6 — Cleanup, docs, decision log
- [T] P6.1 Removed dead multi-notification code: `ReminderPlanner.plan()`, `PlannedReminder`, `ReminderDose`, per-firing id, `EXTRA_REMAINING`/`EXTRA_PLANNED_AT`/`encode`/`decodeDoses`.
- [x] P6.2 IMPL-16 spec annotated as superseded by IMPL-21 (Part A).
- [x] P6.3 `IMPL-21-decision-log.md` written (D-1…D-12).
- [x] P6.4 This dashboard updated to final state.

### Progress dashboard (update as you go)

| Phase | Code | Tested | Pushed | Notes |
|-------|:----:|:------:|:------:|-------|
| P0 Data model & resolution | ✅ | ✅ | ⬜ | backend+web+android; `TimeSlot.time`, `DoseLog.missed` |
| P1 Outstanding reducer | ✅ | ✅ | ⬜ | `OutstandingDoses` (12 tests) |
| P2 Single notification engine | ✅ | ✅ | ⬜ | notifier/scheduler seams + engine (9 tests) |
| P3 Live in-app updates | ✅ | ✅ | ⬜ | coordinator adherence observer |
| P4 Missed rollover | ✅ | ✅ | ⬜ | midnight + boot reconcile |
| P5 Drug-setup UI | ✅ | ✅¹ | ⬜ | ¹compile+precedence unit; visual walkthrough pending (§6.6) |
| P6 Cleanup & docs | ✅ | ✅ | ⬜ | dead code removed; docs updated |

**Not pushed:** no commit/push performed — awaiting human review of the decision log (per request).

---

## 6. Testing approach

Test both the **technical** implementation (units, wiring) and the **functional** behavior (the acceptance story), with an injected clock so time-dependent behavior is deterministic and repeatable. **A clock must never be read from the system in testable code paths** — the reducer and engine take a `Clock`/`() -> Instant` so tests drive time.

### 6.1 Test infrastructure prerequisite
- [ ] Inject a `Clock` (or `TimeSource`) into `ReminderPlanner`/reducer and `ReminderEngine` so tests can set `T` and advance it. This is a hard prerequisite for the functional tests below.

### 6.2 Unit tests (JVM, pure) — Phase 1 primary proof
Assert `outstanding(T)` directly:
- **Canonical story:** 5 morning@07:00 + 2 afternoon@13:00.
  - `T=07:00` → 5 doses, order by time.
  - mark 4 taken, `T=07:05` → **1 dose**.
  - `T=13:00`, morning #5 untaken → **3 doses**, most-overdue (the 07:00 one) first.
  - mark all → **0**.
- Later-today hidden: `T=07:05` never includes the 13:00 doses.
- PRN excluded entirely.
- Frequency honored: weekly-not-today / monthly / cycle off-week → not in set.
- DST / timezone-change day resolves times without drift.
- D2 precedence: slot explicit time overrides settings override overrides window default (three-way).

### 6.3 Instrumented tests (Android, fake clock) — **the functional acceptance gate** (D chosen verification)
Drive the real `ReminderEngine` + `NotificationManager` (Robolectric shadow or on-device) with an injected clock:
- **F1 (rolling decrement):** post at 07:00 → shadow shows 1 notification, 5 lines. Log 4 via repository → same notification id, 1 line, `onlyAlertOnce` (silent). Assert **exactly one** active notification throughout.
- **F2 (cross-window re-alert):** advance clock to 13:00, fire alarm → notification now 3 lines, most-overdue first, posted **with alert** (new-due detected).
- **F3 (clear on complete):** mark all → notification cancelled.
- **F4 (single-notification invariant):** across morning+afternoon firings, `shadowNotificationManager.activeNotifications` size never exceeds 1.
- **F5 (in-app background update — Phase 3):** with engine not foregrounded, log a dose through `AdherenceRepository` → observer re-posts decremented + silent.
- **F6 (missed rollover — Phase 4):** advance to local midnight, fire → missed records written for outstanding doses; notification cancelled; new day starts empty.
- **F7 (swipe/return — D6):** simulate dismiss → next due-time alarm re-posts with alert.
- **F8 (boot reconcile — D15):** simulate boot after a skipped midnight → prior-day outstanding become missed.

### 6.4 Backend tests (JVM/Spring)
- Explicit `time` on `TimeSlot` persists/round-trips through create+edit.
- `resolveDoseTime` precedence (D2) unit-tested server-side.
- Missed adherence status persists and is returned by history/stats endpoints.

### 6.5 Web tests
- Med form accepts optional explicit time; create/edit round-trips; empty → window default (no crash, field omitted).

### 6.6 Manual verification (supplementary, not the gate)
- Owner-account emulator walkthrough of the canonical story with screenshots at 5→1→3→0 for the decision log. Automated F1–F8 are authoritative; this is documentation.

---

## 7. Definition of Done

### Per-task DoD
A task moves to `[T]`/Done only when **all** hold:
1. Code compiles; module builds green (`backend`, `web`, and affected Android modules).
2. The task's automated tests exist and **pass** (unit and/or instrumented per phase).
3. No regression in existing medication/reminder tests.
4. Lint/static checks for the touched modules pass.

### Per-phase DoD
The phase's **exit gate** (listed in §5) is met and its §6 test slice is green.

### Feature-level DoD (all must be true)
- [x] **F1–F8 functional tests pass** — authoritative proof of rolling/decrement/re-alert/missed. Implemented as JVM+fakes+fake-clock (decision D-5): `ReminderEngineTest` (9), all green.
- [x] The single-notification invariant (F4) holds: `ReminderEngineTest.f1…` asserts `maxActive <= 1` and one fixed id.
- [x] Old per-window multi-notification code deleted (D12) — grep confirms no callers of `notificationId(plannedAtMillis)`, `EXTRA_REMAINING`, `encode/decodeDoses`, `PlannedReminder`, `ReminderDose`.
- [x] Explicit slot time settable in **both** Android (`TimeSlotEditor`) and web (`AddMedicationButton`) drug setup, round-trips, and **wins** over the settings override — `ReminderSettingsResolveTest` + `OutstandingDosesTest.explicitSlotTime_overridesWindowDefault`. (Visual confirmation = §6.6, pending.)
- [x] Missed doses recorded at local midnight and flow to backend adherence (D11): `ReminderEngineTest` F6/F8 + `DoseLog.missed` persistence; stats exclude missed (`TodaysDosesServiceTest`, `MedicationController` summary).
- [x] Re-alert-on-new-due / silent-on-decrement verified (F2 alert=true vs F1 decrement alert=false).
- [x] `IMPL-21-decision-log.md` written; IMPL-16 spec annotated as superseded.
- [x] Progress dashboard (§5) reflects reality.
- [ ] Manual owner walkthrough (§6.6) — **pending human run** (requires an emulator/device session).
- [ ] Merge + deploy — **pending human approval**.

---

## 8. Agent verification protocol (how an agent proves "done" before claiming it)

Before marking any phase Done, the agent MUST run the relevant commands and paste the **actual output** (not a summary). Claiming done without green output is a violation.

1. **Backend/shared (P0, P4 backend, §6.4):**
   `./gradlew :backend:test --tests '*Medication*' --tests '*Reminder*' --tests '*TimeSlot*'`
   → must show `BUILD SUCCESSFUL` and the named tests executed.
2. **Reducer units (P1, §6.2):**
   run the JVM test class for the outstanding-dose reducer → all cases green, including the 5→1→3 sequence.
3. **Instrumented functional gate (P2–P4, §6.3):**
   run the reminder-engine instrumented/Robolectric suite → **F1–F8 green**. This is the non-negotiable functional gate.
4. **Web (P0.3, P5.2):**
   web typecheck + med-form test green.
5. **Single-notification invariant:**
   confirm F4 assertion exists and passes; `grep -rn "notificationId(plannedAtMillis)"` returns **no** callers after P6.1.
6. **Manual story (supplementary):**
   capture 4 screenshots (5→1→3→0) on the owner account/emulator into the decision log.

If any command fails or a test is skipped, the phase stays **not Done** and the failure output is recorded in the dashboard notes.

### Verification results (this implementation, 2026-08-27)

Environment: JDK 21 (sdkman `21.0.3-jbr`), Android SDK at `/opt/homebrew/share/android-commandlinetools`, worktree `local.properties` written, `-PwebOauthClientId=dummy…` to pass the app config gate; web deps via `pnpm install`.

| Gate | Command | Result |
|------|---------|--------|
| Backend precedence + missed | `:backend test --tests '*medication*' '*Adherence*' '*Dose*'` | ✅ `ReminderSettingsResolveTest` 3, `TodaysDosesServiceTest` 5, `ReminderSettingsServiceTest` 3 — 0 failures |
| Reducer units (P1) | `:core-domain:testDebugUnitTest --tests '…domain.medications.*'` | ✅ `OutstandingDosesTest` 12, `ReminderPlannerTest` 4 — 0 failures |
| Functional engine gate (F1–F8) | `:core-data:testDebugUnitTest --tests '…data.reminders.*'` | ✅ `ReminderEngineTest` 9, `ReminderReplanCoordinatorTest` 4 — 0 failures |
| Adherence repo | `:core-data:testDebugUnitTest --tests '…AdherenceRepositoryTest'` | ✅ 2 — 0 failures |
| Web typecheck + lint | `pnpm run typecheck` / `pnpm run lint` | ✅ tsc clean; lint only pre-existing warnings |
| Hilt DI graph | `:app:hiltJavaCompileDebug` | ✅ BUILD SUCCESSFUL (notifier/scheduler/clock providers + coordinator resolve) |
| Full compile | `:app:compileDebugKotlin`, `:feature-medical:compileDebugKotlin` | ✅ BUILD SUCCESSFUL |
| Dead-code removal | `grep notificationId(plannedAtMillis) / PlannedReminder / ReminderDose / EXTRA_REMAINING` | ✅ no production callers |

Mapping to F1–F8: F1 `f1_rollingDecrement_isSilentAndSingle`, F2 `f2_afternoonBatch_reAlerts_mostOverdueFirst`, F3 `f3_clearsWhenAllTaken`, F4 asserted inside F1 (`maxActive<=1`), F5 `f5_inAppMark_decrementsSilently`, F6 `f6_midnight_marksUntakenMissed_andClears`, F7 `f7_afterSwipe_nextBatchReAlerts`, F8 `f8_bootReconcile_marksYesterdayMissed`.

Not run here (require a device/emulator or the Firestore emulator, left for the human/CI pass): the backend `AdherenceSameDayConcurrencyTest` (emulator), Android instrumented Room tests, and the §6.6 manual walkthrough with screenshots.

---

## 9. Risks & open questions

- **Battery / always-on observer (D7):** a long-lived flow collector must be scoped so it doesn't leak or hold the process awake unnecessarily; validate it doesn't require a foreground service (D6 says it must not). Fallback: re-post on app foreground + alarm ticks if the background observer proves unreliable on aggressive OEMs.
- **Exact-alarm permission revoked:** existing 15-min windowed fallback ([ReminderEngine.kt:285](../../android/core-data/src/main/java/com/gte619n/healthfitness/data/reminders/ReminderEngine.kt) `FALLBACK_WINDOW_MILLIS`) means re-alert timing may drift; acceptable, document it.
- **Force-stopped app:** no alarms fire until relaunch; midnight-missed reconciliation on launch (D15/F8) covers correctness but not timeliness.
- **Multi-device:** the notification is phone-local; adherence/missed sync keeps state consistent, but two phones each render their own single notification (acceptable — still "one per phone").
- **DST / travel:** re-plan on `TIMEZONE_CHANGED`/`TIME_CHANGED` already exists; reducer must resolve times in the *current* local zone.
- **"Missed" marker shape:** confirm backend adherence schema can represent taken=false/missed distinctly from "no record"; if not, P4.4 needs a small schema addition.

---

## 10. Out of scope (non-goals)

- Snooze action (D13).
- PRN meds in the notification (D13).
- Retroactive missed-dose editing UI (D11).
- Web-side notifications/scheduling (Android-only delivery, unchanged).
- Periodic re-nag of ignored doses (D10).
- Feature-flag / A-B dual-run (D12 — replace outright).
