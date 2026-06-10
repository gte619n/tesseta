# IMPL-16: Medication Reminders & Nutrition Entry UX

> **Status: implemented** (all seven sequencing steps, on
> `feature/ui_ux_improvement`). Implementation deviations and the decisions
> made along the way are recorded in
> [IMPL-16-decisions-log.md](IMPL-16-decisions-log.md) — notably A1-1
> (reminder config is one settings document, not Medication fields), AND-1
> (a new `relog` endpoint backs one-tap recents), and A2-1 (a single-alarm
> chain rather than N scheduled alarms).

## Goal of this spec

Two themes, planned together because they were requested together:

1. **Medication dose reminders on Android** — local notifications at
   user-configured times, one notification per time covering every drug due
   then, with per-drug "Taken" actions that log adherence without opening the
   app, auto-clearing when everything is checked off.
2. **Nutrition entry overhaul** — make every add-food path feel instant
   (fire-and-forget with in-place processing placeholders), recognize packaged
   products as the *exact* branded product (name + studio image), enforce
   calories = f(macros) everywhere, surface recent meals for one-tap re-log,
   and collapse the add-meal UI into a single faster surface with the meal
   auto-detected from time of day.

Backend + Android + web in this spec (notifications are Android-only;
everything else lands on both clients).

## Decisions locked

| Decision | Choice |
|---|---|
| Reminder time model | User-level default time per `TimeWindow` (e.g. MORNING → 06:00) set once in settings; any medication time-slot may override with its own explicit time |
| Reminder delivery | Computed and scheduled **locally on Android** (AlarmManager) from the Room medication mirror — works offline, no backend push needed; backend only stores the config |
| Grouping | All meds resolving to the same fire time share one notification; per-med "Taken" actions (≤3 meds) or a single "Take all" action (>3); notification auto-cancels when all are logged |
| Unacted notification | Persistent until acted on or swiped away — no re-alert, no snooze (v1) |
| Macro/calorie reconciliation | **Calories are always derived from macros** (4·protein + 4·carbs + 9·fat), at every write path and in AI/label extraction; calorie fields in entry UIs become live-computed, read-only |
| Packaged products | Photo analysis extracts exact product identity (brand + product + variant); image generation reproduces the **exact branded product including packaging** in house studio style, using the capture photo as reference |
| Describe-a-meal flow | Fire-and-forget: button immediately closes the modal and logs an `ANALYZING` placeholder (same pattern as camera capture); no preview step — edit afterward if wrong |
| Camera capture flow | Pop back to Today **before** the upload completes — optimistic local placeholder + background upload, not just the existing 202 placeholder |
| Recent meals | New endpoint: distinct meals/foods logged in the last 14 days, deduped by identity, newest first, ~20 items; one tap re-logs same portions |
| Meal type selection | Auto-inferred from time of day (reuse `Meal.forHour` boundaries), shown as a tappable chip override — the button row is removed |
| Platform scope | Backend + Android + web together; web gets the modal redesign and recent meals too |

---

## Part A — Medication reminders (backend + Android)

### A1. Backend: reminder configuration

The schedule data (frequency, time slots, dosage periods) already exists; we
only add *when to fire*.

- **User-level window times** — new `reminderWindowTimes` map on the user
  settings document (`MORNING: "06:00"`, `AFTERNOON: "12:00"`,
  `EVENING: "18:00"`, `BEDTIME: "21:30"` defaults). Exposed via the existing
  user settings endpoint (GET/PATCH).
- **Per-med override** — `TimeSlot` gains optional `reminderTime: LocalTime`
  (null ⇒ use the user-level window time). Plumb through
  `CreateMedicationRequest` / `UpdateMedicationRequest`, DTOs, and the Android
  domain model + mappers.
- **Per-med mute** — `remindersEnabled: Boolean` (default true) on
  `Medication`, so a drug can opt out without being discontinued.
- No new push infrastructure. The existing silent FCM `sync` message already
  wakes clients when medications change, which keeps the Room mirror (and thus
  the alarm schedule) fresh.

### A2. Android: scheduling

New `core-data` (scheduler) + `app` (notification plumbing) pieces:

- **Permissions**: add `POST_NOTIFICATIONS` (runtime request on the
  medications screen the first time reminders are active),
  `SCHEDULE_EXACT_ALARM` (fall back to `setWindow` if the special access is
  revoked), `RECEIVE_BOOT_COMPLETED`.
- **Notification channel**: `medication_reminders` (IMPORTANCE_HIGH).
- **`ReminderPlanner`** (WorkManager daily + on every medication-mirror
  change): reads active medications from Room, resolves each due slot for the
  next 48h to a concrete fire time (per-med override ?: user window time),
  honoring `FrequencyType` (DAILY, WEEKLY `specificDays`, MONTHLY, CYCLE
  on/off weeks; **PRN excluded**), skipping doses already logged today.
  Groups by fire time and schedules one exact alarm per distinct time via
  AlarmManager (`setExactAndAllowWhileIdle`).
- **`ReminderReceiver`** (BroadcastReceiver, fires at T): re-resolves which
  meds are due at T and *still untaken* (a dose logged in-app since planning
  drops out), posts one notification:
  - Title: "Medications — 6:00 AM"; body lists each drug + dose.
  - ≤3 meds: one "✓ Took <name>" action per med. >3 meds: "✓ Take all" +
    "Open".
  - Actions route to a `ReminderActionReceiver` that logs adherence through
    the existing `MedicationRepository` outbox (works offline), then re-posts
    the notification minus that med; when none remain it cancels.
  - Tapping the notification deep-links to the medications Today checklist.
- **Resilience**: `BOOT_COMPLETED` + `TIMEZONE_CHANGED`/`TIME_SET` receivers
  re-run the planner; discontinue/delete cancels that med's alarms (planner
  re-run handles it).

### A3. Android: settings UI

- Medications screen gains a reminder settings entry: the four window times
  (time pickers) + master toggle.
- Add/edit medication screen: per-slot optional "Remind at…" time picker and
  a per-med reminders toggle.

---

## Part B — Instant-feel entry (backend + Android + web)

### B1. Describe-a-meal goes async (the camera pattern)

- **Backend**: new `POST /api/me/nutrition/{date}/describe-meal-async`
  (alongside the existing sync one). Mirrors `MealCaptureService.captureMeal`:
  create an `ANALYZING` placeholder entry immediately (name = the raw
  description text, zero macros), return 202, resolve via the existing
  `MealDescriptionExtractor` off-thread, finalize to a composite entry
  (`READY`) or `FAILED`, fire `syncNotifier`.
- **Android**: "Find or create" → call async endpoint, close the sheet
  immediately, `refreshDay` pulls the placeholder; the existing settle-poll
  already handles `ANALYZING → READY`. The preview pane is deleted.
- **Web**: same endpoint; modal closes on 202, the day list renders the
  `ANALYZING` row with a processing treatment; extend
  `PendingImageRefresher` to also poll while any entry is `ANALYZING`.

### B2. Camera pops back instantly

Today the user waits on `AnalyzingPane` while the photo uploads. Change:

- On shutter/confirm, write an optimistic local placeholder entry to Room
  (`syncState=PENDING`, `analysisStatus=ANALYZING`, thumbnail = local photo)
  and **pop immediately** to Today.
- Upload happens in the background (ViewModel scope is not enough across
  process death — enqueue a one-shot expedited `CaptureUploadWorker` carrying
  the JPEG file reference). On 202, reconcile the local placeholder with the
  server entry id; the settle-poll takes over. On hard failure, flip the local
  row to a retryable error state.

### B3. Spinner refinement

- Search: inline trailing progress indicator inside the search field (both
  clients) instead of a separate centered block that shifts the layout.
- Keep result-area skeletons stable-height so the list doesn't jump.

---

## Part C — Exact packaged products (backend)

- **`MealPhotoExtractor`**: prompt + `extract_meal_items` tool schema gain
  `brand` and exact `productName` ("Fairlife Core Power Elite 42g, chocolate"
  — brand, line, flavor/size when legible). Keep `isPackagedProduct`.
  Parsing carries brand into `MealAnalysis`.
- **`MealDescriptionExtractor`**: same schema addition (a described "Core
  Power chocolate shake" should also resolve to the exact product).
- **`MealCaptureService.finalizeProduct`**: populate `CatalogFood.brand`,
  entry/food name = exact product name. Before creating a new catalog food,
  attempt a match against the existing catalog (`brand` + `nameLower`) so
  repeat captures of the same product reuse the same food + image.
- **`GeminiFoodImageGenerator`**: the packaged-product prompt is rewritten —
  reproduce the EXACT product from the attached reference photo, *including
  its real container and label/branding*, re-shot in house studio style. The
  "no brand names" restriction is removed **for the product category only**;
  ingredient and plated-meal prompts are unchanged.

---

## Part D — Calories derived from macros (backend + both clients)

- **`MacroMath`** utility in `core.nutrition`:
  `kcal = 4·protein + 4·carbs + 9·fat` (carbs include fiber; no alcohol field
  exists), plus `Macros.withDerivedCalories()`.
- **Backend enforcement** (server is the source of truth — derive, don't
  validate): applied in `NutritionService.addEntry / updateEntry / logDay`,
  ingredient re-portioning, `MealCaptureService` finalization, the three
  extractors' outputs, and `FoodCatalogService.create` for Gemini-sourced
  foods. OFF/USDA-seeded `macrosPer100g` are normalized at read-into-entry
  time (the snapshot), leaving upstream source data untouched.
- **Clients**: quick-add and edit forms make the calories field read-only and
  live-computed as macros are typed (Android `AddFoodSheet` quick-add pane,
  web `AddFoodModal` quick-add tab, plus both entry-edit surfaces).

---

## Part E — Add-meal redesign + recent meals (backend + both clients)

### E1. Backend: recent meals endpoint

`GET /api/me/nutrition/recent-meals?days=14&limit=20` — scans the user's
recent entries, dedupes by identity (composite/described: `mealId` or
normalized name; catalog/barcode: `foodId`), newest first. Each item returns
what one-tap re-log needs: display name, image, macros snapshot, serving
label/grams/quantity, and the ids to re-log it (`foodId` or full ingredient
list for composites). Re-log goes through the existing add-entry /
composite-meal endpoints.

### E2. The new add surface (Android sheet + web modal, same structure)

```
┌──────────────────────────────────────────┐
│  Add food          [→ Lunch ▾]  (chip)   │  ← auto from time of day, tappable
│  ┌────────────────────────────────────┐  │
│  │ 🔍 Search or describe a meal…   ◌ │  │  ← one field, inline spinner
│  └────────────────────────────────────┘  │
│  RECENT                                   │
│  [img] Chicken & rice bowl   620 kcal  + │  ← one tap = re-log
│  [img] Core Power Elite 42g  230 kcal  + │
│  …                                        │
│  ──────────────────────────────────────  │
│  ✨ Log "two eggs and toast…" as a meal   │  ← describe = fire-and-forget row
│  ⚖  Quick add (manual macros)            │
└──────────────────────────────────────────┘
```

- **Meal chip**: `Meal.forHour` boundaries (already defined) move to shared
  logic on each client; chip tap opens a small picker. The button row is gone.
- **One field, two behaviors**: short queries search the catalog as you type
  (debounced); the describe action is always available as a row beneath the
  results ("Log '<text>' as a meal") that fires B1's async endpoint. The
  separate tabs are removed — recents, search results, describe, and quick
  add live in one scrolling surface.
- **Recent list** is the default (empty-query) content — the fastest path is
  open → tap → done, two interactions total.
- **Camera** stays as today's entry point on Android (top-bar capture), now
  popping back instantly per B2.

---

## Sequencing

Ordered to put shared backend foundations first; each step is independently
shippable and tested:

| # | Scope | Contents |
|---|---|---|
| 1 | backend | Part D (MacroMath + enforcement at all write paths + tests) |
| 2 | backend | Part C (brand extraction, exact-product prompts, catalog reuse-by-brand) |
| 3 | backend | Part B1 + E1 (async describe endpoint, recent-meals endpoint) |
| 4 | android | Parts B + D + E client-side (capture worker, async describe, new add sheet, recent list, meal chip, read-only calories) |
| 5 | web | Parts B + D + E client-side (modal redesign, ANALYZING polling, recents, meal chip, read-only calories) |
| 6 | backend | Part A1 (reminder config fields + settings endpoint) |
| 7 | android | Parts A2 + A3 (permissions, planner, alarms, notification actions, settings UI) |

Steps 1–3 are pure backend and unblock both clients in parallel; step 7 is
the largest single piece and has no dependency on the nutrition work.

## Verification

- Backend: unit tests per service change (macro derivation at every path,
  brand extraction parsing, async describe lifecycle ANALYZING→READY/FAILED,
  recent-meals dedupe); existing suite stays green.
- Android: planner unit tests across frequency types (WEEKLY specificDays,
  CYCLE weeks, PRN excluded, already-taken excluded); notification
  action→adherence-log integration test with the outbox; manual smoke on a
  device for alarm firing, action check-off, auto-clear, and reboot
  rescheduling.
- Web: component tests for the new modal states; `pnpm test` green.
- Docs: update `docs/reference/api-surface.md` (new endpoints/fields) and
  `docs/reference/feature-catalog.md` when each part ships.
