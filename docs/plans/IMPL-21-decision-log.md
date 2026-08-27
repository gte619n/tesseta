# IMPL-21 — Decision Log

Running log of implementation decisions for the Single Rolling Medication Reminder
([spec](IMPL-21-single-rolling-medication-reminder.md)). Each entry: what was decided,
why, and the alternative rejected. Review after implementation to tweak.

Legend: **D-N** decision · **♦** deviation from spec · **⚑** risk accepted.

## Review outcome (2026-08-27) — ratified

Evan reviewed the log; **every decision was accepted as-built, no code changes requested.** Specifically confirmed:
- **D-5** (JVM+fakes instead of instrumented tests) — accepted as the F1–F8 gate; instrumented tests not required.
- **D-2** (taken = `missed=false`; adherence logic changed) — accepted as a correct fix; old logs default `missed=false` so historical stats are unchanged.
- **D-7** (in-memory alert state; one stray buzz possible after a cold process start) — accepted; not persisting.
- **D-9** (boot reconcile marks yesterday only) — accepted; multi-day catch-up not wanted.
- **D-11 / D-12** (drug-setup time-picker UX; web parity) — UX matches intent, keep as built.
- **Missed stays invisible on web** (follow-up #2) — no web missed badge; stats-only per spec D11.
- **Notification content** — flat list `name — dose unit · time`, count title, no overdue flagging — keep as built.

No open items remain from the review beyond the pre-existing "pending human run" gates (manual walkthrough §6.6, Firestore-emulator test, merge/deploy).

---

---

## Phase 0 — Data model & time resolution

### D-1 — `TimeSlot.time` is an optional `LocalTime` (wire: `"HH:mm"` string)
Added a nullable explicit time to the dose slot on all three stacks. Precedence
(spec D2) is realized as `resolveDoseTime = slot.time ?: settings.timeFor(medId, window)`
so the slot time (drug setup) beats the per-med settings override beats the global
window default — all in one expression, no new precedence machinery.
- Backend `TimeSlot` gets a 3-arg canonical record component plus a **2-arg
  compatibility constructor** `TimeSlot(window, dose)` → `(window, dose, null)`, so the
  ~5 existing `new TimeSlot(window, dose)` call sites keep compiling (Java records have
  no default params).
- Android domain `TimeSlot(window, dose, time = null)` uses a Kotlin default param, so
  existing positional `TimeSlot(window, dose)` construction (incl. tests) is unaffected.
- Wire (`TimeSlotDto`) carries `time: String?` ("HH:mm"); the mapper parses/formats.
Rejected: a separate `slotTimes` map on the medication — splits one concept across two
fields and complicates the editor.

### D-2 — "Missed" is a first-class `missed` boolean on the dose log, not a sentinel
`DoseLog` (backend) and `AdherenceMirrorPayload` (Android) gain `missed` (default false).
Semantics: a dose is **taken** iff a log row exists with `missed=false`; **missed** iff a
row exists with `missed=true`; **no data** iff no row. This distinguishes an
auto-recorded miss from an untouched dose (spec D11), which the previous
"any-log-means-taken" model could not.
- ♦ Consequence: two latent correctness fixes were required and made —
  `TodaysDosesService` now counts a `(med,window)` as taken only when its dose log is
  `missed=false`, and `MedicationController.calculateAdherenceSummary` counts a day as
  adherent only when it has a non-missed dose. Without these, injected missed rows would
  have inflated "taken"/adherence stats.
Rejected: a separate `missedDoses` collection — doubles the read paths for the daily
projection and the 30-day summary.

### D-3 — Backend keeps `LocalTime` slot times; wire is `"HH:mm"`
`MedicationRepositoryImpl` (de)serializes the slot `time` as an ISO `HH:mm` string in
Firestore (null omitted), matching every other time-of-day on the wire (reminder
settings already use `"HH:mm"`). `resolveDoseTime` added to `ReminderSettings` for the
server-side precedence unit test even though scheduling is Android-local — keeps the
precedence rule provable on the server slice (spec §6.4).

---

## Phase 1 — Outstanding-dose reducer

### D-4 — New pure `OutstandingDoses` object rather than overloading `ReminderPlanner`
`ReminderPlanner` answers "when do upcoming reminders fire?" (grouped by time, strictly
future). The rolling notification needs a different question — "what is overdue+due at
clock T?" — so it lives in a dedicated pure object with its own model (`DueDose`, which
unlike `ReminderDose` carries the resolved `LocalTime`). Both share `isDueOn` and the
slot/time resolution helper to avoid divergence. `ReminderPlanner` was updated to honor
`slot.time` too, so alarm arming fires at the explicit time.

---

## Phase 2–4 — Engine (populated during implementation)

### D-5 ♦ — Engine seams (`ReminderNotifier`/`ReminderScheduler`/`Clock`) instead of instrumented tests
The spec's DoD gate (F1–F8) asked for **instrumented** tests with a fake clock. This repo
has no Robolectric on `core-data`'s unit classpath, and its own reminder logic is unit-
tested by abstracting the framework (see `ReminderReplanCoordinatorTest`, which injects
the replan action as a lambda). Following that idiom, the engine's framework touchpoints
are extracted behind `ReminderNotifier` (post/cancel) and `ReminderScheduler` (arm/cancel
alarms), and time is injected as `java.time.Clock`. The orchestration — single
notification, alert-vs-silent diff, next-alarm selection, midnight missed rollover — is
then covered by **fast JVM tests with fakes + an adjustable clock**, which assert the same
F1–F8 behaviors more precisely than a shadow framework would. The production Android
implementations of the two seams are thin and carry no branching logic.
⚑ Trade-off: the exact `NotificationCompat` builder output / PendingIntent wiring is not
asserted by an automated test (it has no logic to get wrong); it is covered by the manual
owner walkthrough (spec §6.6).

### D-6 — One fixed notification id; `onDosesTaken` re-derives state via `refresh()`
The per-firing `notificationId(plannedAtMillis)` is deleted in favor of a single constant
`MED_REMINDER_NOTIFICATION_ID`. The notification-action path no longer threads an encoded
"remaining" list through the intent; it just logs the taken dose(s) and calls `refresh()`,
which recomputes outstanding from the reducer and re-posts (silent) or cancels. This makes
the action button, in-app marking, and alarm firing all funnel through one code path.

### D-7 — Alert-vs-silent via an in-memory last-posted key set
`refresh()` diffs the new outstanding `(med:window)` key set against the last posted set
(held in a `@Volatile` field on the singleton engine). Re-alert iff a key appears that
wasn't posted before (a new batch crossed into due, or first post of the session);
otherwise silent (a decrement). Rejected persisting the set: process death simply makes the
next post alert once, which is acceptable and matches "reappears with re-alert" (spec D6).

### D-8 — Two alarms: a DUE alarm and a MIDNIGHT alarm (distinct request codes/actions)
The DUE alarm is armed for the next resolved dose time strictly after now (via
`ReminderPlanner`); firing → `refresh()`. The MIDNIGHT alarm is armed for the next local
`00:00`; firing → mark the just-ended day's untaken scheduled doses missed, cancel the
notification, then `refresh()` for the new day. Separate PendingIntents keep the two
semantics unambiguous at fire time (no time-comparison guesswork).

### D-9 ⚑ — Missed reconciliation covers the previous day only
On boot / app start, `reconcileMissed()` marks yesterday's untaken scheduled doses missed
(the common "device off across one midnight" case). Multi-midnight gaps (device off >1
day) only reconcile the most recent day; older gaps are left as no-data rather than risk a
flood of retroactive writes over dates that may predate a med's schedule. `markMissedFor`
is idempotent — it skips any `(med,window)` that already has a taken **or** missed record
for the date — so repeated boots don't double-write.

### D-10 — Live in-app updates: coordinator observes the adherence mirror → `engine.refresh()`
`ReminderReplanCoordinator` gains a third source: the `medication_adherence` Room flow.
A logged/undone dose (from the Today screen, backgrounded) debounces into
`engine.refresh()`, which re-posts the single notification decremented and silent (spec
D7). No foreground service (spec D6).

---

## Phase 5 — Drug-setup scheduling UI

### D-11 — Per-slot time picker lives inside `TimeSlotEditor` (window preset → tap to refine)
Selecting a window adds the slot with `time=null` (uses the window default, shown as a
hint). A "Set time" control on the slot row reveals a time picker that sets `slot.time`;
clearing it reverts to the default (spec D16). The existing `InlineReminderControls`
(per-med settings override) is left intact — the two surfaces coexist (spec §2 kept-both),
with the slot time taking precedence (D-1).

### D-12 — Web parity: optional time input per slot in the meds form
The web medication form gets an optional `time` input alongside each window/dose row; it
round-trips through `TimeSlot.time` (spec D14). Web shows no notifications; the field only
feeds the schedule the Android reminder consumes.

---

## Verification summary & open follow-ups

**Verified green (2026-08-27):** backend medication/adherence tests (incl. new
`ReminderSettingsResolveTest`, missed-not-taken); `OutstandingDosesTest` (12);
`ReminderEngineTest` (9, = F1–F8); `ReminderReplanCoordinatorTest` (4);
`AdherenceRepositoryTest` (2); web `tsc`/`next lint`; `:app:hiltJavaCompileDebug` (DI
graph); full Kotlin compile. Details in the spec's §8 "Verification results".

**Not run in this pass (need a device/emulator or CI):** backend
`AdherenceSameDayConcurrencyTest` (Firestore emulator), Android instrumented Room tests,
and the manual owner walkthrough with screenshots (spec §6.6).

### Follow-ups for human review
1. ✅ **Edit-flow parity for explicit time — resolved.** The web `MedicationDetailModal`
   edit form rebuilt `timeSlots` from windows and would have dropped `time`; it now seeds
   and edits a per-window time (same control as add). The Android detail edit only sends
   `frequency`/`startDate` in `UpdateMedicationRequest` (never `timeSlots`), so
   `MedicationRepository.update` keeps `current.timeSlots` — times are preserved. No
   further action; noted here in case a future Android slot-editor is added to detail.
2. ⚑ **Missed on web.** The backend now stores `missed` and stats/today exclude it, so web
   adherence numbers are correct, but there is no dedicated "missed" badge in the web UI —
   out of scope per spec D11 ("recorded silently"). Revisit if a visible missed indicator
   is wanted.
3. **Alert-state persistence.** `lastPostedKeys` is in-memory (decision D-7); a process
   death makes the next post alert once. Acceptable per spec D6, but if users report a
   stray buzz after app restart, persist the set.
4. **Boot reconcile depth.** Only yesterday is reconciled (decision D-9). If multi-day
   device-off gaps must record missed for every skipped day, widen `reconcileMissed`.
