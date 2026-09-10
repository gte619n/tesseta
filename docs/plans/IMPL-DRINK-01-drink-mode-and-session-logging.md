# IMPL-DRINK-01 — Drink Mode & Bounded Drink-Session Logging

**Status:** 🟢 Implemented (rev 3, 2026-09-10) — all phases code-complete across backend/web/android; unit + functional-automated gates green on all three layers (backend `./gradlew test`, android drink unit tests 13/13, web `build`+`lint`+`test`). **Remaining:** on-device functional checks F5–F18 (need an emulator/device + live backend — see §8) and human-visual image/layout confirmation. See the [decision log](IMPL-DRINK-01-decision-log.md) (IL-1..IL-8) for implementation choices to review.
**Branch:** `drink-logging`
**Author / PM:** Evan Ruff (interview) + engineering
**Created:** 2026-09-10
**Related:** [ADR-0011 describe-a-meal / saved-meal catalog](../decisions/ADR-0011-describe-a-meal-saved-meal-catalog.md) · [IMPL-16 nutrition UX](../specs/IMPL-16-med-reminders-and-nutrition-ux.md) · [IMPL-AND-20 offline-first sync](IMPL-AND-20-offline-first-sync.md)

---

## 1. Problem statement

Evan wants to log alcoholic drinks **while out**, with as little friction as possible, and see his nutrition totals move in real time.

The desired experience, in his words:

1. A **settings page in the user profile** where he can, with AI help, build a personal catalog of drinks. Each entry gets a **generated picture** and **saved nutrition information** (calories, sugar, and alcohol content).
2. A **"Drink Mode"** selectable from the nutrition page menu.
3. When Drink Mode is on, the **home screen shows a card pinned to the very top** that lets him log & track drinks with a **single tap**.
4. The card shows the **drinks in the current session** and **updates his nutrition information as it changes** (how many standard drinks, calories, and the dent in today's budget).

### The canonical scenario (the "acceptance story")

> **Setup (web, at home, sober):** Evan opens **Profile → Drinks**, types "Negroni", the AI proposes `~12% ABV, 90 ml, 210 kcal, 0 g sugar → 1.5 standard drinks`; he tweaks the volume, confirms, and a studio glassware image generates. He repeats for "Vodka Soda", "Guinness", "Malbec (6oz)". Four drinks now live in his catalog with pictures.
>
> **Friday night, at a bar, on his phone, patchy signal:**
> - He opens **Nutrition → ⋮ → Drink Mode**. A card appears at the top of the home screen. He taps **Start session** at 21:30 (Fri).
> - He taps the **Negroni** tile → instantly "Added — Undo" snackbar; card headline updates to **1.5 std drinks · 210 kcal**. The entry is written to **Friday's** nutrition (meal = DRINKS), even offline.
> - Two Guinness taps later → **3.5 std drinks · ~640 kcal**; secondary line shows added sugar/carbs and "−640 of today's budget".
> - He misclicks a Malbec → taps **Undo** in the snackbar → removed, tally rolls back.
> - It's now **00:40 Saturday**. He logs one more Vodka Soda. Because the session started Friday (**D6**), that drink is **back-dated to Friday's** nutrition day.
> - At 01:15 he taps **End session** → a one-time **summary** shows *"5 drinks · 6.5 std drinks · ~980 kcal · 3h 45m"*. The individual entries remain in Friday's nutrition day permanently; the session grouping is not stored for later browsing.
> - Next morning, back on wifi, every queued entry has synced; **Friday's** day view shows a **Drinks** section with the 5 entries and the alcohol calories folded into the daily total.

---

## 2. Design decisions (locked via interview 2026-09-10)

| # | Decision | Choice |
|---|----------|--------|
| **D1** | Drink data model | **Reuse `CatalogFood`** with `category = DRINK`. A drink is a catalog food; logging a drink is a normal nutrition entry. No new synced catalog collection. |
| **D2** | Session concept | **Explicit, bounded `DrinkSession`** ("a night out") that **may cross midnight**. |
| **D3** | Alcohol modeling | **Add alcohol grams + standard-drink count** to the model (new `AlcoholInfo`). Alcohol calories (7 kcal/g) fold into `caloriesKcal`. |
| **D4** | Platforms | **Android = logging** (Drink Mode + card, the "out" case). **Web = AI setup** (catalog management). No web logging card this round. |
| **D5** | Session lifecycle | **Explicit Start / End buttons on the card.** Ending shows a summary. |
| **D6** | Midnight attribution | **All drinks attribute to the session-start day.** A 01:00 drink in a Friday-night session lands on Friday's nutrition day (entries are back-dated). |
| **D7** | Drink Mode ↔ session | **Drink Mode is a device-local toggle** (from the nutrition menu) that controls whether the card is present. The **session lives inside the mode** and is also **local-only** (not synced). Turning Drink Mode off ends any active session. The only synced artifacts are the individual drink nutrition entries. |
| **D8** | Meal bucket | **New `DRINKS` meal type**, added across backend/web/android/sync. The day view surfaces a distinct **Drinks** section **only when drink entries exist** (does not clutter non-drinkers' day view). |
| **D9** | Setup entry mode | **Add drinks one at a time**, AI-assisted (type a name → AI enriches → review → save). |
| **D10** | Alcohol math | **AI estimates ABV% + serving volume (ml).** Backend deterministically computes `alcoholGrams = ml × (ABV/100) × 0.789` and `standardDrinks = alcoholGrams / 14`. Auditable & editable. |
| **D11** | Save gate | **Review & edit the AI proposal, then confirm.** Only on confirm are the drink persisted and its image enqueued (no wasted image spend on misparsed drinks). |
| **D12** | Card contents | **Recents first, then a searchable full list.** Recently logged (this/last session) as one-tap tiles; a search/expand reaches the whole catalog. |
| **D13** | Tap & undo | **Instant log + brief "Added — Undo" snackbar** (~4s). Re-tapping the same tile adds another of that drink. |
| **D14** | Live display | **Standard drinks + calories are the headline;** a secondary line shows added sugar/carbs and the dent in today's calorie budget. |
| **D15** | End of night | **Summary on End** (total drinks, standard drinks, calories, duration). Entries persist in nutrition permanently. **No browsable session history** is stored. |
| **D16** | Offline | **Fully offline.** Taps update the local session tally instantly; entries queue on the existing Room + WorkManager nutrition op rail; images may lag. |
| **D17** | Scope of "drinks" | **Strictly alcoholic drinks only.** Alcohol fields are required for a `DRINK`. Non-alcoholic beverages stay in normal food logging. |
| **D18** | Imagery | **New "beverage" Gemini style** — correct glassware (rocks/coupe/pint/wine), garnish, moody bar-top, same studio lighting. New prompt template. |
| **D19** | Guardrails | **No responsible-drinking guidance.** Numbers only; no thresholds, warnings, or nagging. |
| **D20** | Catalog CRUD | **Full CRUD on web:** add, edit any field (name, ABV, volume, macros), **regenerate image** (reuse existing food-image regenerate endpoint pattern), and **delete/archive** (soft, to preserve historical entry references). |
| **D21** | Stale session (locked 2026-09-10, rev 2) | **No nudge, no auto-close.** The always-visible card *is* the reminder: while a session is open the card is always on the home screen and prominently shows the session **start day + time** ("Started Fri 9:30 PM"). Additionally, the **End flow lets the user edit the close time** (defaults to now) — this affects only the summary duration, since entries are already dated and no session history is stored. |
| **D22** | Pour-size variation (locked 2026-09-10, rev 2) | **Long-press opens a size multiplier** (×0.5 / ×1 / ×1.5 / ×2). Plain tap = default serving, still single-tap. The multiplier maps to the existing entry `quantity` field; frozen macros **and** the `AlcoholInfo` snapshot scale linearly. |

### Reconciliation note (D5 + D7)

"Drink Mode is the session" (D7) and "explicit Start/End buttons" (D5) are layered, not contradictory:

- **Drink Mode** (nutrition menu, device-local pref) = *is the card present on the home screen at all?*
- **Session** (Start/End on the card) = *is a night actively being tracked?*
- Drink Mode ON + no active session → the card renders a **"Start a session"** empty state.
- Tapping **End session** shows the summary and returns the card to the empty state (Drink Mode stays on).
- Turning **Drink Mode OFF** while a session is active **ends the session first** (silent end, no summary), then removes the card.
- **Invariant (D21): an open session always has a visible card.** Because disabling Drink Mode ends the session, a session can never outlive the card. The active card always displays its start day + time ("Started Fri 9:30 PM"), so a forgotten session is impossible to overlook — this replaces any nudge/auto-close mechanism.

Neither the mode flag nor the session is synced (**D7**); both live in Android local storage. The drink **entries** are ordinary nutrition entries and sync via the normal delta/op-rail.

---

## 3. Current state (grounded in code — read before implementing)

These are the existing pieces this feature reuses or extends. Paths are relative to repo root.

### 3.1 Nutrition / catalog (backend)

| Concern | File | Note |
|---------|------|------|
| Catalog food record (drinks reuse this) | `backend/src/main/java/com/gte619n/healthfitness/core/nutrition/CatalogFood.java` | **Verified fields (rev 2):** already has `createdBy` (ownership solved — "my drinks" = `createdBy == userId`), `category` is a **lowercase free string** (`"ingredient"`, `"product"` drive image-template dispatch → use `category = "drink"` to match), and there is **no archive field** — add nullable `archivedAt` (D20 soft-delete). Add nullable `AlcoholInfo`. `imageStatus` lifecycle NONE→PENDING→READY/FAILED already exists. |
| Macros record (needs alcohol-calorie handling) | `.../core/nutrition/Macros.java` | Has `caloriesKcal, protein, carbs, fat, fiber, sugar`. **No alcohol field.** Alcohol calories fold into `caloriesKcal`; grams live on `AlcoholInfo`. |
| Serving size | `.../core/nutrition/ServingSize.java` | A drink's serving = its glass; `servingVolumeMl` captured on `AlcoholInfo`. |
| Logged entry (frozen snapshot) | `.../core/nutrition/FoodEntry.java` | Add optional frozen `AlcoholInfo` snapshot so session tally sums std drinks/grams; `meal` must accept `DRINKS`. |
| Meal type enum | `.../core/nutrition/*` `MealType` (BREAKFAST/LUNCH/DINNER/SNACK) | **Add `DRINKS`.** |
| Daily rollup | `.../core/nutrition/NutritionDailyLog.java` | Server-computed; alcohol calories flow through `caloriesKcal` automatically once entries carry them. |
| Catalog service | `.../core/nutrition/FoodCatalogService.java` | `create/find/search/confirm/regenerateImage/healReferencedImages`. Reuse for drink CRUD + image regen. |
| Food image orchestration | `.../core/nutrition/FoodImageService.java` | `enqueueGeneration`, `sweepStalePending` (3-min self-heal). Reuse as-is for drink images. |
| Gemini image generator | `.../integrations/nutrition/GeminiFoodImageGenerator.java` | Model `gemini-3.1-flash-image-preview`; plated/raw/packaged templates. **Add "beverage" template (D18).** |
| Description → structured analyzer | `.../integrations/nutrition/MealDescriptionExtractor.java` (impl of `MealDescriptionAnalyzer` port) | Tool-calling Gemini flash. **Add/adapt a drink analyzer** returning `{name, abvPercent, servingVolumeMl, macros}` (D10). |
| Durable job queue | `.../core/nutrition/jobs/*` (`NutritionJob`, `NutritionJobType`, `NutritionJobDispatcher`) + `CloudTasksNutritionJobQueue` | Reuse `FOOD_IMAGE` job type for drink images (drinks are catalog foods). No new job type needed. |
| Food/catalog API | `.../api/nutrition/FoodController.java`, `NutritionController.java`, `MealDescriptionController.java` | Add drink endpoints (may live under a `DrinkController` or extend `FoodController` with `category=DRINK`). |
| Add-entry + idempotency | `NutritionController` `POST /api/me/nutrition/{date}/entries` + `SyncWriteContext.idempotentCreate` | Client-minted `id` + `Idempotency-Key`. Drink logging reuses this verbatim (entry `date` = session-start day, `meal = DRINKS`). |

### 3.2 Catalog availability on Android — local-first cache, **NOT** sync (corrected rev 2)

> ⚠️ **Rev-1 of this spec was wrong here.** `foodCatalog` is **not** a synced mirror collection: it is absent from `FirestoreSyncChangeReader.TOP_LEVEL` and from `CollectionRegistry`. The sync reader only *joins* catalog food data into **entry** payloads at read time (`FirestoreSyncChangeReader.java:230,269`). Android's catalog access is REST + a lazily-warmed local cache.

| Concern | File | Note |
|---------|------|------|
| Local catalog cache (the real offline mechanism) | `android/core-data/.../data/nutrition/FoodRepository.kt` + `catalog_cache` Room table + `CatalogCacheDao.search()` | Added by **#219 (local-first food search)**: `catalog_cache` is name-searchable (nameLower/brandLower, ranked search), warmed on every cache write, with `warmFromIds()` best-effort prefetch. **Drinks must be *proactively* warmed** (not lazily — you can't lazily fetch at a bar with no signal): fetch "my drinks" (`GET /api/me/drinks`) and write them into the cache on Drink Mode enable, session start, and app foreground. Room is at **v7** (v6→v7 additive migration in #219); a further additive migration may be needed for alcohol columns or a `type="drink"` tag. |
| Recents source (D12) | `nutritionEntries` mirror (synced) — pattern: `cachedRecentMeals` in #219 | **Recents = most-recent `meal=DRINKS` entries from the synced mirror**, exactly like the add-food sheet's recents. No separate "last session" persistence needed; survives app restart and reinstall+resync. |
| Backend sync emission | `backend/.../persistence/sync/FirestoreSyncChangeReader.java` | **No new collection, no registry/alias changes** (D1/D7 — drinks are catalog foods, sessions are local). The slash-form-alias trap does not apply. Drink **entries** sync as ordinary `nutritionDays/entries`; verify the entry payload's joined food data includes the new alcohol fields so mirror-derived recents can render std-drink counts. |
| Nutrition op rail (offline) | `android/core-data/.../data/nutrition/{NutritionOpStore,NutritionOpWorker,NutritionOpPayloads}.kt` + `db/entity/PendingNutritionOpEntity.kt` | Drink logging reuses the standard add-entry op with `meal=DRINKS` and back-dated `date=sessionDay`. **No new op type.** |

### 3.3 UI — settings / profile

| Concern | File | Note |
|---------|------|------|
| Web profile page (add "Drinks" link) | `web/app/me/profile/page.tsx` | Sectioned card layout; add a link/section to a new `/me/drinks` sub-page. |
| Web settings sub-page pattern | `web/app/me/*/page.tsx` (e.g. `nutrition`, `meds`) | Next.js App Router; server component + server actions. |
| Web nutrition API client / types | `web/lib/nutrition-api.ts`, `web/lib/types/nutrition.ts` | Add drink CRUD calls + `AlcoholInfo`/`DRINK` types. |

### 3.4 UI — Android nutrition + home

| Concern | File | Note |
|---------|------|------|
| Nutrition today screen + menu (add "Drink Mode") | `android/feature-nutrition/.../NutritionTodayScreen.kt`, `NutritionTodayViewModel.kt` | The `⋮` menu hosts the Drink Mode toggle. Day view gains a conditional **Drinks** section (D8). |
| Add-food / describe flow (pattern to mirror for drink add) | `android/feature-nutrition/.../AddFoodSheet.kt`, `AddFoodViewModel.kt` | Reference for AI-assisted add + serving UI. |
| Phone dashboard (pin card to top) | `android/app/.../mobile/dashboard/PhoneTodayScreen.kt` | Insert the Drink card **above** the vitals grid when Drink Mode is on. |
| Foldable dashboard (pin card to top) | `android/app/.../mobile/dashboard/FoldableDashboardScreen.kt` | Same, above the vitals row. |
| Card pattern to mirror | `TodaysDosesCard` (referenced from dashboards) | HfCard + title + action + list; good structural template for the Drink card. |
| Local preference pattern (Drink Mode + session) | `android/core-data/.../data/prefs/UnitPreferencesRepository.kt` | `preferencesDataStore` pattern for the device-local Drink Mode flag + active-session state. |

### 3.5 Distinction reminders

- **"drink" here = a catalog `CatalogFood` with `category="drink"`** (lowercase string, matching the `"ingredient"`/`"product"` convention that drives image-template dispatch). Per D1, drinks are ordinary catalog foods and *may* surface in normal food search; the Drink card filters to `category="drink" AND createdBy == me AND archivedAt == null`.
- The **session is not a synced entity** (D7). Do not add a `drinkSessions` sync collection. If a future spec wants cross-device session history (D15 says no), that is out of scope here.
- Alcohol **grams** live on `AlcoholInfo`; alcohol **calories** are folded into `Macros.caloriesKcal`. Do not add an alcohol field to `Macros`.

---

## 4. Target behavior specification

### 4.1 Standard-drink math (pure, backend — the single source of truth)

Given AI (or user) inputs `abvPercent` and `servingVolumeMl`:

```
pureEthanolMl = servingVolumeMl × (abvPercent / 100)
alcoholGrams  = pureEthanolMl × 0.789            // ethanol density g/ml
standardDrinks = alcoholGrams / 14.0             // US standard drink = 14 g
alcoholKcal   = alcoholGrams × 7.0               // folded into caloriesKcal
```

- Rounding: keep `alcoholGrams` and `standardDrinks` at 1 decimal for display; store full precision.
- **Multiplier scaling (D22):** a logged entry with quantity `q` (long-press ×0.5/×1/×1.5/×2) freezes `macros × q` and `AlcoholInfo` with `servingVolumeMl × q`, `alcoholGrams × q`, `standardDrinks × q` (ABV unchanged). This reuses the existing entry `quantity` semantics.
- `AlcoholInfo = { abvPercent, servingVolumeMl, alcoholGrams, standardDrinks }`. Required (non-null) whenever `category="drink"` (**D17**). Validation rejects a drink with missing/zero ABV or volume.
- `caloriesKcal` for a drink = `carbKcal + fatKcal + proteinKcal + alcoholKcal` (AI may already return total calories; if so, reconcile: prefer explicit macro-derived + alcohol; surface a warning in review if the AI total diverges > 15%).

### 4.2 Drink catalog CRUD (web, D9/D11/D20)

- `POST /api/me/drinks/analyze` → body `{name}` → returns a **proposal** `{name, abvPercent, servingVolumeMl, macros, standardDrinks, alcoholGrams}` (not persisted). **AI failure path:** analyzer errors surface as **422** with a user-readable message (mirror `MealDescriptionController`); the web form shows "couldn't analyze — try rewording or fill in manually" and always allows fully-manual entry of the same fields.
- User edits any field in a review row; on **confirm**:
  - `POST /api/me/drinks` (or `FoodController` with `category="drink"`) → creates `CatalogFood` (`createdBy = userId`), enqueues `FOOD_IMAGE` job with the **beverage** style, returns the drink with `imageStatus=PENDING`.
- `PUT /api/me/drinks/{id}` → edit fields (re-derives alcohol math server-side).
- `POST /api/me/drinks/{id}/image/regenerate` → 202, re-enqueue image. Also the recovery for `imageStatus=FAILED`.
- `DELETE /api/me/drinks/{id}` → **archive** (sets `archivedAt`; historical entries keep their frozen snapshot; archived drinks drop out of the card and drink list but the doc remains).
- `GET /api/me/drinks` → list my non-archived drinks (`createdBy == me`, `category="drink"`); this is also the endpoint Android uses to warm the offline cache (§3.2).
- Web polls for `imageStatus` transition (reuse `PendingImageRefresher` pattern).

### 4.3 Drink Mode + session (Android, local — D5/D6/D7/D13/D14/D15)

State (device-local, DataStore/Room):

```
DrinkMode = { enabled: Boolean }
DrinkSession = {
  active: Boolean,
  startedAtMillis: Long,
  sessionDay: LocalDate,          // == start day; ALL entries dated here (D6)
  loggedDrinkIds: List<LoggedDrink>   // { entryId, drinkId, name, std, kcal, sugar, carbs, at }
}
```

Behavior:

- **Enable Drink Mode** (nutrition ⋮ menu) → card renders at top of home; empty "Start a session" state. On enable (and on session start / app foreground), **warm the offline drink cache** from `GET /api/me/drinks` (§3.2) so tiles work with zero signal later.
- **Start session** → capture `startedAtMillis`, `sessionDay = today (device-local date at start)`. The active card **always shows the start day + time** ("Started Fri 9:30 PM") — this is the D21 stale-session reminder. Session state persists across process death and reboot (DataStore/Room).
- **Tap a drink tile** → immediately:
  1. Append to `loggedDrinkIds` (local, instant).
  2. Enqueue an add-entry op (op rail): `date = sessionDay`, `meal = DRINKS`, client-minted `entryId`, frozen macros + `AlcoholInfo` snapshot.
  3. Show **"Added — Undo"** snackbar (~4s). **Undo** → cancel the op if not yet sent / issue a delete, and pop the local entry.
  4. Update headline tally: `Σ standardDrinks`, `Σ caloriesKcal`; secondary: `Σ sugar/carbs` + "− kcal of today's budget".
- **Re-tap same tile** → another discrete entry (D13). **Long-press a tile (D22)** → inline ×0.5/×1/×1.5/×2 picker; selecting logs one entry at that quantity with linearly scaled macros + alcohol snapshot (§4.1).
- **End session (D21)** → End flow shows an **editable close time** (default = now; must be ≥ startedAt); compute summary `{count, Σstd, Σkcal, duration = closeTime − startedAt}`; show once; clear session; card returns to empty state. Entries stay in nutrition (close time affects only the displayed duration — nothing else is persisted).
- **Disable Drink Mode** with active session → silent end (no summary) + remove card.
- **Card contents (D12):** top row = recent drinks derived from the **synced `nutritionEntries` mirror** (most-recent `meal=DRINKS` entries — same pattern as the add-food sheet's `cachedRecentMeals`); a search field/expand lists the full drink catalog from the warmed local cache (`category="drink"`, `createdBy == me`, non-archived). Tiles whose image is `PENDING`/`FAILED` render a glassware placeholder — logging never depends on the image.
- **Offline (D16):** steps 1, 3, 4 are pure local; step 2 queues durably and reconciles later; images may be `PENDING` until online. Preconditions: the drink cache was warmed while online (§3.2) — tiles, tally, and search all work signal-free after that.
- **Budget line degrade:** if no nutrition target is set, the secondary line omits the "− kcal of today's budget" fragment and shows only Σ sugar/carbs.

### 4.4 Day-view integration (Android + web, D8)

- Day view renders a **Drinks** meal section **iff** the day has ≥1 `meal=DRINKS` entry.
- Daily totals already include the entries' calories (alcohol folded in). No separate alcohol line in the daily summary this round (D19 — numbers only, no special treatment).

---

## 5. Phases, status & tracking

**Status legend:** ⬜ not started · 🟡 in progress · 🟢 done · 🔵 blocked
**Columns:** *Impl* = code written · *Unit* = unit/integration tests green · *Func* = functional/acceptance check passed (see §6) · *Pushed* = merged/pushed to branch.

> The agent MUST NOT mark a row 🟢 in a column until the corresponding gate in §6/§7 is satisfied and the evidence (command output / screenshot / test name) is linked in §8.

### Phase 0 — Data model & backend foundations

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 0.1 | Add `DRINKS` to `MealType` (backend enum + serialization) — fixed exhaustive switch in `RecentActivityService` | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.2 | `AlcoholInfo` record on `CatalogFood` (NOT entry — IL-2) + `archivedAt` (D20); 14 ctor sites + Firestore ser/de | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.3 | `DrinkMath` util (§4.1) + validation (drink requires ABV & volume) — `DrinkMathTest` (7), `DrinkCatalogServiceTest` | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.4 | Alcohol calories fold into `caloriesKcal` (IL-3); serving-calories test green; daily rollup sums over entries | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.5 | "Beverage" prompt template in `GeminiFoodImageGenerator` (category `"drink"`, glassware by type) | 🟢 | n/a | 🔵 | ⬜ |
| 0.6 | Drink analyzer `DrinkAnalyzer`/`DrinkExtractor`: `{name}` → `{abvPercent, servingVolumeMl, macros}` | 🟢 | 🔵 | 🔵 | ⬜ |
| 0.7 | Drinks are NOT a synced collection (§3.2); per-100 scaling reproduces serving & scales ×q (D22) — tested | 🟢 | 🟢 | 🟢 | ⬜ |

_0.5 image quality & 0.6 live-AI behavior are human-visual / live-Gemini (🔵) — need a running backend with a key._

### Phase 1 — Web drink catalog (setup, D4/D9/D11/D20)

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 1.1 | `POST /api/me/drinks/analyze` (proposal, not persisted; 422 → manual entry) | 🟢 | 🔵 | 🔵 | ⬜ |
| 1.2 | `POST/PUT/DELETE /api/me/drinks` + `image/regenerate` (CRUD, soft-delete) — `DrinkController` | 🟢 | 🟢 | 🔵 | ⬜ |
| 1.3 | `/me/drinks` page: add-one flow → review/edit form → confirm → save + enqueue image | 🟢 | n/a | 🔵 | ⬜ |
| 1.4 | Edit / regenerate-image / archive UI; pending-image polling (reused `PendingImageRefresher`) | 🟢 | n/a | 🔵 | ⬜ |
| 1.5 | Link from `/me/profile` (Drinks section) | 🟢 | n/a | 🔵 | ⬜ |
| 1.6 | Web types + api client (`AlcoholInfo`, `Drink`, `DrinkProposal`) + `drinks-api.ts` | 🟢 | n/a | 🟢 | ⬜ |

_Web verified by `pnpm build` (typecheck) + `lint` + existing `test` (58/58). Live-AI + visual flows are 🔵 (need running backend)._

### Phase 2 — Android drink catalog (read + offline cache, D1/D16 — corrected rev 2)

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 2.1 | Android domain: `Food.alcohol` + `AlcoholInfo` (parse from drinks API / cache payload) | 🟢 | 🟢 | 🔵 | ⬜ |
| 2.2 | `DrinkApi` (`GET /api/me/drinks`) + `DrinkRepository` proactive `catalog_cache` warm (type="drink", NO migration — alcohol rides opaque JSON, IL note) | 🟢 | 🟢 | 🔵 | ⬜ |
| 2.3 | Drinks appear after a warm and remain listable/searchable fully offline | 🟢 | 🔵 | 🔵 | ⬜ |
| 2.4 | Recents from `nutritionEntries` mirror (`meal=DRINKS`) — `cachedRecentDrinks()` | 🟢 | 🟢 | 🔵 | ⬜ |

_2.2 note: subagent avoided the Room v7→v8 migration by storing alcohol facts inside the existing opaque cache JSON — lighter than the spec's anticipated column add, preserves the op queue. Logged as an implementation nuance to review._

### Phase 3 — Android Drink Mode + session + card (log, D5–D7, D12–D16)

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 3.1 | Device-local Drink Mode flag (`DrinkModeStore` DataStore) + nutrition ⋮ menu toggle | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.2 | Local `DrinkSession` store: start/end, sessionDay, tally; survives death/reboot; card shows start day+time (D21) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.3 | `DrinkCard` pinned top (phone + foldable), gated on Drink Mode | 🟢 | n/a | 🔵 | ⬜ |
| 3.4 | Card: recents tiles + searchable full list (D12) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.5 | One-tap log → op-rail add-entry (`date=sessionDay`, `meal=DRINKS`) + Undo snackbar (D13) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.6 | Live headline (std drinks + kcal) + secondary line (sugar/carbs, budget dent) (D14) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.7 | End-session flow with **editable close time** (≥ start) → summary; disable-mode silent-end (D5/D7/D15/D21) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.8 | Offline correctness: instant local tally, durable queue, later reconcile (D16) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.9 | Long-press size multiplier (×0.5/×1/×1.5/×2) → quantity-scaled entry (macros + std) (D22) | 🟢 | 🟢 | 🔵 | ⬜ |

_All Phase-3 logic verified by `DrinkTallyTest` (4) + `DrinkSessionTest` (9): tally sums, undo, ×q scaling, back-dating, secondary-line/budget, duration/std formatting. Func 🔵 = on-device interaction (F6–F13, F16, F17)._

### Phase 4 — Day-view integration (D8)

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 4.1 | Android day view: conditional **Drinks** section (`assembleDay` appends DRINKS group only when non-empty; avoids enum-drop) | 🟢 | 🟢 | 🔵 | ⬜ |
| 4.2 | Web day view: same conditional Drinks section (`buildDay` appends DRINKS; `MEALS` excludes it) | 🟢 | 🟢 | 🔵 | ⬜ |
| 4.3 | Daily totals include alcohol calories — both sum over ALL entries incl. DRINKS (verified by code review) | 🟢 | 🟢 | 🔵 | ⬜ |

### Phase 5 — Hardening, QA & sign-off

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 5.1 | Cross-midnight back-dating (D6): `planDrinkLog` dates to `sessionDay` — unit-tested; end-to-end 🔵 | 🟢 | 🟢 | 🔵 | ⬜ |
| 5.2 | Idempotency/replay: reuses client-minted-id add-entry rail; Undo delete path — backend idempotency tested; device replay 🔵 | 🟢 | 🟢 | 🔵 | ⬜ |
| 5.3 | Image cost guard: images enqueue only on `createDrink` (confirm), D11; regen is explicit endpoint | 🟢 | 🟢 | 🔵 | ⬜ |
| 5.4 | Full canonical scenario (§1) walkthrough on device | 🟢 | n/a | 🔵 | ⬜ |
| 5.5 | Rollout ordering: backend (`DRINKS` enum) deploys before/with clients; unknown-meal tolerance | 🟢 | ⬜ | 🔵 | ⬜ |
| 5.6 | Decision-log review + docs updated; branch pushed & PR opened | 🟡 | n/a | ⬜ | ⬜ |

_5.4 = the full acceptance story; all its constituent logic is unit-covered, but the end-to-end run needs a device + live backend (🔵). 5.6 pending human review of IL-1..IL-8 + your go-ahead to commit/push._

---

## 6. Testing approach

Two layers, both required. A task is not "tested" until **both** its technical and functional gates pass.

### 6.1 Technical (automated) tests

| Area | What to test | Where |
|------|--------------|-------|
| Standard-drink math (§4.1) | grams/std/kcal for known cases (12% × 90 ml → 8.5 g → 0.6 std; 40% × 44 ml shot → 13.9 g → ~1.0 std; 5% × 355 ml pint → 14.0 g → 1.0 std) | backend unit |
| Validation | `DRINK` with null/zero ABV or volume → 400 | backend unit |
| Alcohol calories | entry `caloriesKcal` includes `alcoholGrams × 7`; daily rollup sums correctly | backend unit/integration |
| MealType `DRINKS` | serialization round-trip backend↔web↔android; unknown-enum tolerance | backend + android + web unit |
| Entry payload | `nutritionDays/entries` delta + day-view join carry the frozen `AlcoholInfo` and `meal=DRINKS`; Android parses without dropping the row | backend + android unit |
| Offline cache warm | after a warm, drink list/search served entirely from `catalog_cache` with network unavailable; un-warmed cache degrades to empty-with-message, never a crash | android unit/integration |
| Multiplier scaling (D22) | quantity 1.5 × (12% / 90 ml) drink → volume 135 ml, grams ×1.5, std ×1.5, kcal ×1.5; ×0.5 likewise | android + backend unit |
| Archive filter | archived drink excluded from `GET /api/me/drinks`, card list, and cache warm; its historical entries unchanged | backend + android unit |
| Editable close time (D21) | summary duration = closeTime − startedAt; closeTime < startedAt rejected | android unit |
| Analyze failure | analyzer throw → 422 + message; manual-entry fallback still saves a valid drink | backend unit + web test |
| Drink analyzer | mocked Gemini → proposal shape; divergence warning when AI total vs macro-derived > 15% | backend unit (mock) |
| Session tally | pure reducer: N taps → correct Σstd/Σkcal; Undo decrements; re-tap increments | android unit |
| Session-day dating | drink logged at 01:00 with `sessionDay=Fri` produces entry dated Fri | android unit + backend integration |
| Offline op rail | queued add-entry replays exactly once; Undo before send cancels; after send deletes | android unit/integration |
| Idempotency | replayed add-entry with same client `id` + Idempotency-Key → one entry | backend integration |

**Commands (record exact output in §8):**

- Backend: `./gradlew :backend:test` (or the module's test task) — filter e.g. `--tests '*Drink*' --tests '*Alcohol*' --tests '*MealType*'`.
- Android: `./gradlew :core-data:testDebugUnitTest :feature-nutrition:testDebugUnitTest :core-domain:testDebugUnitTest` (respect the worktree `local.properties` gotcha — see [android-build-env memory]).
- Web: `cd web && npm run test` (and `npm run lint`, `npm run build` for type safety).

### 6.2 Functional (behavioral) tests — the demarcations for "done"

Each is a scripted, observable outcome. The agent reproduces it and records evidence (screenshot / log line / DB state).

| ID | Functional check | Pass criterion |
|----|------------------|----------------|
| **F1** | Web: add "Negroni" via AI | Proposal shows non-zero ABV & volume; after confirm, drink persists with `imageStatus` reaching `READY` (image visible). |
| **F2** | Web: edit ABV then save | `standardDrinks`/`alcoholGrams` recompute server-side to match §4.1. |
| **F3** | Web: regenerate image | New `imageUrl`; status cycles PENDING→READY. |
| **F4** | Web: archive drink | Drink disappears from catalog list; existing logged entries unaffected. |
| **F5** | Android: drink created on web appears | After a cache warm (Drink Mode enable / app foreground while online), the drink is selectable on the card — **then remains selectable in airplane mode**. |
| **F6** | Android: enable Drink Mode | Card appears pinned **above vitals** on phone **and** foldable. |
| **F7** | Android: one-tap log | Headline updates instantly; "Added — Undo" shows; a `DRINKS` entry exists for `sessionDay`. |
| **F8** | Android: Undo | Tally rolls back; no entry remains (or entry deleted if already sent). |
| **F9** | Android: re-tap same drink | Two discrete entries; tally = 2×. |
| **F10** | Cross-midnight (D6) | Log at ≥00:00 after a session started previous evening → entry dated the **start** day; appears on that day's card. |
| **F11** | End session | End flow offers an editable close time (default now); summary shows correct count/std/kcal and duration = closeTime − start; entries remain in nutrition; card returns to empty state. |
| **F12** | Disable Drink Mode mid-session | Session ends silently (no summary); card removed; entries retained. |
| **F13** | Offline logging | Airplane mode: taps still update tally instantly; entries flagged pending; on reconnect they sync exactly once (no dupes). |
| **F14** | Day view | Drinks section appears only when drink entries exist; daily calorie total includes alcohol kcal. |
| **F15** | Full canonical scenario (§1) | Entire story reproduced end-to-end without manual DB edits. |
| **F16** | Long-press multiplier (D22) | Long-press a tile, pick ×2 → one entry at 2× macros/volume/grams/std; tally reflects 2×; plain tap still logs 1× instantly. |
| **F17** | Active-card reminder (D21) | With a session open, the card is present on the home screen showing "Started <day> <time>"; after force-killing the app and relaunching (even next day), session + card + start time survive intact. |
| **F18** | Image FAILED resilience | A drink with `imageStatus=FAILED` still renders a placeholder tile and logs normally; web regenerate recovers it to READY. |

**Note on non-automatable checks:** F1/F3 image *quality* (glassware correctness) and F6 layout are **human-visual** — the agent captures a screenshot and flags for human confirmation rather than self-certifying aesthetics. F5/F13 require a real device or emulator + backend; if unavailable in-agent, mark 🔵 blocked with the reason, do **not** mark 🟢.

---

## 7. Definition of Done

A task/phase is **Done** only when ALL hold:

1. **Implemented** — code written, compiles, no TODO stubs on the happy path.
2. **Unit** — relevant §6.1 tests exist and pass; command output captured in §8.
3. **Functional** — the mapped §6.2 F-check(s) pass with evidence (screenshot/log/DB row).
4. **No regressions** — existing backend/android/web test suites still green (nutrition sync especially).
5. **Pushed** — committed to `drink-logging`, pushed; for the final phase, PR opened and deploy gates considered (Trivy/CI — see [deploy-pipeline-observability memory]).

**Feature-level DoD:** Phases 0–5 all-green, F1–F15 all pass (human-visual items confirmed by Evan), decision log reviewed, and the canonical scenario (§1) demonstrated on a device.

### Agent self-verification protocol (before flipping any 🟢)

The agent MUST, per task:

1. State which F-check(s) and unit test(s) gate this task.
2. Run the exact commands (§6.1) and paste the tail of the output into §8.
3. For functional checks, produce the observable artifact (screenshot path, log excerpt, or the Firestore/Room row) — not a claim that it "should work."
4. If a check cannot be executed in the current environment (no device, no Gemini key, no backend), mark the column 🔵 **blocked** with the specific missing dependency. **Never** infer 🟢 from code inspection alone.
5. Only then edit the status cell. Update the top-of-file **Status** line when a whole phase closes.

---

## 8. Verification log (append-only evidence)

> Fill as work proceeds. One entry per verified task: date, task #, command/steps, result, evidence path.

| Date | Task | Gate | Command / step | Result | Evidence |
|------|------|------|----------------|--------|----------|
| 2026-09-10 | Backend all | Unit/compile | `./gradlew test` (full suite) | ✅ BUILD SUCCESSFUL (exit 0) | `/tmp/backend_fulltest.log` |
| 2026-09-10 | 0.3/0.4 | Std-drink math | `./gradlew test --tests '*DrinkMath*'` | ✅ 7/7 | `DrinkMathTest.xml` |
| 2026-09-10 | 0.2/0.7/1.2 | Drink create/scale/archive | `./gradlew test --tests '*DrinkCatalog*'` | ✅ pass (per-100 scaling, ×q linear, validation, archive filter) | `DrinkCatalogServiceTest.xml` |
| 2026-09-10 | Phase 3 | Session tally reducer | `:core-domain:testDebugUnitTest --tests '*Drink*' --rerun-tasks` | ✅ `DrinkTallyTest` 4/4 | `core-domain/.../DrinkTallyTest.xml` |
| 2026-09-10 | 3.5/3.9/5.1 | Log/undo/multiplier/back-date | `:feature-nutrition:testDebugUnitTest --tests '*Drink*' --rerun-tasks` | ✅ `DrinkSessionTest` 9/9 | `feature-nutrition/.../DrinkSessionTest.xml` |
| 2026-09-10 | Android all | Compile | `:core-data:compileDebugKotlin :app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL (exit 0) | `/tmp/android_verify.log` |
| 2026-09-10 | 4.1 | DRINKS not dropped | Code review `NutritionRepository.assembleDay` (totals sum all entries; DRINKS group appended when non-empty) | ✅ | `NutritionRepository.kt:681-714` |
| 2026-09-10 | Web all | Build/typecheck | `pnpm run build` + `lint` + `test` | ✅ compiled; `/me/drinks` route emitted; 58/58 tests | `/tmp/web_verify.log` |
| 2026-09-10 | 4.2 | DRINKS conditional (web) | Code review `buildDay` (DRINKS not in `MEALS`; appended when entries>0) | ✅ | `web/app/me/nutrition/page.tsx:71-89` |
| 2026-09-10 | IL-13 | Interview refinements | backend `*FoodCatalogSearch*` (+2 tests) + android `DrinkSessionTest` (+4 reconcile) rebuilt | ✅ backend green; android DrinkSessionTest 13/13, DrinkTally 4/4; web build clean | `/tmp/*_verify2.log` |
| _pending_ | F5–F18 | On-device functional | emulator/device + live backend | 🔵 blocked (no device in-agent) | — |

---

## 9. Open items / deferred (explicitly out of scope this round)

- **Browsable session history** (D15 = no). Entries live in nutrition; the session grouping is not persisted for later.
- **Cross-device session** (D7 = local-only). Drink Mode + active session do not sync; switching devices mid-night is not supported.
- **Web logging card** (D4). Web is setup-only this round.
- **Non-alcoholic drinks** (D17). Stay in normal food logging.
- **Responsible-drinking guidance / limits** (D19). None.
- **Reference-photo-steered drink images** (considered, not chosen). Beverage template is text-prompt only for now.
- **Standard-drink target/goal.** Not this round (would mirror nutrition targets if ever wanted).
- **Logging a drink that isn't in the catalog from the card** (someone hands you a mystery shot). Fallback = normal add-food/describe flow; the card is catalog-only.
- **Stale-session nudges / auto-close** (D21 = rejected). The always-visible card with its start timestamp is the whole mechanism.
- **Time-zone travel mid-session.** `sessionDay` is the device-local date at start; a session spanning a TZ change keeps its original day. Accepted as-is.
