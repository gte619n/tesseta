# IMPL-LEFTOVER-01 — Implementation Decision Log

Append-only log of implementation-time decisions made while building the Remove Leftovers feature (spec: [IMPL-LEFTOVER-01-remove-leftovers.md](IMPL-LEFTOVER-01-remove-leftovers.md)). Each entry (IL-n) records a choice made without stopping to ask, so it can be reviewed and tweaked afterward.

Legend: **Choice** = what was decided · **Why** = rationale · **Revert** = how to change it if the review disagrees.

---

<!-- entries appended below -->

### IL-1 — Nest all leftover state in one `Leftover` record on `FoodEntry`
**Choice:** Instead of adding 3–4 separate columns to `FoodEntry` (servedMacros, servedQuantity-per-ingredient, leftoverStatus, analyzedAt) — which would force every one of the ~13 `new FoodEntry(...)` call sites to grow — add a **single nullable `Leftover leftover`** field. `Leftover` holds `{status, servedMacros, servedIngredients, proposal, analyzedAt}`. The as-served per-ingredient baseline lives as `servedIngredients` (a full `List<CompositeIngredient>` snapshot) inside `Leftover`, so **`CompositeIngredient` is untouched (zero ripple there)**.
**Why:** Minimizes constructor churn, keeps the served baseline cohesive, and `leftover == null` cleanly means "no leftover activity" (spec D9's "presence = applied", generalized to presence-of-status).
**Revert:** If review prefers flat columns, split `Leftover` into fields; the ser/de and service already treat it as one blob so the change is localized.

### IL-2 — Live `entry.macros`/`entry.ingredients` = consumed; `Leftover.servedIngredients` = baseline (spec D9)
**Choice:** After apply, the entry's live `macros` and `ingredients` carry the **consumed** values; the as-served snapshot is preserved in `leftover.servedMacros` + `leftover.servedIngredients`. Day rollups/sync/web read the live (consumed) macros unchanged.
**Why:** Exactly spec D9 — zero changes to every existing totals reader.
**Revert:** Swap the two in `LeftoverService.apply`/`restore`.

### IL-3 — Served baseline captured at first `analyze`, never overwritten; re-run recomputes from it
**Choice:** On the FIRST analyze (entry has no `leftover`), snapshot the current macros/ingredients into `leftover.servedMacros/servedIngredients`. Subsequent analyze/apply (re-run) keep that baseline and recompute consumed from it (spec D6). `restore` clears `leftover` back to null and live=served.
**Why:** Idempotent re-run, last-photo-wins, matches D6/D15.
**Revert:** Change the "if leftover==null capture baseline" guard in `LeftoverService.beginAnalyze`.

### IL-4 — Leftover job reuses existing `NutritionJob` shape (no new fields)
**Choice:** New `NutritionJobType.LEFTOVER_ANALYSIS` + `NutritionJob.leftoverAnalysis(userId,date,entryId,leftoverPhotoRef,mime)` reusing `ref`=leftover temp photo, `mime`=leftover mime. The **original** photo is read from the entry's own `photoRef` inside the job — so only one ref rides the queue.
**Why:** No change to the `NutritionJob` record; original photoRef is already on the entry.
**Revert:** N/A (additive).

### IL-5 — Discard the leftover photo after analysis via a new `MealPhotoStore.delete(ref)` (spec D11)
**Choice:** Park the leftover photo through the existing `MealPhotoStore.store()`, then delete it once the job finishes (success or reject) via a new `delete(String ref)` port method implemented in `MealPhotoStorage` (parse object name from the public URL, `storage.delete`). Best-effort; never fails the job.
**Why:** Honors D11 (only the original photo is retained) while reusing the capture storage path.
**Revert:** Make `delete` a no-op to retain leftover photos.

### IL-6 — Completion notification sent inline from the service via the core `FcmSender` port (spec D13)
**Choice:** `LeftoverService` injects the core `FcmSender` + `FcmTokenRepository` (both already core ports) and, on job completion, sends a user-visible notification: valid → title "Leftovers analyzed", body "−X kcal · tap to review", `data{type:"leftover-review", date, entryId, action:"apply"}`; rejected → "Couldn't read leftovers", "Tap to retake", `data{type:"leftover-retake",...}`. Also always fires `syncNotifier.changed(...)` so the PENDING_REVIEW state syncs in-app regardless of the push.
**Why:** Reuses the exact reconnect-notifier pattern; keeps push failure off the job path; in-app path still works if the push is missed.
**Revert:** Extract to an event + `LeftoverReviewNotifier` listener if review prefers the event-driven style.

### IL-7 — Reject thresholds (spec D12/§4.2) as named constants
**Choice:** `LeftoverMath` rejects when `overallConfidence < 0.35` OR hard-mismatch (no served item maps to any analyzer item / analyzer returned empty). Otherwise clamp each consumed to `[0, served]`; unmatched served items are treated as **fully consumed** (remaining 0) with a `warning` note. Threshold is a constant for easy tuning.
**Why:** Deterministic, unit-testable boundary matching D12; conservative default confidence gate.
**Revert:** Tune `MIN_CONFIDENCE` / matching rule in `LeftoverMath`.

### IL-8 — Android domain: nest leftover state in one nullable `Leftover` on `Entry` (mirrors IL-1)
**Choice:** Added `Entry.leftover: Leftover? = null` plus `Leftover`/`LeftoverStatus`/`LeftoverProposal`/`LeftoverProposalItem`/`LeftoverServedIngredient` to `core-domain/.../nutrition/Nutrition.kt`, mirroring the backend `EntryResponse.leftover` object exactly. `LeftoverServedIngredient` is a superset (name/servingGrams/servingLabel/quantity/macros/macrosPer100g, all nullable) so it parses BOTH the slim REST `LeftoverIngredientDto` AND the fuller `CompositeIngredient` shape the sync delta serializes into `servedIngredients`. Eligibility/derivation live as pure `Entry` computed props (`isLeftoverEligible`, `isAnalyzingLeftovers`, `hasLeftoverReview`, `hasAppliedLeftover`).
**Why:** Additive + nullable = every existing constructor/parse path unchanged (back-compat); one cohesive blob matches the backend's IL-1 nesting; pure props keep button-visibility/badge logic unit-testable off the ViewModel.
**Revert:** Flatten `Leftover` into fields on `Entry`; ser/de is Moshi-automatic so the change is localized.

### IL-9 — `REMOVE_LEFTOVERS` op mirrors `CAPTURE_PHOTO` (JPEG cache file + `{targetEntryId}` payload)
**Choice:** New `NutritionOpType.REMOVE_LEFTOVERS` on the existing durable rail: the leftover JPEG is parked in a cache file (`jpegPath`, exactly like capture) and a tiny `RemoveLeftoversPayload(targetEntryId)` rides `payloadJson`; the worker POSTs to `…/leftovers/analyze` via `NutritionCaptureRepository.analyzeLeftovers` (multipart `photo` part, copied from `captureMeal`) then `fillDayAndSignal`. Unlike capture (which hands its JPEG to the preview store), the worker **deletes** the leftover cache file on success — the leftover photo is never shown or reused (spec D11). `mealWire` is unused (left ""); the op targets an existing entry.
**Why:** Maximal reuse of the process-death-durable rail; a leftover photo has no preview lifetime so it's dropped immediately.
**Revert:** N/A (additive op type + payload).

### IL-10 — "Analyzing leftovers…" decorates the EXISTING target row (no synthetic row); FCM Apply resolves the target from the synced mirror
**Choice:** (a) A `REMOVE_LEFTOVERS` op targets an existing composite entry, so `withPendingOps` does NOT append a synthetic row for it — instead it decorates that entry's `leftover.status = ANALYZING` (via `NutritionOpEntity.targetEntryId()`, a Moshi-free string scan so the pure merge stays JVM-testable), giving instant "Analyzing leftovers…" feedback before the backend/sync lands. (b) The backend completion push carries only `{type}` (confirmed: `LeftoverService` sends `Map.of("type", type)` — no date/entryId). So `HfMessagingService` on `leftover-review` enqueues a sync pull THEN posts a notification whose **body tap** opens the app (the synced PENDING_REVIEW entry surfaces its in-app "Review leftovers" affordance) and whose **Apply** action fires `LeftoverApplyReceiver`, which resolves the pending-review target from the freshly-synced mirror (`NutritionRepository.findLeftoverPendingReview()`) and calls `applyLeftovers`. `leftover-retake` posts a "tap to retake" notification (body tap opens the app). Both are robust because the app always also receives the sync ping, so the leftover state is in the mirror regardless of the push.
**Why:** Honors spec D13 (Apply action + deep-link) despite the push lacking ids; keeps the leftover state as the single synced source of truth; avoids a phantom extra row for an in-place quantity reduction.
**Revert:** If the backend later adds date/entryId to the push data, read them directly in the receiver instead of scanning the mirror; drop `findLeftoverPendingReview`.

### IL-11 — Web read-only shows a "leftovers" badge + "Served → Ate" line on the entry row only
**Choice:** Web (spec D14 read-only) surfaces the leftover on the day-view meal row (`web/components/nutrition/MealSection.tsx`): a small "leftovers" chip beside the name and a "Served {X} → Ate {Y} kcal" sub-line, shown only when `entry.leftover.status === "APPLIED"`. The `leftover` field flows through automatically because `getDay` uses `apiJson<NutritionDay>` (no field-by-field mapping) — no api-client change needed. Per-ingredient served→ate on web was NOT added (kept minimal; the row line conveys the net effect).
**Why:** Minimal, low-risk read-only surface that matches D14/D17 without a web capture flow.
**Revert:** Remove the two JSX blocks; the types stay harmless.
**Env note:** `web/node_modules` in this worktree was symlinked to a stale sibling worktree's pnpm store, so `tsc` was missing. Fixed with `CI=true pnpm install --prefer-offline` (pnpm project). Not a code change — just worktree setup.

---

## Post-implementation review (interview 2026-09-12)

Walked the log with Evan. Outcomes:

- **IL-1 / IL-8 (nested `Leftover` record)** — CONFIRMED, keep as built.
- **IL-2 (live macros = consumed)** — CONFIRMED (it's spec D9).
- **IL-3 (baseline captured once; reject/discard reverts to prior committed state)** — CONFIRMED, keep as built.
- **IL-4 (job reuses `NutritionJob` shape)** — CONFIRMED (internal).
- **IL-5 / D11 (discard leftover photo after analysis)** — CONFIRMED, keep discarding (declined retaining it).
- **IL-7 (reject gate `MIN_CONFIDENCE = 0.35`)** — CONFIRMED for now; tune against the golden eval set (spec §6.3) once live-Gemini output exists.
- **IL-11 (web row-level only)** — CONFIRMED; no per-ingredient web view, no web capture flow.

**REVISED (re-implemented after the review):**

- **IL-6 → event + listener.** Replaced the inline `FcmSender` call in `LeftoverService` with an event: `LeftoverService` now publishes a `LeftoverReviewReadyEvent` via a new `LeftoverReviewPublisher` (core wrapper over `ApplicationEventPublisher`, mirrors `SyncChangeNotifier`); a new `LeftoverReviewNotifier` (`api/nutrition`, `@EventListener`) sends the FCM push — matching `GoogleHealthReconnectNotifier`/`WithingsReconnectNotifier`. `LeftoverService` no longer depends on `FcmSender`/`FcmTokenRepository`. Re-verified: `./gradlew :backend:test` full suite green (LeftoverServiceTest 6/6, LeftoverMathTest 10/10, LeftoverPhotoExtractorTest 2/2).
- **IL-10 → push carries `date`+`entryId`.** `LeftoverReviewNotifier` now includes `{type, date, entryId}` in the FCM data. Android: `HfMessagingService` threads them onto the Apply broadcast as extras; `LeftoverApplyReceiver` commits that EXACT entry (falls back to the mirror-resolve only if extras are absent). Removes the multiple-pending-review ambiguity. Re-verified: `:app:compileDebugKotlin` green.
