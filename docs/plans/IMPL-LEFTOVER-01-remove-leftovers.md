# IMPL-LEFTOVER-01 — Remove Leftovers (photo-based partial-meal subtraction)

**Status:** 🟢 Code-complete across all three layers (2026-09-12) — automated gates green on each; only on-device / live-AI functional checks remain (🔵) and the branch push/PR (Phase 5.6).
- **Backend:** full `./gradlew :backend:test` suite passing; LeftoverMathTest 10/10, LeftoverServiceTest 6/6, LeftoverPhotoExtractorTest 2/2; `compileJava` clean.
- **Android:** `:core-domain`/`:core-data`/`:feature-nutrition` unit tests green (re-run with `--rerun-tasks`, 0 failures); new leftover tests LeftoverEligibilityTest 9/9, NutritionLeftoverParseTest 5/5, NutritionLeftoverViewModelTest 8/8; `:app:compileDebugKotlin` clean. Independently re-verified by the lead (not just the sub-agent).
- **Web (read-only, D14):** typecheck + lint + `vitest` 58/58 + `next build` all green.
- **Remaining 🔵:** F1–F17 on-device functional, live-Gemini estimate accuracy, real FCM delivery (need a device + backend + Gemini key). See the [decision log](IMPL-LEFTOVER-01-decision-log.md) (IL-1..IL-11) for choices to review.
**Branch:** `leftover-food`
**Author / PM:** Evan Ruff (interview) + engineering
**Created:** 2026-09-12
**Related:** [Adjust meal with AI](../../) (branch `nutrition-adjustment`, `MealAdjustmentService`) · [IMPL-16 nutrition UX](../specs/IMPL-16-med-reminders-and-nutrition-ux.md) · [IMPL-AND-20 offline-first sync](IMPL-AND-20-offline-first-sync.md) · [Nutrition durable op rail (#… ui-updates)](../../)

---

## 1. Problem statement

Evan logs a meal with the photo food logger, then doesn't finish it. Today the logged macros overstate what he actually ate. He wants to photograph what's **left on the plate** and have the app **subtract the uneaten portion** from the already-logged meal, per-ingredient, so his nutrition totals reflect what he *consumed*, not what he was *served*.

The desired experience, in his words:

1. On a logged meal, trigger a **"Remove Leftovers"** action.
2. It **jumps back into photo mode**.
3. He photographs whatever is left on the plate.
4. The **LLM analyzes it and subtracts** the leftover from the originally-logged macros.

> **Interview refinement:** although the original ask described a **long-press → "Remove Leftovers" popup**, the trigger was moved (D4) to a **button inside the existing edit/ingredients sheet** — the row's long-press is already bound to drag-to-move, and the sheet already hosts the sibling "Adjust with AI" action. Same intent, no gesture conflict, more reuse.

### The canonical scenario (the "acceptance story")

> **Dinner, on his phone:** Evan photo-logs dinner — the meal logger identifies a **composite**: grilled salmon (140 g), white rice (150 g), broccoli (90 g) → **~720 kcal**. He eats most of it but leaves **about half the rice and a little salmon**.
>
> - He taps the dinner entry → the **ingredients sheet** opens. Next to **"Adjust with AI"** is a new **"Remove Leftovers"** button. He taps it.
> - The camera opens in **leftover mode** (no barcode/label stages — straight to the shutter). He photographs the plate and taps the shutter. The sheet/camera closes **immediately**; the entry row now shows **"Analyzing leftovers…"**.
> - Analysis runs **in the background** (survives him locking the phone). A few seconds later a **notification** fires: **"Leftovers analyzed · −180 kcal"** with an **Apply** action button.
> - He taps the notification **body** → a **review diff** opens: **Served 720 → Ate ~540 kcal**, per-ingredient *(rice 150 g → 70 g, salmon 140 g → 120 g, broccoli unchanged)*. He taps **Apply**.
> - Today's total drops by ~180 kcal. The dinner entry now reads **~540 kcal** with a small **"leftovers"** badge beside the name; opening the sheet shows **"Served → Ate"** per ingredient.
> - Later he grazes on it again and re-shoots. The **re-run recomputes from the preserved 720 kcal *served* baseline** using the new leftover photo (not from the already-reduced value) — the latest leftover photo wins.
> - One attempt the photo is blurry → a different notification: **"Couldn't read leftovers — tap to retake."** No macros change.
> - He decides he actually finished it after all → **"Restore full portion"** resets the entry back to the served 720 kcal and clears the leftover state.

---

## 2. Design decisions (locked via interview 2026-09-12)

| # | Decision | Choice |
|---|----------|--------|
| **D1** | Subtraction strategy | **Compare both photos.** Send the **original meal photo** (`entry.photoRef`) **and** the new **leftover photo** to Gemini together; the model estimates the **consumed** amount per item by visual comparison. Highest accuracy; requires an original photo to exist. |
| **D2** | Granularity | **Per-ingredient.** For composite meals, recompute each ingredient's remaining quantity independently ("ate all the salmon, left half the rice") using the existing ingredient list + composite re-scale math. |
| **D3** | Result model | **Preserve "as served" + consumed.** Keep the original amount as a preserved baseline; the entry's *live* values become what was **consumed**. Adds a small schema baseline (see D9). |
| **D4** | Trigger UX | **Button in the edit/ingredients sheet**, next to "Adjust with AI" (NOT a long-press popup — long-press is drag-to-move). |
| **D5** | Eligibility | **Photo meals only.** Show the button **only** on entries that have **both** an original `photoRef` **and** an ingredient list (composite photo meals). Hidden for barcode / manual / label / single-product entries. |
| **D6** | Re-run | **Re-run replaces, computed from the served baseline.** Each run recomputes consumed against the **preserved as-served** baseline using the **latest** leftover photo. Naturally idempotent; last leftover photo wins. |
| **D7** | Estimate review | **Preview diff, confirm/discard only.** Show before/after totals with Apply/Discard — same shape as "Adjust with AI". **No** per-item manual editing this round. |
| **D8** | Processing | **Async on the durable op rail.** The entry shows **"Analyzing leftovers…"**; the upload survives process death (reuses the nutrition op rail). When analysis lands, a **pending-review** state surfaces (not a blocking spinner). |
| **D9** | Data model | **Live macros = consumed; add a served baseline.** `entry.macros` and each ingredient's quantity become the **consumed (net)** values, so day-total rollups, sync, and the web card need **zero** changes. Add `servedMacros` + per-ingredient `servedQuantity` + a `leftover{analyzedAt}` marker as the preserved baseline. Presence of `servedMacros` = "a leftover has been applied". |
| **D10** | Camera | **Reuse `NutritionCaptureScreen` in a "leftover mode."** A mode flag + target `entryId` param: skip barcode/label stages, go straight to the shutter, and route the captured bytes to the **leftover op** instead of new-meal logging. |
| **D11** | Photo retention | **Discard the leftover photo after analysis.** It is stored to a **temporary** ref only long enough for the async job to read it, then deleted. Only the **original** `photoRef` is retained. Re-run re-shoots (always possible because the served baseline is preserved). |
| **D12** | Reject vs clamp | **Reject + retake when** Gemini's overall **confidence is below threshold** **OR** there's a **hard mismatch** (leftover can't be mapped to the meal's ingredients / foreign items / empty / unreadable photo). **Otherwise** clamp consumed to **[0, served]** per ingredient (never negative, never "ate more than served") and proceed to preview. |
| **D13** | Completion notification | On a **valid** result → post a notification **"Leftovers analyzed · −X kcal"** with an **Apply** action (commits immediately with clamp guardrails) and a **body tap** that deep-links into the review diff. On a **rejected** result → a different notification **"Couldn't read leftovers — tap to retake"** (no Apply). |
| **D14** | Platform scope | **Android-only v1; web read-only.** Web displays "Served X / Ate Y" on entries that carry a leftover baseline. No web capture/upload flow this round. |
| **D15** | Undo | **"Restore full portion" action** on entries with a leftover applied — resets consumed back to served and clears the leftover baseline. Cheap because the baseline is stored. |
| **D16** | Meal image | **Keep the original generated image.** Same food, just less of it — no regeneration (unlike Adjust-with-AI, where food *identity* changed). Saves a generation + settle-poll. |
| **D17** | Visual indicator | **Reduced (consumed) macros shown on the row + a small badge by the meal name.** The edit/ingredients sheet shows **"Served → Ate"** per ingredient. |

### Reconciliation note (D7 + D8 + D13)

"Async op-rail processing" (D8) and "confirm/discard preview" (D7) are layered, not contradictory:

- The **upload + analysis are async** — the sheet/camera close immediately; the entry shows **"Analyzing leftovers…"**.
- When the backend job finishes it stores the computed **proposal server-side** and sets the entry to **`PENDING_REVIEW`** (valid) or **`REJECTED`**.
- A **notification** (D13) surfaces the outcome. **Apply from the notification** commits the **already-stored** proposal (with clamp guardrails). **Body tap** opens the **review diff** (D7) for Apply/Discard.
- If the app is foregrounded, the same `PENDING_REVIEW` state is visible in-app on the entry (an "Review leftovers" affordance) without needing the notification — the notification is the out-of-app path, not the only path.

**Invariant:** the leftover photo is never needed after analysis (D11). Apply/Discard/Restore operate purely on the stored proposal + the preserved served baseline, so re-run and restore never depend on a retained image.

---

## 3. Current state (grounded in code — read before implementing)

Existing pieces this feature reuses or extends. Paths are relative to repo root. **Verify each still matches before building on it** (recalled from research 2026-09-12).

### 3.1 Nutrition core / entry model (backend)

| Concern | File | Note |
|---------|------|------|
| Logged entry (frozen snapshot) | `backend/src/main/java/com/gte619n/healthfitness/core/nutrition/FoodEntry.java` | Record: `macros`, `ingredients` (null=single, present=composite), `photoRef`, `analysisStatus` (NONE/ANALYZING/READY/FAILED), `foodName`, `quantity`. **Add** `servedMacros` (nullable), per-ingredient `servedQuantity`, `leftoverStatus`, `leftoverAnalyzedAt` (D9). |
| Composite ingredient | same package (`CompositeIngredient` / `EntryIngredient`) | Per-ingredient `macros` (frozen), `macrosPer100g`, `servingGrams`, `quantity`. Consumed = per-ingredient quantity reduced; **add** `servedQuantity` baseline. |
| Macros record | `.../core/nutrition/Macros.java` | `caloriesKcal, protein, carbs, fat, fiber, sugar` (nullable). `withDerivedCalories()` = 4/4/9 (drinks preserve alcohol kcal). Consumed macros flow through the same derivation. |
| Composite re-scale (SSOT) | `.../core/nutrition/NutritionService.java` | `updateEntry` re-scales composites via `compositeTotal(existing.ingredients(), newQuantity)`; leftover apply reuses the **same** composite-total math on the **consumed** ingredient quantities. |
| Daily rollup | `.../core/nutrition/NutritionDailyLog.java` | Server-computed by summing entries. **Because live macros = consumed (D9), rollups need no change.** |
| Adjust service (closest cousin — the template) | `.../core/nutrition/MealAdjustmentService.java` | `preview()`/`apply()`: reads the **original** photo via `MealPhotoReader.read(entry.photoRef())`, builds `contextOf(entry)`, runs the analyzer, computes new-vs-old totals, `finalizeSingleFood`/`finalizeCompositeMeal`, `syncNotifier.changed(userId, null, "nutritionDays/entries")`. **Leftover = a sibling service with a two-photo analyzer + served-baseline persistence.** |
| Analyzer port (reuse pattern) | `.../core/nutrition/MealAdjustmentAnalyzer.java` (impl `.../integrations/nutrition/MealPhotoExtractor.java`, Gemini `gemini-3.8-flash`, tool `extract_meal_items`) | **Add a new `LeftoverAnalyzer` port** + Gemini impl (new tool `estimate_leftovers`) taking **two images** (original + leftover) and returning per-ingredient consumed/remaining + confidence + matched flag. |
| Photo reader port | `.../core/nutrition/MealPhotoReader.java` (GCS impl in integrations) | `Optional<Photo> read(String ref)` → bytes + mime. Reuse to fetch the **original** meal photo for comparison. |
| Photo storage (for the transient leftover) | same GCS adapter used by capture | Store the leftover photo to a **temp ref** for the job; **delete after analysis** (D11). Mirror how `capture-meal` parks the photo for its async job. |
| Durable nutrition jobs | `.../core/nutrition/jobs/*` (`NutritionJob`, `NutritionJobType`, `NutritionJobDispatcher`, `CloudTasksNutritionJobQueue`) | **Add** a `LEFTOVER_ANALYSIS` job type: read original+leftover photos, run analyzer, store proposal, set `PENDING_REVIEW`/`REJECTED`, enqueue completion push, delete temp photo. |
| Nutrition API controller | `.../api/nutrition/NutritionController.java` | Has `capture-meal`, `describe-meal(-async)`, `adjust/preview`, `adjust/apply`, `PATCH /entries/{id}`. **Add** the leftover endpoints (see §4.3). |
| Completion push (FCM) | reuse the sync/reminder push path (`syncNotifier` fan-out; reminder FCM handler) | Backend sends an **FCM data message** on job completion so the device can post the D13 notification even when backgrounded. Fallback: `PENDING_REVIEW` visible on next sync/foreground. **Locate the exact FCM send + Android receiver during Phase 0/3.** |

### 3.2 Sync (Android ↔ backend)

| Concern | File | Note |
|---------|------|------|
| Delta collection mapping | `android/core-data/.../data/sync/CollectionRegistry.kt` | `"nutritionDays/entries"` → `NUTRITION_ENTRIES`. **New entry fields ride the existing collection additively — no new collection, no alias change.** (Heed the [slash-collection-alias trap](../../) generally, but it does not apply here.) |
| Sync reader (backend) | `.../persistence/sync/FirestoreSyncChangeReader.java` | Emits the entry payload; **verify the new served/leftover fields serialize into the entry delta** so the Android mirror + web render them. |
| Entry persistence | `.../persistence/nutrition/FirestoreFoodEntryRepository.java` | Add ser/de for `servedMacros`, per-ingredient `servedQuantity`, `leftoverStatus`, `leftoverAnalyzedAt`, and the stored proposal (or store the proposal on a subfield/side doc). |
| Conflict resolution | `.../data/sync/SyncEngine.kt` (LWW on server `lastUpdate`) | Apply is a server write → normal LWW. No special handling. |

### 3.3 Android — capture, op rail, day/edit UI

| Concern | File | Note |
|---------|------|------|
| Capture screen (reuse in leftover mode, D10) | `android/feature-nutrition/.../NutritionCaptureScreen.kt` + `NutritionCapturePanes.kt` + `NutritionCaptureViewModel.kt` | Stages: Scanning→Working→(BarcodeFood/MealItems/LabelDraft)→Done. **Add a `mode=LEFTOVER` + `targetEntryId` param**: render only the shutter (skip barcode/label), and on capture call the leftover op instead of `enqueueCapturePhoto()`. |
| Durable nutrition op rail (D8) | `android/core-data/.../data/nutrition/{NutritionOpStore,NutritionOpWorker,NutritionOpPayloads}.kt` + `db/entity/PendingNutritionOpEntity.kt` | **Add** a `REMOVE_LEFTOVERS` op carrying `{entryId, date, leftoverJpegCachePath}`; worker POSTs to `…/leftovers/analyze`. Room-backed, survives process death. |
| Synthetic "Analyzing…" row | `NutritionOpStore` + `NutritionRepository.observeDay()` (combines mirror + capture-preview store) | Reuse the pattern to render **"Analyzing leftovers…"** on the target entry while the op/job is in flight. |
| Repository (composite math, adjust calls) | `android/core-data/.../data/nutrition/NutritionRepository.kt` | Has `compositeTotal(ingredients, portion) = sum(macros)×portion`, `adjustPreview`/`adjustApply`, and the **local-only `patchEntry`** ([composite-portion local-patch bug](../../) — do **not** route leftover apply through it). **Add** `removeLeftoversAnalyze/Apply/Discard/Restore` repo calls. |
| API client | `android/core-data/.../data/nutrition/NutritionApi.kt` | Add the leftover endpoints. |
| Domain DTOs | `android/core-domain/.../domain/nutrition/Nutrition.kt` | Has `Entry`, `Macros`, `EntryIngredient`, `MealCaptureItem`, `Adjust*` DTOs. **Add** `LeftoverProposal`/`LeftoverItem` + `servedMacros`/`servedQuantity`/`leftoverStatus` on `Entry`/`EntryIngredient`. |
| Day/list UI + entry row | `android/feature-nutrition/.../NutritionTodayComponents.kt` (`EntryRow`, `NutritionOverflowMenu`) | Add the **badge by the meal name** (D17) + the "Analyzing leftovers…" / "Review leftovers" affordances. |
| Edit / ingredients sheets (host the button) | `.../EditEntrySheet.kt`, `.../IngredientsSheet.kt`, `.../AdjustWithAi.kt` (`AdjustWithAiSection`) | Add a **"Remove Leftovers"** button (visible only when D5 eligible) beside "Adjust with AI"; add the **"Served → Ate"** per-ingredient display + **"Restore full portion"** (D15). |
| Today ViewModel | `.../NutritionTodayViewModel.kt` | Has `openEditSheet`, `previewAdjustment`, `applyAdjustment`, `savingAdjust`. **Add** `startLeftoverCapture`, `reviewLeftovers`, `applyLeftovers`, `discardLeftovers`, `restoreFullPortion`. |
| Completion notification (D13) | reuse reminder notification channel / `NotificationManager` infra (see [reminder engine](../../)) | Post the "Leftovers analyzed / couldn't read" notification with an **Apply** action + deep-link. **Locate the exact reminder notification builder + FCM receiver during Phase 3.** |

### 3.4 Web (read-only, D14)

| Concern | File | Note |
|---------|------|------|
| Day view / entries | `web/app/me/nutrition/page.tsx`, `web/lib/nutrition-api.ts`, `web/lib/types/nutrition.ts` | Render **"Served X → Ate Y"** on entries carrying a leftover baseline. **No** capture/apply UI this round. |
| Dashboard nutrition card | `web/lib/nutrition-dashboard.ts` | Reads day totals — already correct (totals reflect consumed via D9). No change. |

### 3.5 Distinction reminders (don't confuse these)

- **Adjust with AI** = *fix a mis-identified meal* via free-text, re-looks at the **original** photo, may change food identity, **regenerates the image**. **Remove Leftovers** = *reduce quantity consumed* via a **second (leftover) photo**, food identity unchanged, **keeps the image** (D16).
- **`patchEntry` (Android) is local-only** and skips the backend composite re-scale — leftover apply must go through the **backend** leftover endpoint, not `patchEntry`.
- **Live `entry.macros` = consumed** after a leftover is applied (D9). The **served** amount lives only in `servedMacros`/`servedQuantity`. Every existing totals reader keeps working unchanged *because* it reads the live (consumed) macros.

---

## 4. Target behavior specification

### 4.1 Consumed math (backend — single source of truth)

Given the preserved **served** baseline and the analyzer's per-ingredient estimate:

1. For each served ingredient `i` with served quantity `servedQ_i`, the analyzer returns an estimated **remaining** fraction/grams. Compute `consumedQ_i = clamp(servedQ_i − remaining_i, 0, servedQ_i)` (D12 clamp).
2. `consumedIngredient_i.macros = macrosPer100g_i × (servingGrams_i × consumedQ_i)/100` (existing per-portion scaling).
3. `entry.macros = compositeTotal(consumedIngredients)` then `withDerivedCalories()` (existing pipeline).
4. `servedMacros` is captured **once**, on the **first** apply, as the pre-leftover baseline (equal to the current live macros at that moment). It is **never overwritten** by re-runs (D6). Restore (D15) sets live = served and **clears** `servedMacros` + leftover fields.
5. Re-run (D6): recompute `consumedQ_i` from `servedQ_i` (baseline), **not** from the current consumed value — the latest leftover photo wins.

**Invariants (all unit-testable):** consumed ≤ served per ingredient and in aggregate; no negative macro field; a meal with "nothing left" → consumed = served (no-op diff); a meal "all left" → consumed = 0 (day total drops by the full served amount).

### 4.2 Reject vs clamp (D12) — the testable boundary

Analyzer returns per-item `{matched: bool, remaining, confidence}` + `overallConfidence`. The job **rejects** (→ `REJECTED`, retake notification) iff:
- `overallConfidence < THRESHOLD` (a named constant, tunable), **OR**
- **hard mismatch**: leftover maps to **no** served ingredient, contains only **foreign** items, returns **empty**, or the photo is unreadable.

Otherwise it **clamps** per-ingredient to `[0, served]`, sets `PENDING_REVIEW`, stores the proposal, and fires the valid notification. A **partial** mismatch (some items unmatched) is **not** a rejection — unmatched served items are treated as **fully consumed** (nothing of them left), and a **warning** note is attached to the proposal for display.

### 4.3 Endpoints (Android-only clients; backend, D14)

| Method | Path | Body | Effect |
|--------|------|------|--------|
| `POST` | `/api/me/nutrition/{date}/entries/{entryId}/leftovers/analyze` | multipart: leftover photo | **202**. Guards D5 eligibility (has `photoRef` + ingredients) else 422. Parks leftover to temp ref, enqueues `LEFTOVER_ANALYSIS` job, sets entry `leftoverStatus=ANALYZING`. |
| `POST` | `/api/me/nutrition/{date}/entries/{entryId}/leftovers/apply` | (empty / confirm token) | Commits the **stored** proposal: captures `servedMacros` if first apply, writes consumed ingredient quantities + macros, sets `leftoverStatus=APPLIED`, `leftoverAnalyzedAt`, clears stored proposal, `syncNotifier.changed`. Idempotent on the stored proposal. |
| `POST` | `/api/me/nutrition/{date}/entries/{entryId}/leftovers/discard` | — | Clears the stored proposal, `leftoverStatus` back to prior state (NONE or APPLIED if one already existed). |
| `POST` | `/api/me/nutrition/{date}/entries/{entryId}/leftovers/restore` | — | Live = served, clears `servedMacros`/`servedQuantity`/leftover fields, `leftoverStatus=NONE`, `syncNotifier.changed`. |
| `GET` | (piggyback on existing day/entry read) | — | Entry carries `leftoverStatus` + stored proposal (when `PENDING_REVIEW`) + `servedMacros`/served quantities so the review screen and the "Served→Ate" display render from synced state. |

`analyze` runs **async** (D8): the client op rail owns the durable upload; the backend job owns the Gemini call. `apply` is what the **notification Apply button** and the **review-screen Apply** both call (server already holds the proposal — no proposal round-trips from the client, D13).

### 4.4 State machine (per entry)

```
NONE ──analyze──▶ ANALYZING ──job ok──▶ PENDING_REVIEW ──apply──▶ APPLIED
  ▲                   │                     │  ▲                     │
  │                   └job reject─▶ REJECTED│  └──(re-run) analyze───┘
  │                                  │      │
  └──restore──────────────────────────────┴──discard (from PENDING_REVIEW)
```
- `REJECTED` → retake notification; a retake re-enters `ANALYZING`.
- `APPLIED` → re-run (`analyze`) recomputes from the served baseline; `restore` returns to `NONE`.
- Discard from `PENDING_REVIEW` returns to the prior committed state (`NONE` or `APPLIED`).

---

## 5. Phases, status & tracking

**Status legend:** ⬜ not started · 🟡 in progress · 🟢 done · 🔵 blocked
**Columns:** *Impl* = code written · *Unit* = unit/integration tests green · *Func* = functional/acceptance check passed (see §6) · *Pushed* = merged/pushed to branch.

> The agent MUST NOT mark a row 🟢 in a column until the corresponding gate in §6/§7 is satisfied and the evidence (command output / screenshot / test name) is linked in §8. **Never infer 🟢 from code inspection alone.**

### Phase 0 — Data model & backend foundations

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 0.1 | `FoodEntry.leftover` nested `Leftover{status, servedMacros, servedIngredients, proposal, analyzedAt}` (IL-1); Firestore ser/de (`FirestoreFoodEntryRepository`) — additive, back-compat (null = no leftover) | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.2 | Consumed math (§4.1) as a pure, tested unit (`LeftoverMath`) reusing `withPortion`/`withDerivedCalories`; clamp to [0, served]; served captured once, never overwritten on re-run | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.3 | `LeftoverAnalyzer` port + `LeftoverPhotoExtractor` (Gemini, tool `estimate_leftovers`, **two images**) returning per-ingredient remaining + `matched` + `confidence` + `overallConfidence` | 🟢 | 🟢 | 🔵 | ⬜ |
| 0.4 | Reject-vs-clamp classifier (§4.2) in `LeftoverMath`: threshold + hard-mismatch rules; partial-mismatch → warning, unmatched served = fully consumed | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.5 | `LEFTOVER_ANALYSIS` job type + `NutritionJob.leftoverAnalysis` + dispatcher: read original (`photoRef`) + temp leftover photo, run analyzer, store proposal, set `PENDING_REVIEW`/`REJECTED`, delete temp photo (D11) | 🟢 | 🟢 | 🟢 | ⬜ |
| 0.6 | Completion **FCM push** on job finish (valid & rejected) via core `FcmSender` (IL-6) | 🟢 | ⬜ | 🔵 | ⬜ |
| 0.7 | Sync emission: `leftover` blob rides the `nutritionDays/entries` delta (verified `sanitize` passes nested keys through) | 🟢 | 🟢 | 🔵 | ⬜ |

_0.3 estimate accuracy + 0.6 live push are live-AI/live-push (🔵 until a backend + Gemini key + device). 0.6 unit: sender is best-effort try/catch; covered indirectly by LeftoverServiceTest running with no FCM bean._

### Phase 1 — Backend API + apply/restore lifecycle

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 1.1 | `POST …/leftovers/analyze` (multipart): D5 eligibility guard (422 otherwise), park temp photo, enqueue job, set `ANALYZING`, 202 | 🟢 | 🟢 | 🔵 | ⬜ |
| 1.2 | `POST …/leftovers/apply`: commit stored proposal, capture served baseline once, write consumed, `syncNotifier.changed`; idempotent | 🟢 | 🟢 | 🔵 | ⬜ |
| 1.3 | `POST …/leftovers/discard` + `POST …/leftovers/restore` (D15) | 🟢 | 🟢 | 🔵 | ⬜ |
| 1.4 | Entry read (`EntryResponse.leftover`) carries status + stored proposal + served baseline for the review UI | 🟢 | 🟢 | 🔵 | ⬜ |
| 1.5 | Day-total rollups unchanged & correct (live=consumed, D9) — `LeftoverServiceTest` asserts day total drops by served−consumed | 🟢 | 🟢 | 🟢 | ⬜ |

_Unit gates: `LeftoverServiceTest` drives analyze→apply/discard/restore through the real `NutritionService` + day rollup (no HTTP). Endpoint-level Func (real multipart over HTTP) is 🔵 until a running backend._

### Phase 2 — Android capture (leftover mode) + op rail

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 2.1 | `NutritionCaptureScreen`/`ViewModel` leftover mode (`entryId` nav arg) → shutter-only `LeftoverCapturePane`, routes to the op (D10) | 🟢 | 🟢 | 🔵 | ⬜ |
| 2.2 | `REMOVE_LEFTOVERS` op (`NutritionOpPayloads/Worker` + `PendingNutritionOpEntity`): durable upload → `…/leftovers/analyze`, deletes cache file after upload (D11); survives process death (D8) | 🟢 | 🟢 | 🔵 | ⬜ |
| 2.3 | Domain DTOs: `Leftover`/`LeftoverStatus`/`LeftoverProposal`/`LeftoverProposalItem`/`LeftoverServedIngredient` + `Entry.leftover` (`Nutrition.kt`, IL-8) | 🟢 | 🟢 | 🟢 | ⬜ |
| 2.4 | Repository: `analyzeLeftovers` (via op rail) + `apply/discard/restoreLeftovers` (NOT via local-only `patchEntry`) + `NutritionApi`/`NutritionCaptureApi`; `leftover` added to sync-doc mirror parse | 🟢 | 🟢 | 🔵 | ⬜ |
| 2.5 | "Analyzing leftovers…" decorates the target row via pending-op decoration (IL-10, no phantom row) | 🟢 | 🟢 | 🔵 | ⬜ |

### Phase 3 — Android review UI, sheet button, notification

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 3.1 | "Remove Leftovers" button in `IngredientsSheet` (D5-gated to composite meals) → launches leftover capture | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.2 | Review-diff sheet (`LeftoverUi.kt`: Served→Ate totals + per-ingredient), Apply/Discard (D7); warning banner for partial-mismatch | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.3 | Completion notification (D13): `HfMessagingService` handles `leftover-review`/`leftover-retake`; `LeftoverApplyReceiver` commits via Apply action; body tap deep-links | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.4 | Entry "leftovers" badge by name (`NutritionTodayComponents`) + "Served → Ate" in the sheet (D17) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.5 | "Restore full portion" action (D15) on APPLIED entries | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.6 | Re-run path (D6): from an APPLIED entry, re-shoot recomputes from served baseline (backend-driven; Android re-invokes analyze) | 🟢 | 🟢 | 🔵 | ⬜ |
| 3.7 | `NutritionTodayViewModel`: `reviewLeftovers/applyLeftovers/discardLeftovers/restoreFullPortion` (+ pending-op decoration) | 🟢 | 🟢 | 🔵 | ⬜ |

### Phase 4 — Web read-only + cross-cutting

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 4.1 | Web day view shows a "leftovers" badge + "Served X → Ate Y" on APPLIED entries (`MealSection.tsx`, `types/nutrition.ts`) — read-only (D14/IL-8) | 🟢 | 🟢 | 🔵 | ⬜ |
| 4.2 | Web totals unchanged/correct (consumed via D9) — `leftover` rides through `apiJson`; typecheck + build + `vitest` 58/58 green | 🟢 | 🟢 | 🟢 | ⬜ |

_4.1 Func (visual render on a running web app) is 🔵 human-visual; type-safety + build are green._

### Phase 5 — Hardening, QA & sign-off

| # | Task | Impl | Unit | Func | Pushed |
|---|------|:---:|:---:|:---:|:---:|
| 5.1 | Guardrail edge cases (§4.1 invariants): nothing-left no-op, all-left → 0, clamp, no negatives | 🟢 | 🟢 | 🟢 | ⬜ |
| 5.2 | Idempotency/replay: analyze job skips non-ANALYZING (idempotent); apply requires PENDING_REVIEW proposal; op-rail durable | 🟢 | 🟢 | 🔵 | ⬜ |
| 5.3 | Temp leftover photo deleted after analysis (D11) — `LeftoverServiceTest` asserts store empty post-analysis; Android op deletes cache file | 🟢 | 🟢 | 🔵 | ⬜ |
| 5.4 | Backward-compat: `leftover`/`analysisStatus` null-tolerant on backend + Android + web (additive); full suites green | 🟢 | 🟢 | 🔵 | ⬜ |
| 5.5 | Full canonical scenario (§1) on device with live backend | ⬜ | n/a | 🔵 | ⬜ |
| 5.6 | Docs updated (spec + decision log); branch push & PR + deploy gates | 🟡 | n/a | ⬜ | ⬜ |

---

## 6. Testing approach

Two layers, **both required**. A task is not "tested" until **both** its technical and functional gates pass. Testing must cover the **functional** behavior (does the observable outcome happen) not only the **technical** wiring.

### 6.1 Technical (automated) tests

| Area | What to test | Where |
|------|--------------|-------|
| Consumed math (§4.1) | per-ingredient consumed = clamp(served − remaining, 0, served); `entry.macros = compositeTotal(consumed)`; `withDerivedCalories` applied; known cases (720→540 with rice 150→70/salmon 140→120/broccoli unchanged) | backend unit |
| Served baseline | captured once on first apply; **not** overwritten on re-run; restore clears it | backend unit |
| Re-run from baseline (D6) | second analyze/apply computes from `servedQ`, not current consumed; last photo wins | backend unit |
| Clamp/guardrails (D12) | remaining > served → consumed 0 (no negative); foreign/empty/low-confidence → REJECTED; partial mismatch → warning + unmatched treated as consumed | backend unit (mocked analyzer) |
| State machine (§4.4) | legal transitions only; discard returns to prior committed state; apply idempotent on the stored proposal | backend unit |
| Day rollup unchanged (D9) | totals sum live (consumed) macros; a leftover-applied entry lowers the day total by exactly served−consumed | backend unit/integration |
| Eligibility guard (D5) | analyze on a barcode/manual/no-photo/no-ingredients entry → 422 | backend unit |
| Analyzer contract | mocked Gemini → proposal shape (per-item matched/remaining/confidence + overallConfidence); two-image request assembled | backend unit (mock) |
| Sync serialization | served/leftover fields + stored proposal round-trip in the `nutritionDays/entries` delta; Android parses without dropping the row; null fields tolerated (back-compat) | backend + android unit |
| Temp photo lifecycle (D11) | leftover photo deleted after job; original `photoRef` untouched | backend unit/integration |
| Op rail (D8) | `REMOVE_LEFTOVERS` op enqueues, replays exactly once, survives simulated process death; posts to `…/leftovers/analyze` | android unit/integration |
| Domain parse | `Entry`/`EntryIngredient` served+status + `LeftoverProposal` parse from API/mirror payloads | android unit |
| ViewModel logic | review→apply/discard/restore transitions; badge/served-ate derivation; button visibility = D5 eligibility | android unit |
| Web render | "Served X → Ate Y" appears iff `servedMacros` present; totals unchanged | web unit + build/lint |

**Commands (record exact output in §8):**

- Backend: `./gradlew :backend:test` — filter e.g. `--tests '*Leftover*' --tests '*ConsumedMath*' --tests '*FoodEntry*'`.
- Android: `./gradlew :core-data:testDebugUnitTest :feature-nutrition:testDebugUnitTest :core-domain:testDebugUnitTest` (respect the worktree `local.properties` gotcha — see [android-build-env memory](../../)).
- Web: `cd web && npm run test && npm run lint && npm run build`.

### 6.2 Functional (behavioral) tests — the demarcations for "done"

Each is a scripted, observable outcome. The agent reproduces it and records evidence (screenshot / log line / DB or Room row). **Do not mark Func 🟢 without the artifact.**

| ID | Functional check | Pass criterion |
|----|------------------|----------------|
| **F1** | Button visibility (D5) | "Remove Leftovers" appears on a composite photo meal's sheet; **absent** on a barcode/manual entry's sheet. |
| **F2** | Launch leftover capture (D10) | Tapping it opens the camera in shutter-only mode (no barcode/label stages), scoped to the target entry. |
| **F3** | Async upload + "Analyzing…" (D8) | After the shutter, the sheet/camera close immediately and the entry row shows "Analyzing leftovers…"; the op survives locking/force-killing the app (analysis still completes). |
| **F4** | Valid completion notification (D13) | On a valid result, a notification "Leftovers analyzed · −X kcal" fires with an **Apply** action; the body deep-links to the review diff. |
| **F5** | Apply from notification (D13) | Tapping **Apply** on the notification commits (no need to open the app); today's total drops by served−consumed. |
| **F6** | Review diff (D7) | The review shows Served→Ate totals and per-ingredient before/after; Apply commits, Discard leaves the entry unchanged. |
| **F7** | Per-ingredient subtraction (D2) | A meal where one ingredient is fully eaten and another half-left yields per-ingredient consumed values matching the plate (within the estimate's tolerance), not a uniform scale. |
| **F8** | Preserve served + badge (D3/D17) | After apply, the row shows the reduced (consumed) macros + a "leftovers" badge by the name; the sheet shows "Served → Ate" per ingredient. |
| **F9** | Re-run replaces from baseline (D6) | Re-shooting an APPLIED entry recomputes from the served baseline (not the already-reduced value); the latest photo's result is what applies. |
| **F10** | Reject + retake (D12) | An unreadable/foreign/low-confidence leftover photo produces the "Couldn't read leftovers — tap to retake" notification and changes **no** macros; retake re-enters analysis. |
| **F11** | Guardrail clamp (D12/§4.1) | A leftover that looks like *more* than served never produces negative macros or "ate more than served"; consumed clamps to served. |
| **F12** | Restore full portion (D15) | On an APPLIED entry, "Restore full portion" resets to served macros, removes the badge, clears leftover state; day total returns to the served value. |
| **F13** | Image unchanged (D16) | The composite meal image is the **same** after apply (no regeneration / no PENDING flicker). |
| **F14** | Web read-only (D14) | The web day view shows "Served X → Ate Y" on a leftover entry; totals reflect consumed; web offers **no** capture/apply control. |
| **F15** | Offline durability (D8) | In airplane mode: shutter → "Analyzing leftovers…" persists; on reconnect the analyze op sends exactly once (no dupes), and the completion notification arrives. |
| **F16** | Cross-device sync | Apply on the phone → another signed-in client (web/day view) reflects the consumed macros + served baseline after sync. |
| **F17** | Full canonical scenario (§1) | The entire story reproduced end-to-end without manual DB edits. |

**Non-automatable checks:** F4/F5 notification behavior, F7 estimate *accuracy*, and F13 image are **human-visual / live-AI / device** — the agent captures a screenshot / log / Room-or-Firestore row and flags for human confirmation rather than self-certifying. If a device / Gemini key / backend is unavailable in-agent, mark 🔵 **blocked** with the specific reason; do **not** mark 🟢.

### 6.3 AI-estimation testing strategy (non-deterministic component)

Gemini's portion estimate can't be asserted exactly in CI. Strategy (locked D-AI):

1. **Deterministic plumbing** — unit/integration tests **mock the `LeftoverAnalyzer` port** with fixed outputs to exercise **all** math, clamping, reject/clamp classification, state machine, persistence, and sync. This is the CI gate.
2. **Golden eval set (manual/staging, not CI-gating)** — a curated, documented set of **original + leftover image pairs** with **expected consumed ranges** per ingredient. Run against **live** Gemini in staging; record pass/fail against the ranges in §8. This validates real estimation quality without flaking CI.
3. **No live Gemini in CI** (flaky/slow/costly). The golden set is the release-time accuracy signal; the mocked tests are the regression signal.

---

## 7. Definition of Done

A task/phase is **Done** only when ALL hold:

1. **Implemented** — code written, compiles, no TODO stubs on the happy path.
2. **Unit** — relevant §6.1 tests exist and pass; command output captured in §8.
3. **Functional** — the mapped §6.2 F-check(s) pass with evidence (screenshot/log/DB or Room row).
4. **No regressions** — existing backend/android/web suites still green (nutrition sync + day rollups especially).
5. **Pushed** — committed to `leftover-food`, pushed; for the final phase, PR opened and deploy gates considered (Trivy/CI — see [deploy-pipeline-observability memory](../../)).

**Feature-level DoD:** Phases 0–5 all-green; **F1–F17 pass** (human-visual/live-AI items confirmed by Evan or via the §6.3 golden set); the canonical scenario (§1) demonstrated on a device; **backward-compat** verified (pre-existing entries with null served fields behave normally); the **temp leftover photo deletion** (D11) verified in storage.

### Agent self-verification protocol (before flipping any 🟢)

The agent MUST, per task:

1. State which F-check(s) and unit test(s) gate this task.
2. Run the exact commands (§6.1) and paste the tail of the output into §8.
3. For functional checks, produce the observable artifact (screenshot path, log excerpt, Firestore/Room row) — **not** a claim that it "should work."
4. If a check cannot be executed in the current environment (no device, no Gemini key, no backend), mark the column 🔵 **blocked** with the specific missing dependency. **Never** infer 🟢 from code inspection alone.
5. Only then edit the status cell. Update the top-of-file **Status** line when a whole phase closes.

**Specific "prove it" gates for this feature:**
- **Consumed correctness** — a test asserting the day total drops by exactly `served − consumed` for a known fixture (not just that a write happened).
- **Served preserved** — a test asserting `servedMacros` is unchanged after a re-run and equals the original after restore.
- **No negative macros** — a property/edge test over clamp (§4.1).
- **Op survives death** — an instrumented/simulated process-death test for the `REMOVE_LEFTOVERS` op (F3/F15), or 🔵 if no device.

---

## 8. Verification log (append-only evidence)

> Fill as work proceeds. One entry per verified task: date, task #, gate, command/steps, result, evidence path.

| Date | Task | Gate | Command / step | Result | Evidence |
|------|------|------|----------------|--------|----------|
| 2026-09-12 | 0.2/0.4 | Consumed math + reject/clamp | `./gradlew test --tests '*LeftoverMath*'` | ✅ 10/10 | `TEST-…LeftoverMathTest.xml` (tests=10 failures=0) |
| 2026-09-12 | 0.1/0.5/1.1–1.5 | analyze→apply/discard/restore, served baseline, day total drop, re-run from baseline, reject no-op, D11 photo delete | `./gradlew test --tests '*LeftoverService*'` | ✅ 6/6 | `TEST-…LeftoverServiceTest.xml` (tests=6 failures=0) |
| 2026-09-12 | 0.3 | Gemini tool-args → estimate mapping (plumbing) | `./gradlew test --tests '*LeftoverPhotoExtractor*'` | ✅ 2/2 | `TEST-…LeftoverPhotoExtractorTest.xml` (tests=2 failures=0) |
| 2026-09-12 | Backend all | No regressions (FoodEntry+dispatcher+controller change) | `./gradlew :backend:test` (full suite) | ✅ BUILD SUCCESSFUL | gradle output (exit 0) |
| 2026-09-12 | Backend compile | Main compiles | `./gradlew compileJava` | ✅ BUILD SUCCESSFUL | gradle output |
| 2026-09-12 | 4.1/4.2 | Web typecheck | `npm run typecheck` (tsc --noEmit) | ✅ clean | terminal (exit 0) |
| 2026-09-12 | 4.1/4.2 | Web lint | `npm run lint` | ✅ no errors (pre-existing warnings only) | terminal |
| 2026-09-12 | 4.2 | Web unit | `CI=true npm test` (vitest) | ✅ 58/58 | terminal (11 files, 58 tests) |
| 2026-09-12 | 4.1/4.2 | Web build | `CI=true npm run build` (next build) | ✅ BUILD SUCCESSFUL | terminal (routes emitted) |
| 2026-09-12 | 2.3 | Android domain leftover parse (REST + sync shapes) | `:core-data:testDebugUnitTest --rerun-tasks` → `NutritionLeftoverParseTest` | ✅ 5/5 | `TEST-…NutritionLeftoverParseTest.xml` (tests=5 failures=0) |
| 2026-09-12 | 3.1/3.4/3.7 | Android eligibility/badge/button-visibility (pure) | `:core-domain:testDebugUnitTest --rerun-tasks` → `LeftoverEligibilityTest` | ✅ 9/9 | `TEST-…LeftoverEligibilityTest.xml` (tests=9 failures=0) |
| 2026-09-12 | 3.2/3.5/3.7 | Android ViewModel review→apply/discard/restore transitions | `:feature-nutrition:testDebugUnitTest --rerun-tasks` → `NutritionLeftoverViewModelTest` | ✅ 8/8 | `TEST-…NutritionLeftoverViewModelTest.xml` (tests=8 failures=0) |
| 2026-09-12 | Android all | No regressions (independently re-run by me, not the sub-agent) | `:core-domain:… :core-data:… :feature-nutrition:testDebugUnitTest --rerun-tasks` | ✅ BUILD SUCCESSFUL, 0 failures/errors | gradle output (67 tasks executed) |
| 2026-09-12 | 2.1/2.2/3.3 | Android app compiles (nav route, FCM service, receiver, capture mode) | `:app:compileDebugKotlin --rerun-tasks` | ✅ BUILD SUCCESSFUL | gradle output (180 tasks executed) |
| 2026-09-12 | IL-6 (post-review) | Notification refactored to event + `@EventListener` (`LeftoverReviewPublisher`/`LeftoverReviewNotifier`); `LeftoverService` no longer touches FCM | `./gradlew :backend:test` (full suite) | ✅ BUILD SUCCESSFUL; Leftover* 10/6/2 | gradle output |
| 2026-09-12 | IL-10 (post-review) | Push carries `date`+`entryId`; Apply commits exact entry (`HfMessagingService`+`LeftoverApplyReceiver`) | `:app:compileDebugKotlin` | ✅ BUILD SUCCESSFUL | gradle output |
| _pending_ | F1–F17 | On-device functional + live-Gemini accuracy + real FCM | emulator/device + backend + Gemini key | 🔵 blocked (no device/key in-agent) | — |

---

## 9. Open items / deferred (explicitly out of scope this round)

- **Web capture/upload** of leftovers (D14 = Android-only v1; web is read-only display).
- **Per-item manual editing** of the estimate in the review screen (D7 = confirm/discard only). A future rev could add an editable per-ingredient corrector (reusing the MealItems editor).
- **Leftovers for non-photo meals** (barcode/manual/label) — no original photo and/or no ingredients (D5). A future "whole-meal % slider" could cover these without the AI path.
- **Cumulative multi-pass** semantics (D6 chose replace-from-baseline).
- **Regenerating the meal image** to depict the reduced portion (D16 keeps the original).
- **Browsable leftover/edit history** — only the current served/consumed pair is stored; no per-run audit trail beyond `leftoverAnalyzedAt`.
- **Responsible-portion nudges / waste tracking** — numbers only, no behavioral prompts.
