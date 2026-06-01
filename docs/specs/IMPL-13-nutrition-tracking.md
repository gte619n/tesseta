# IMPL-13: Nutrition Tracking

## Goal of this spec

Turn the existing thin nutrition baseline (a per-day macro total) into a full
**macro tracking** feature:

- A **daily macro target** (calories, protein, carbs, fat, fiber, sugar) the
  user sets and tracks against.
- **Per-food logging** organized into meals (Breakfast / Lunch / Dinner /
  Snack) that roll up into the day total.
- A **shared, server-owned food catalog** seeded from open data so foods are
  reused across logs and users — the mechanism that keeps Gemini calls rare.
- On phone: **camera capture** — barcode scan, nutrition-label photo, and
  full-meal photo — that resolves to catalog foods, using Gemini only on a
  genuine miss.
- An **AI studio image** for each catalog food, generated once and reused, in
  the house photography style.

Built on both web (Next.js) and Android (Kotlin/Compose) over the shared Spring
backend, mirroring the Goals module ([IMPL-12](IMPL-12-goals-module.md)) as the
full-stack blueprint. The food-data licensing decision is recorded in
[ADR-0006](../decisions/ADR-0006-open-food-facts-licensing.md).

## Decisions locked

| Decision | Choice |
|---|---|
| First shippable milestone | **Web-first** — manual tracking + targets + catalog, no AI/photo |
| Nutrients tracked | calories, protein, carbs, fat, **fiber, sugar** |
| Day structure | Foods grouped into **meals** (B/L/D/Snack) that **roll up** to the day total |
| Portioning | **Simple portions** — pick a serving size + a quantity step (0.5× / 1× / 1.5× / 2×); macros computed from grams |
| Food catalog scope | **One shared/global catalog**, not per-user — this is what enables reuse |
| Trust model | Shared catalog + status: `UNVERIFIED → VERIFIED`; distinct-user confirmations promote (threshold = 1 today, raised later) |
| Packaged capture | Camera screen with a **Barcode ⇄ Photo toggle**; barcode → DB/Open Food Facts lookup, label photo → Gemini OCR fallback |
| Meal photo | Gemini **itemizes** the plate into components with portion guesses; user **reviews/edits/confirms** before save; each item can become a catalog food |
| Seed data | **USDA FoodData Central** (generics, CC0) + **Open Food Facts** (packaged, ODbL) — see ADR-0006 |
| Macro target vs Goals | **Both** — a standalone daily target for day-to-day tracking, plus new metric keys so the structured Goals system can bind to nutrition |
| Studio image timing | **Async, once per unique catalog food**; reuse forever; user's real photo fed as a visual reference when available |
| Image style | House studio style (white marble, soft upper-left light, shallow DoF, 100mm macro, no text/branding/hands), adapted for plated food |
| AI models | Extraction (meal/label/estimation) on `gemini-3.5-flash`; studio image on `gemini-3.1-flash-image-preview`. **No new model** (cf. ADR-0005) |

---

## Starting point — what already exists

`main` ships a minimal nutrition baseline that this spec extends, not replaces:

- `core/nutrition/NutritionDailyLog` — record of per-day
  `proteinGrams / carbsGrams / fatGrams / caloriesKcal` + timestamps.
- `core/nutrition/NutritionService` — saves the day log and publishes
  `MetricChangedEvent` for the registered nutrition metric keys.
- `api/nutrition/NutritionController` — `POST /api/me/nutrition`,
  `GET /api/me/nutrition?from=&to=`, `GET /api/me/nutrition/today`.
- `persistence/.../FirestoreNutritionDailyLogRepository` — collection
  `users/{userId}/nutritionDailyLogs/{yyyy-MM-dd}`, upsert.
- Goal metric keys already registered: `NUTRITION_PROTEIN_AVG_7D`,
  `NUTRITION_CARBS_AVG_7D`, `NUTRITION_FAT_AVG_7D`, `NUTRITION_CALORIES_AVG_7D`.

**The `NutritionDailyLog` becomes a cached rollup.** It keeps its shape (so the
existing `/today`, range endpoint, and the `nutrition.*Avg7d` goal metrics keep
working) and gains two nullable fields (`fiberGrams`, `sugarGrams`). Its values
are now **computed from the day's food entries** whenever those change, rather
than set directly. The legacy `POST /api/me/nutrition` quick-entry is retained
(see REST), modeled as a single manual entry so the rollup stays the one source
of truth.

---

## Concepts

**Macro target** — the user's daily goal: a value per tracked nutrient
(calories, protein, carbs, fat, fiber, sugar). One active target at a time,
with history.

**Catalog food** — a globally shared, reusable food definition: a name, optional
brand/barcode, macros per 100 g, serving sizes, a provenance `source`, a
validation `status`, and (eventually) a generated studio image. Foods come from
open-data seeding, barcode/label scans, meal-photo items, or manual creation.
**The catalog is the cache that keeps Gemini calls rare.**

**Food entry** — one logged food on a given day and meal: a reference to a
catalog food (or an ad-hoc entry), a chosen serving + quantity, and a
**snapshot** of the resulting macros. Entries are the source of truth for the
day total.

**Meal** — a grouping of entries within a day: `BREAKFAST | LUNCH | DINNER |
SNACK`. Meals roll up to the day; days roll up into the `nutrition.*Avg7d`
metrics that already feed Goals.

**Validation status** — `UNVERIFIED` (Gemini- or user-derived, unconfirmed) vs
`VERIFIED` (confirmed by enough distinct users). Today the threshold is 1, so
the act of confirming a food at logging time verifies it; the model scales to
multi-user later by raising the threshold.

---

## Data model

### Firestore collections

```
foodCatalog/{foodId}                                    ← GLOBAL, shared, not user-scoped
foodCatalog/{foodId}/confirmations/{userId}             ← one doc per distinct confirming user

users/{userId}/nutritionTargets/{targetId}              ← target history
users/{userId}/nutritionTargets/current  (pointer)      ← active target id

users/{userId}/nutritionDailyLogs/{yyyy-MM-dd}          ← EXISTING; now a cached rollup (+fiber,+sugar)
users/{userId}/nutritionDays/{yyyy-MM-dd}/entries/{entryId}   ← per-food log entries
```

The catalog is **top-level**, not under `users/{userId}` — that is the whole
point: one chicken-breast definition serves everyone. Everything user-specific
(targets, days, entries) stays multi-tenant under `users/{userId}`, matching
every other module.

### Catalog food document

```
foodId: string
name: string                     "Chicken breast, grilled"
nameLower: string                normalized, for prefix search
brand: string | null
barcode: string | null           GTIN/EAN/UPC — indexed; null for generics
category: string | null

basis: "PER_100G"
macrosPer100g: {
  caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams
}
servingSizes: [ { label: "1 cup", grams: 240 }, ... ]   default first
defaultServingIndex: int

source: enum  USDA | OPEN_FOOD_FACTS | USER | GEMINI_PHOTO | GEMINI_LABEL
sourceRef: string | null         USDA fdcId / OFF barcode
status: enum  UNVERIFIED | VERIFIED
confirmationCount: int           distinct confirming users (denormalized)
verifiedAt: timestamp | null

imageUrl: string | null          generated studio image
imageStatus: enum  NONE | PENDING | READY | FAILED

createdBy: userId
createdAt, updatedAt: timestamp
```

Per ADR-0006, `source` is load-bearing: it segregates Open Food Facts (ODbL)
rows from USDA (CC0) and proprietary rows so they stay separable, and it drives
attribution in the UI.

**Search.** v1 uses `nameLower` prefix queries (Firestore range query) for name
search and an equality query on `barcode` for scans. A dedicated search index
(e.g. Typesense/Algolia) is out of scope for v1 and noted as a later upgrade if
prefix search proves too blunt.

### Macro target document

```
targetId: string
caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams: number
effectiveFrom: date
createdAt, updatedAt: timestamp
```

`users/{userId}/nutritionTargets/current` holds the active `targetId`. History is
retained so past days can be evaluated against the target that was active then.

### Food entry document

```
entryId: string
date: date                        denormalized (= parent day)
meal: enum  BREAKFAST | LUNCH | DINNER | SNACK
foodId: string | null            catalog ref; null for a pure ad-hoc entry
foodName: string                 snapshot of the name at log time
servingLabel: string             snapshot, e.g. "1 cup"
servingGrams: number             snapshot
quantity: number                 0.5 | 1 | 1.5 | 2  (simple portions)
macros: {                        COMPUTED snapshot, frozen at log time
  caloriesKcal, proteinGrams, carbsGrams, fatGrams, fiberGrams, sugarGrams
}
photoRef: string | null          meal-photo storage ref, if photographed
source: enum  MANUAL | CATALOG | BARCODE | LABEL | PHOTO
createdAt, updatedAt: timestamp
```

Macros are **snapshotted** onto the entry so editing a catalog food later never
silently rewrites history.

`macros = macrosPer100g × (servingGrams × quantity) / 100`.

### Rollup rule

On any entry create / update / delete, `NutritionService` recomputes the parent
`nutritionDailyLogs/{date}` doc as the **sum of that day's entries** (now
including `fiberGrams`, `sugarGrams`) and saves it — which fires the existing
`MetricChangedEvent` for the nutrition metric keys, so Goals re-evaluation is
unchanged. The day log stays the single cached total; entries are its source.

---

## Backend — Spring

### Module placement

A new `nutrition` surface extending the existing package: domain + services in
`core/nutrition`, Firestore repos in `persistence/nutrition`, controllers in
`api/nutrition`, Gemini + image + open-data clients in `integrations/nutrition`.
Same layering as Goals.

New `core` records/repos: `MacroTarget` + repo, `FoodEntry` + repo,
`CatalogFood` + `FoodCatalogRepository`. `NutritionDailyLog` gains
`fiberGrams`, `sugarGrams` (nullable — non-breaking Firestore add).

### Food catalog service

`FoodCatalogService`:

- `search(query)` — name prefix search over `nameLower`.
- `getByBarcode(code)` — local equality lookup → on miss, Open Food Facts API
  (`/api/v2/product/{barcode}.json`) → map `nutriments` to a `CatalogFood`,
  persist tagged `source: OPEN_FOOD_FACTS`, return. Gemini is **not** in this
  path.
- `create(food)` — user/manual or AI-derived food; `status = UNVERIFIED`,
  enqueues studio-image generation.
- `confirm(foodId, userId)` — writes `confirmations/{userId}` (idempotent per
  user), recomputes `confirmationCount`; when it reaches the threshold
  (config `app.nutrition.verify-threshold`, default `1`), sets
  `status = VERIFIED`, `verifiedAt`. Logging a food with a confirm step is the
  v1 verification path; raising the threshold turns on true multi-user
  validation with no code change.

### Open-data seeding (Cloud Run Job)

A one-off / periodic **Cloud Run Job** (same image, alternate entrypoint, the
pattern IMPL-12 established for the daily eval job) imports open data into
`foodCatalog`:

- **USDA** Foundation + SR Legacy (+ optional FNDDS) from the bulk CSV/JSON;
  normalize to per-100 g macros; `source: USDA`, `status: VERIFIED` (lab-grade).
- **Open Food Facts** from the JSONL/Parquet dump, filtered to populated
  `nutriments`; key by barcode; `source: OPEN_FOOD_FACTS`, `status: UNVERIFIED`;
  refreshed by daily deltas. ADR-0006 governs licensing/segregation.

Seeded foods are imported **without** images; `imageStatus: NONE`. Images are
generated lazily on first use, not for millions of rows up front.

### Barcode lookup order

local catalog → Open Food Facts API → label-photo Gemini OCR (last resort).
Every successful OFF/Gemini resolution is cached back into `foodCatalog`, so the
*second* scan of anything is free. This is the core "cut the Gemini calls"
mechanism.

### Gemini flows (all `gemini-3.5-flash`, tool-calling)

The other extraction jobs in the codebase (DEXA, blood test, drug lookup) run on
flash; these follow suit, honoring the root `CLAUDE.md` flash-only rule. No ADR
needed (contrast ADR-0005, which is a *model* exception; ADR-0006 here is only
about data licensing).

- **`extract_meal_items`** — input: the meal photo (multimodal). Output: an
  array of `{ name, estimatedPortionGrams, macrosPer100g, confidence }`. The
  backend wraps each as an editable proposed entry; nothing is saved until the
  user confirms. Confirmed items with no catalog match become new
  `GEMINI_PHOTO` catalog foods.
- **`extract_nutrition_label`** — input: the label photo. Output:
  `{ servingSizeGrams, servingsPerContainer, macrosPerServing, productName? }`,
  normalized to per-100 g and proposed as a packaged `GEMINI_LABEL` food
  (attached to the scanned barcode if present).

If flash proves too weak for portion estimation, escalating to a Pro model is a
**future, separate** ADR (the ADR-0005 precedent), not assumed here.

### Studio image generation

`FoodImageGenerator` in `integrations/nutrition`, mirroring
`integrations/medication/DrugImageGenerator`:

- Model `gemini-3.1-flash-image-preview`.
- **Style** = the house studio prompt adapted for plated food: clean white
  marble surface, soft diffused light from upper-left, gentle shadows, shallow
  depth of field (~f/2.8), 100mm macro perspective, centered with negative
  space, premium editorial, **no text / branding / hands / clutter**,
  photorealistic.
- When the food originated from a user meal photo, that photo is passed as a
  **visual reference** (exactly as `DrugImageGenerator` uses RxImageAccess
  reference images) so the studio shot resembles what was actually eaten.
- Triggered **async, once per unique catalog food** (on create, or lazily on
  first view for seeded foods). `imageStatus` walks `PENDING → READY` (or
  `FAILED`, with a generic per-category fallback like the drug generator's
  fallbacks). Stored via `FoodImageStorage`.

### Storage (GCS)

Mirrors the existing bucket-storage classes (`DrugImageStorage`,
`LocationPhotoStorage`, `EquipmentImageStorage`):

- `MealPhotoStorage` — user-uploaded meal/label photos (like
  `LocationPhotoStorage`). The raw photo is kept alongside any generated image.
- `FoodImageStorage` — generated studio images (like `DrugImageStorage`),
  served as `https://storage.googleapis.com/{bucket}/foods/...`.

### REST endpoints

```
# Macro target
GET   /api/me/nutrition/target              active target
PUT   /api/me/nutrition/target              set / update target

# Day + entries
GET   /api/me/nutrition/today               EXISTING — extend: totals + per-meal entries + progress vs target
GET   /api/me/nutrition?from=&to=           EXISTING — extend with fiber/sugar
GET   /api/me/nutrition/{date}              day: meals -> entries, totals, target
POST  /api/me/nutrition/{date}/entries      add a food entry  { meal, foodId|adhoc, servingIndex, quantity }
PATCH /api/me/nutrition/{date}/entries/{id} edit entry (meal, serving, quantity)
DELETE /api/me/nutrition/{date}/entries/{id}
POST  /api/me/nutrition                     EXISTING legacy quick day-total — kept; modeled as a manual entry

# Food catalog (global; authenticated, not user-scoped)
GET   /api/foods/search?q=                  name prefix search
GET   /api/foods/barcode/{code}             local -> Open Food Facts -> cache
GET   /api/foods/{foodId}
POST  /api/foods                            create catalog food (manual / AI-confirmed)
POST  /api/foods/{foodId}/confirm           confirm/validate (distinct-user; may promote)

# AI capture (phone)
POST  /api/nutrition/capture/meal           upload meal photo  -> itemized proposal (no save)
POST  /api/nutrition/capture/label          upload label photo -> packaged food proposal
```

### Goals integration (the "Both" half)

Standalone targets cover day-to-day tracking. To let the structured Goals system
*also* reference nutrition, extend the metric key registry (IMPL-12) and
`FirestoreMetricResolver` with:

```
nutrition.proteinAvg7d     (exists)
nutrition.carbsAvg7d       new
nutrition.fatAvg7d         new
nutrition.fiberAvg7d       new
nutrition.sugarAvg7d       new
nutrition.targetMetDays    new — COUNT of days where all macros fell within target
```

These resolve off the same rollup data and ride the existing
`MetricChangedEvent` → `StepEvaluationService` path. No new evaluation
machinery; a Goals Step can now bind to "average 180 g protein over 7 days" or
"hit my macros 40 days".

---

## Web UI — Next.js (the web-first milestone)

New primary destination **Nutrition** in the left nav (after Meds).

### Today (`/nutrition`)

- **Macro progress** at the top: a ring or bar per nutrient (calories, protein,
  carbs, fat, fiber, sugar) showing consumed vs target and remaining, in the
  oatmeal/olive token system.
- **Meals**: four sections (Breakfast / Lunch / Dinner / Snack), each listing
  its entries (food name, portion, macros) with per-meal subtotals.
- **Add food**: opens a catalog **search modal** → pick a food → choose a
  serving + quantity step (0.5× / 1× / 1.5× / 2×) → live macro preview → save.
  A **Quick add** affordance allows ad-hoc macros without a catalog food. No
  camera on web.
- Date switcher to log/review past days.

### Target (`/nutrition/target`)

Set the six daily macro values; shows the active target and history. Optional
"calculate from calories" helper that splits a calorie goal into macro grams.

### History / trends (`/nutrition/history`)

Range view over the daily rollups — per-nutrient trend vs target, days-on-target
count. Reuses the existing range endpoint.

Catalog foods sourced from Open Food Facts render the ADR-0006 attribution line
on their detail.

---

## Android UI — Kotlin / Compose

A `feature-nutrition` module mirroring `feature-goals`; networking through
`core-data` (Retrofit `NutritionApi` + `FoodApi`, repositories), screens in
Compose/Material 3. Phone gets **everything web has, plus the camera**.

### Screens

- **Today** and **Target** — the Compose twins of the web pages: macro progress
  rings, meal sections, catalog search-and-add, target editor.
- **Capture** — the camera screen. A **segmented toggle `Barcode ⇄ Photo`** as
  the user requested:
  - **Barcode** mode: on-device scanning via **ML Kit Barcode Scanning** (free,
    offline) yields the GTIN; the backend resolves it
    (`GET /api/foods/barcode/{code}`); the matched food is shown to confirm and
    log. ML Kit returns only the code — no nutrition AI call.
  - **Photo** mode: a secondary chip picks **Meal** vs **Label**.
    - *Meal* → `POST /api/nutrition/capture/meal` → Gemini itemizes → an
      **editable item list** (add/remove/adjust portion) → confirm → entries
      saved, new items become catalog foods.
    - *Label* → `POST /api/nutrition/capture/label` → Gemini OCRs the panel →
      a proposed packaged food → confirm → cached to the catalog.
- Capture uses **CameraX**. Photos upload to the backend (`MealPhotoStorage`);
  the studio image is generated async and appears on the food once `READY`.

On a foldable unfolded, Today + detail become a two-pane layout, matching the
Goals treatment.

---

## Phased roadmap

Sequenced so each milestone is independently shippable and the cheap, broadly
useful parts land before the expensive AI parts.

**Milestone 1 — Web foundation (web-first).**
Extend `NutritionDailyLog` (+fiber, +sugar) and make it a rollup; add
`MacroTarget`, `FoodEntry`, and a minimal `CatalogFood` with **manual create +
name search** (no seed yet); target + entry REST endpoints; web Today, Target,
History pages with manual catalog logging. *No AI, no camera, no seed.* Proves
the core loop end-to-end. **← first ship.**

**Milestone 2 — Food catalog seeding.**
Open-data import Cloud Run Job: USDA (CC0) generics, Open Food Facts (ODbL)
packaged; barcode lookup endpoint with Open Food Facts fallback + cache-back;
ADR-0006 attribution in the UI. Catalog now answers most lookups for free.

**Milestone 3 — Android parity + capture.**
`feature-nutrition`: Today, Target, manual logging; then the Capture screen —
Barcode (ML Kit) → lookup, Label photo → Gemini OCR, Meal photo → Gemini
itemize-and-confirm. Photos stored to GCS.

**Milestone 4 — Studio images.**
`FoodImageGenerator` (async, once per unique food, reference-photo when
available), `FoodImageStorage`, `imageStatus` lifecycle, fallbacks; surface
images in web + Android.

**Milestone 5 — Validation at scale + Goals binding.**
Raise the verification threshold for true multi-user promotion; add the new
`nutrition.*Avg7d`, `nutrition.targetMetDays` metric keys + resolver branches so
structured Goals can bind to nutrition.

---

## Out of scope for IMPL-13

- Offline / on-device food catalog and any bulk catalog export (would trigger
  the ODbL share-alike question — see ADR-0006 "Revisit when").
- Micronutrients (vitamins/minerals) — macros + fiber + sugar only.
- Recipes / saved multi-food meals as reusable units (entries only for v1;
  meal-photo items are saved individually).
- Restaurant-menu databases and water/hydration tracking.
- A dedicated search engine (Typesense/Algolia) — v1 uses Firestore prefix
  search.
- Notifications / reminders to log.
- A Pro model for meal estimation (separate ADR if ever needed).

## Acceptance criteria

- A user can set a six-nutrient daily target on web and Android; both render the
  same progress view.
- Logging a catalog food into a meal updates that meal's subtotal, the day
  total, and progress vs target; the day rollup equals the sum of its entries.
- Editing a catalog food does **not** retroactively change already-logged
  entries (macros are snapshotted).
- Name search returns catalog foods; selecting one with a serving + quantity
  computes correct macros.
- A barcode scan resolves from the local catalog when present, else from Open
  Food Facts, and the resolved product is cached so the next scan makes no
  external call. Open Food Facts–sourced foods show the ODbL attribution.
- A nutrition-label photo of an unknown packaged good produces a proposed food
  the user confirms into the catalog, attached to its barcode when scanned.
- A full-meal photo produces an editable, itemized list of components with
  portion estimates; after edits, confirming logs entries and creates catalog
  foods for new items.
- A newly created catalog food gets a studio image generated asynchronously in
  the house style (using the user's photo as reference when available); the UI
  shows a pending state until `READY` and a category fallback on `FAILED`.
- Confirming a food increments its distinct-user confirmation count; reaching
  the configured threshold flips it `UNVERIFIED → VERIFIED`. The same food
  confirmed twice by one user counts once.
- The existing `nutrition.*Avg7d` goal metrics continue to update from the new
  rollup with no regression, and a Goals Step can bind to a nutrition metric.
- Gemini is invoked only on a catalog miss (unknown barcode/label, or a meal
  photo) — never for a food already in the catalog.
