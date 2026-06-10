# IMPL-16: Implementation Decisions Log

Questions that came up during implementation and the decisions made
autonomously, for post-hoc review. Companion to
[IMPL-16-med-reminders-and-nutrition-ux.md](IMPL-16-med-reminders-and-nutrition-ux.md).

Format: each entry states the question, the decision, the rationale, and
where in the code it landed.

---

## Part D — calories derived from macros

### D-1: Calories-only entries stay loggable
**Q:** If calories are always derived from macros, what happens to a quick
add where the user knows only the calories (e.g. a 150 kcal beer) and enters
no macros?
**Decision:** Derivation only kicks in when at least one of protein/carbs/fat
is non-null. With all three absent, the supplied calories are kept verbatim.
Explicit zeros DO derive (0 g macros ⇒ 0 kcal) — entering `0/0/0 + 500 kcal`
is treated as physically impossible and corrected to 0.
**Where:** `Macros.withDerivedCalories()` (`core/nutrition/Macros.java`).

### D-2: Derivation lives in the service layer, not DTO validation
**Q:** Reject inconsistent input (400) or silently correct it?
**Decision:** Silently correct at the service chokepoints
(`NutritionService.logDay/addEntry/updateEntry/addCompositeMeal/
finalizeCompositeMeal/finalizeSingleFood/updateIngredient/compositeTotal`,
`FoodCatalogService.create`). The server is the source of truth; clients
additionally make the calories field read-only so the user is never surprised
by the correction.

### D-3: Carbs include fiber (no fiber subtraction)
**Q:** US labels count fiber inside total carbs, and fiber contributes
~2 kcal/g rather than 4. Subtract it?
**Decision:** Plain 4/4/9 with carbs as given (fiber included). Simple,
matches label convention, and consistent everywhere. Alcohol has no field in
the data model, so 7 kcal/g is out of scope.

### D-4: AI/label extraction is corrected at the source
**Q:** Apply derivation only on persist, or also on AI proposals the user
previews?
**Decision:** Also derive inside `MealPhotoExtractor`,
`MealDescriptionExtractor` and `NutritionLabelExtractor`, so previews,
proposals and saved meals already show the corrected kcal — what you preview
is exactly what gets logged. Consequence: a scanned label's stored kcal can
differ slightly from the printed panel (labels legally round).

### D-5: OFF/USDA catalog data is left untouched
**Q:** Rewrite Open Food Facts / USDA seeded `macrosPer100g` too?
**Decision:** No — upstream source data is preserved as imported (ODbL/CC0
provenance), but any *entry logged from it* gets derived calories via
`addEntry`. Foods created through `FoodCatalogService.create` (manual + all
Gemini sources) do derive.

### D-6: Existing stored data is healed lazily, not migrated
**Q:** Backfill existing Firestore entries/day rollups?
**Decision:** No migration. Any edit/re-portion of a legacy entry re-derives
(composite normalization in `NutritionService` heals per-ingredient values),
and day rollups re-derive on the next entry mutation of that day. Historical
untouched days keep their original kcal.

### D-7: Pre-existing test fixtures updated, not worked around
Four backend tests asserted calorie values inconsistent with their own macro
fixtures (e.g. label kcal 200 vs derived 192). The expectations were updated
to the derived values — this is precisely the intended behavior change.

---

## Part C — exact packaged products

### C-1: Product name format
**Q:** Where does the exact identity live — separate brand/name fields or one
display name?
**Decision:** The item/entry *name* is the full product name (brand + line +
flavor/variant, e.g. "Fairlife Core Power Elite 42g, Chocolate"); `brand`
additionally lands in `CatalogFood.brand` (field already existed). The prompt
instructs the model to prefer the product's *official nutrition values* when
it recognizes the exact product.
**Where:** `MealPhotoExtractor`, `MealDescriptionExtractor` (prompt + tool
schema + parsing), `MealCaptureService.finalizeProduct`.

### C-2: Unrecognizable brand falls back to specific-generic
When no brand is legible/recognizable the model is told to use a specific
generic name ("Chocolate protein shake") and leave brand unset — same as old
behavior, so the change is strictly additive.

### C-3: Repeat captures reuse the catalog food
**Q:** Every product capture used to mint a new catalog food + new image.
**Decision:** `FoodCatalogService.findProduct(name, brand)` matches an
existing `category="product"` food by case-insensitive exact name (+ brand
when present) before creating. Reuse also skips a redundant image generation.
Trade-off: matching is by AI-extracted name, so a small naming variation
("42g" vs "42 g") still creates a second food — acceptable, self-corrects as
the model is consistent for identical photos.

### C-4: Brand visibility allowed in generated product images only
**Q:** The house image style globally forbids text/branding; the user wants
the exact product depicted.
**Decision:** The packaged-product style now *requires* faithful reproduction
of the real container, label and logo, and forbids only *added* text beyond
the product's own packaging. A new product-specific reference-photo prompt
("same exact product, re-staged in studio style") replaces the meal-oriented
reference prompt for products. Ingredient and plated-meal prompts keep the
no-branding rule.
**Note for review:** generated images may now include trademarked packaging;
fine for a personal app, worth revisiting if Tesseta is ever distributed.

### C-5: Described products stay composite-meal shaped
The describe flow still logs a saved meal with one ingredient rather than a
single-food product entry (restructuring it was out of scope), but that
ingredient/meal now carries the exact product name and official-values
preference. The capture flow is the one that produces true product entries.

### C-6: Barcode/OFF cross-linking deferred
Matching an AI-identified product to an Open Food Facts entry (for canonical
macros/barcode) was considered and deferred — no reliable join key without
the user scanning the barcode, which the barcode flow already handles.

---

## Parts B1 + E1 — async describe & recent meals (backend)

### B1-1: New endpoint instead of changing the existing one
**Q:** Make `POST /{date}/describe-meal` async, or add a sibling?
**Decision:** New `POST /{date}/describe-meal-async`; the synchronous
endpoint and the two-phase `/api/nutrition/describe` preview endpoint remain
untouched so the web client keeps working until it migrates and third
parties/old app builds never break.

### B1-2: Placeholder is named with the user's own text
The pending row shows the typed description (first 60 chars) rather than
"Analyzing photo…", and a failed resolution KEEPS that text (the photo path
still renames to "Couldn't read photo"). The user can see what they asked
for and retry/delete.

### B1-3: Placeholder source is MANUAL
Described meals were already logged with `EntrySource.MANUAL` by
`logResolvedMeal`; the placeholder matches, and `markAnalysisFailed` uses
source==PHOTO to decide whether to rename. The 5-minute stale-ANALYZING
sweep covers describe placeholders for free.

### E1-1: Recent meals returns `EntryResponse` objects
**Q:** Define a new RecentMeal DTO?
**Decision:** No — `EntryResponse` already carries name, image, macros,
serving, quantity, full ingredient breakdown (with per-100g baselines),
date and meal: everything a one-tap re-log needs through the existing
add-entry/composite-meal endpoints. Less wire surface to maintain.

### E1-2: Dedupe identity
`foodId` when present (catalog/barcode/product foods); otherwise
kind-prefixed normalized name (`meal:`/`manual:` + lowercased name) so a
composite "Chicken bowl" doesn't collide with a manual quick-add of the same
name. Newest occurrence wins (ordering by `createdAt` desc, nulls last).

### E1-3: Recency beats frequency (per user's interview choice), bounds clamped
`days` clamps to [1,60], `limit` to [1,50]; defaults 14/20. ANALYZING,
FAILED and blank-named entries are skipped. Implementation reads one
Firestore query per day (day-keyed subcollections have no cross-day query
without a collection-group index); at 14 reads per open of the add sheet
this is fine for a personal app — revisit with a collection-group index if
it ever matters.

---

## Part A1 — reminder config (backend)

### A1-1: One settings document instead of fields on Medication/TimeSlot
**Q:** The spec said `TimeSlot.reminderTime` + `Medication.remindersEnabled`.
**Decision:** Implemented as a single preferences doc
(`users/{u}/settings/medicationReminders`) holding the master switch, the
four window times, AND per-medication overrides `{enabled, times{window→HH:mm}}`
keyed by medicationId. Rationale: reminder timing is a user preference, not
part of the medical record — this avoids touching the Medication schema, its
change-history/audit log, every DTO, and the Android Room mirror payloads.
The resolution rule is unchanged from the spec: override → user window time →
built-in default (06:00/12:00/18:00/21:30).
**Trade-off:** overrides for deleted medications linger in the doc (harmless,
ignored at resolution time).

### A1-2: Scheduling stays on-device
The backend stores config only; Android computes alarms from the medication
mirror + this settings doc. PUT writes fan out under a new
`medicationReminderSettings` collection tag so other devices replan. Android
fetches the settings via REST and caches them locally (no new Room mirror
table needed).

### A1-3: Times on the wire are "HH:mm" strings, windows by enum name
Matches the existing date-as-ISO-string convention in Firestore docs and
keeps the DTO trivially JSON-friendly on both clients.

---

## Parts B/D/E — Android client

### AND-1: Re-log goes through a new server-side copy endpoint
**Q:** Re-logging a recent composite meal via the existing composite-meal
endpoint would re-create one catalog food per ingredient and re-run image
generation on every repeat.
**Decision:** Added `POST /api/me/nutrition/{date}/relog`
`{sourceDate, sourceEntryId, meal}` — the server copies the source entry
(foodIds, frozen macros, ingredients, finished-meal image) with no AI work.
The recent-meals response therefore needed each entry to carry its `date`
(added as a nullable field on the wire model).

### AND-2: Synthetic "Uploading photo…" rows live in memory, not the mirror
**Q:** Where does the optimistic row live while a capture photo uploads in
the background? Writing it to the Room mirror would either replay through
the outbox (wrong — the upload worker creates the server entry) or be pruned
by the next server reconcile.
**Decision:** A singleton in-memory `PendingCaptureStore` feeds synthetic
ANALYZING rows into the Today page (presentation-only merge; totals
untouched; rows are guarded from edit/delete/drag). The JPEG itself is
parked in a cache file and uploaded by a WorkManager worker (network
constraint, exponential backoff, 5 attempts, survives process death). After
process death the synthetic row is gone but the upload still completes and
the entry appears on the next refresh.

### AND-3: Terminal upload failure drops the row silently
After 5 failed attempts the worker deletes the parked photo and clears the
synthetic row — no notification. Rationale: the capture screen already
requires connectivity to shoot, so terminal failures are rare; a stuck
forever-"uploading" row is worse. Flagged for review — a retry affordance
could be added later.

### AND-4: Describe placeholder still does one quick POST before the sheet closes
"Fire-and-forget" waits only for the 202 (placeholder creation), not for
resolution. The sheet closes immediately after; a network error surfaces as
the day-screen error banner. Offline describing is not supported (AI flows
are online-only per the existing D17 decision).

### AND-5: Search and describe share one text field
Short queries search the catalog (debounced, inline trailing spinner — the
old layout-shifting centered spinner block is gone); beneath the results a
standing "✨ Log “…” as a meal" row describes whatever was typed. The
separate Describe tab/preview UI was deleted. Recents render as the
default empty-query content with one-tap re-log; quick add stays as a row
beneath.

### AND-6: Calories-only entries keep a manual kcal field
In quick-add and entry-edit, the calories field is replaced by a live
"computed from macros" readout the moment any macro is typed (4/4/9), but
when protein/carbs/fat are all blank a manual calories field remains — same
rule as the backend (D-1).

---

## Parts B/D/E — web client

### WEB-1: Per-meal "Add food" buttons kept
The user's "don't have the buttons" applied to the modal's meal button row
(now a tappable chip, pre-set from the section you clicked). The per-section
Add buttons on the page were kept — on web all four meal sections are always
visible, so they're navigation, not a meal-question. The chip still allows
overriding.

### WEB-2: Fire-and-forget UX via toast + revalidate
Describe/relog close the modal immediately; the server action revalidates
the page so the ANALYZING placeholder (with a pulsing "processing" badge)
appears on the next render. Errors surface as toasts after the fact.
`PendingImageRefresher`'s budget was raised from 60 s to ~3 min and now also
polls while any entry is ANALYZING (the server fails stale placeholders at
5 min, so polling longer is pointless).

### WEB-3: ANALYZING rows are not editable, but are deletable
Clicking a processing row does nothing (nothing to edit yet); the delete
button still works so a mistaken describe can be removed immediately.

---

## Parts A2/A3 — Android reminders

### A2-1: Single-alarm chain instead of N scheduled alarms
Only the SOONEST upcoming reminder is armed with AlarmManager; firing posts
the notification and arms the next. Re-armed on app start, boot,
timezone/clock change, settings save, and a 12-hour WorkManager safety-net.
Avoids alarm-count limits and stale-alarm cleanup entirely.

### A2-2: Fire-time re-resolution
The alarm payload carries only the fire timestamp. At fire time the engine
re-plans from the medication mirror + cached settings and shows only doses
still untaken — a dose logged in-app after planning silently drops out, and
medication edits made since planning are honored.

### A2-3: Notification actions within Android's 3-action cap
≤3 medications ⇒ one "✓ <name>" action each; >3 ⇒ a single "✓ Take all".
Each action logs adherence through the existing offline outbox (so check-off
works without connectivity) and re-posts the notification minus the taken
meds; it cancels automatically when none remain (the user's auto-clear
requirement). Notification ids derive from the fire time, so morning and
evening reminders coexist in the shade (persistent-until-acted-on).

### A2-4: Exact alarms with a windowed fallback
`setExactAndAllowWhileIdle` when the user grants exact-alarm access
(Android 12+ special access; a settings banner deep-links to it), otherwise
a 15-minute windowed alarm — reminders still arrive, just less precisely.

### A2-5: Receivers + engine live in core-data, not app
The engine needs MedicationRepository/AdherenceRepository (core-data) and
the receivers need Hilt injection; library-manifest merging makes core-data
the simplest home (the app module only adds the startup replan hook). The
notification's pill icon is a core-data drawable.

### A2-6: No FCM-triggered replan (v1)
Medication changes made on another device reach this one via the existing
sync pull, but don't immediately replan alarms; the app-open replan and the
12h worker bound the staleness. Wiring replan into the sync engine was
deferred to keep the sync path untouched.

### A3-1: One settings screen houses everything
Window default times AND per-medication mute/custom-slot-times live on a
single "Dose reminders" screen (bell chip on the medications list) rather
than spreading per-med settings into the add/edit medication form — matching
the single-document config model (A1-1).

### A2-7: Tapping the notification opens the app's launcher activity
No deep link to the medications checklist yet — the nav graph has no
deep-link plumbing and adding it touched MainActivity scope. Flagged as a
nice-to-have follow-up.
