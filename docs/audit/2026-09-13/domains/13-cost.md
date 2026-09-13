# D13 — Cost Audit (COST)

Auditor: D13 / COST. Date: 2026-09-13. Repo root: `/Users/gte619n/.anvil/worktrees/sess_bef801a7-d3cb-410e-b0ba-535104af1e5c`. Project: `health-fitness-160`, us-central1.

## BLUF

The bill today is **not** driven by Gemini or by users — it is driven by two fixed engineering choices. **(1)** The backend Cloud Run service deploys with `--no-cpu-throttling` (instance-based billing) and the phone's sync traffic (~880 req/day for ~1 user) keeps the 2 vCPU / 2 GiB instance billable **0.64 of every day** (measured live via Cloud Monitoring, 7-day window) → a modeled **~$81/mo of mostly-idle compute for one user**, ~70% of the estimated total bill. The original justification (detached `runAsync` image-generation threads starving under throttling, `backend/cloudbuild.yaml:62-70`) is half-obsolete now that a durable Cloud Tasks job rail exists and is enabled in prod. **(2)** Android release builds run on `E2_HIGHCPU_32` at $0.0624/min, ~10–14 min, on every android-touching merge (~38/30d) → **~$28/mo**, the second-largest line. Gemini today is small (consistent with the Aug 2026 investigation), but the unit economics are lopsided: a single logged meal photo costs ~$0.003 to analyze and **~$0.068 to render a studio image** (uncacheable by design for photo-referenced meals) — image generation is ~90% of the modeled ~$8.3/user-month and becomes the dominant cost line somewhere around 10 active users. There is **no budget, no billing alert, no per-user quota** (Budget API disabled per D9; open signup + no rate limit per SEC-001), so every prospective line is unbounded. Estimated current run rate: **~$115–125/mo [Likely]**, of which perhaps $10 is intrinsic.

**Live reads performed (all read-only, ADC + `x-goog-user-project`):** Cloud Billing projects.billingInfo (billing enabled; account ID withheld from this report), Cloud Monitoring `billable_instance_time` + `request_count` + GCS `total_bytes`, Cloud Run Admin GET service, Firestore Admin databases list + `(default)` listCollectionIds, Artifact Registry repositories list, Cloud Build builds list, `gh api repos/gte619n/tesseta` (`private: false`).

---

## Price sheet (R7 — all fetched 2026-09-13)

| Unit | Price | Source |
|---|---|---|
| gemini-3.8-flash input / output | $0.75 / $3.75 per 1M tok (through 2026-12-31; $1.50/$7.50 from 2027-01-01; batch = 50% off) | https://ai.google.dev/gemini-api/docs/pricing (fetched 2026-09-13) |
| gemini-3.1-pro-preview input / output | $2 / $12 per 1M tok (≤200k prompt) | same page, same date |
| gemini-3.1-flash-image input / image output | $0.50 per 1M tok / $60 per 1M tok ≈ **$0.045 per 512px, $0.067 per 1K image** | same page, same date |
| gemini-3-pro-image input / image output | $2 per 1M tok / $120 per 1M tok ≈ **$0.134 per 1K/2K image** | same page, same date |
| Cloud Run instance-based (Tier 1) | $0.000024 /vCPU-s, $0.0000025 /GiB-s; free tier 240K vCPU-s + 450K GiB-s/mo | cloud.google.com/run/pricing via search summary (verified 2026-09-13) [Likely on free-tier applicability] |
| Cloud Build default pool (e2-standard-2, us-central1) | $0.006 /build-min | cloud.google.com/build/pricing-update (fetched via search 2026-09-13) |
| Cloud Build E2_HIGHCPU_32 (us-central1) | $0.0624 /build-min | same, same date |
| Artifact Registry storage | $0.10 /GB-mo after 0.5 GB free | cloud.google.com/artifact-registry/pricing (fetched via search 2026-09-13) |
| Firestore (Standard, us-central1) | reads $0.03 / writes $0.09 / deletes $0.01 per 100k; storage ≈ $0.146 /GiB-mo; free: 50k reads, 20k writes/day, 1 GiB | firestore pricing via search (fetched 2026-09-13) |
| GCS Standard (us-central1) | $0.020 /GB-mo | cloud.google.com/storage/pricing via search (fetched 2026-09-13) |

---

## 1. Billable service inventory (code-anchored)

| Service | Evidence | Cost posture |
|---|---|---|
| Cloud Run svc `health-fitness-backend` | `backend/cloudbuild.yaml:39-116` — `--memory=2Gi --cpu=2 --no-cpu-throttling --concurrency=16`; live API: `cpuIdle:null`, min 0 / max 100 | **~$81/mo modeled** (see §3) — top line today |
| Cloud Run svc `health-fitness-web` | `web/cloudbuild.yaml:36-63` — no cpu/throttling flags → defaults (request-based) | ~$0 (measured 0.0016 avg billable instances) |
| Cloud Run Jobs: `goals-sustained-reeval` (daily 03:00), `gh-health-check` (daily 04:00), `gh-refresh` (*/6h), `withings-refresh` (*/6h) | `backend/cloudbuild.yaml:140-195`; schedules `infra/scripts/bootstrap-goals-scheduler.sh:28`, `bootstrap-gh-health-check-scheduler.sh:27`, `bootstrap-gh-refresh-scheduler.sh:30`, `bootstrap-withings-refresh-scheduler.sh:29` | negligible (10 short runs/day, inside free tier) |
| Cloud Scheduler ×4 | same bootstrap scripts | ~$0.10/mo (3 free) |
| Cloud Tasks nutrition queue | `backend/cloudbuild.yaml:96` `NUTRITION_JOBS_MODE=cloud-tasks`; `infra/scripts/bootstrap-nutrition-jobs.sh` | free tier (1M ops/mo) |
| Firestore ×2 databases: `production` + `(default)` | live Firestore Admin list; `(default)` populated: `drugs, equipment, exercises, foodCatalog, mealCatalog, refreshTokens, users` (live listCollectionIds) | ops+storage ≈ $1/mo at 1 user; see COST-006 |
| GCS ×12 buckets, 10.98 GB total (live) — largest: `android-releases` 9.72 GB, `nutrition-photos` 0.76 GB (public) | bucket config: `backend/src/main/resources/application.yml:196,202,216,285,297,338,340`; creation `infra/scripts/bootstrap-gcp.sh:60-115` — **zero lifecycle rules anywhere** (grep `lifecycle` in `infra/` = 0 hits) | ~$0.22/mo, unbounded growth |
| Artifact Registry `health-fitness` 14.29 GB, **no cleanup policy** (live: `cleanupPolicies` absent) | pushes: `backend/cloudbuild.yaml:17-22`, `web/cloudbuild.yaml:17-21`, `android/cloudbuild.yaml` | ~$1.38/mo, +~$0.35/mo each month |
| Cloud Build: backend ~8 min default pool, web ~5.7 min default, android ~10–14 min `E2_HIGHCPU_32` | live builds list (12 most recent, all SUCCESS); merge cadence `git log --since='30 days'`: 54 total, 29 backend, 22 web, 38 android | **~$30/mo** (see §4) |
| Cloud Functions `thumbnail-fn` | `infra/scripts/deploy-thumbnail-fn.sh`; gcf-artifacts repo 102 MB | negligible [Likely] |
| KMS (`auth` keyring), Secret Manager (~13 secret mounts at `backend/cloudbuild.yaml:109`) | | ~$1/mo combined |
| Gemini API (single key `gemini_api_key`) | `application.yml:147-152`; 18 call-site files (§2) | ~$5–8/mo at 1 user [Guessing]; the scaling line |
| FCM, Firebase Hosting (`website/`), GitHub Actions + CodeQL | repo is **public** (`gh api`: `private:false`) → Actions/CodeQL **free**; FCM free; Hosting free tier | $0 |

**Structural top-3 at 1 user:** backend Cloud Run instance time (~$81) → Cloud Build (~$30) → Gemini (~$5–8).
**Structural top-3 at 1k users:** Gemini image generation (~$8k+/mo) → Cloud Run compute (shape depends on COST-001 fix; $10s–$1000s) → Firestore ops + GCS photo storage (~$50–150/mo combined). They are different problems: today's bill is fixed overhead; tomorrow's is per-action AI unit economics.

---

## 2. Gemini call-site inventory & unit economics

Complete inventory (grep `client.models.generateContent|generateContentStream`, 18 files, `backend/src/main/java/.../integrations/`):

**Flash text/vision (`gemini-3.8-flash`)** — MealPhotoExtractor (`nutrition/MealPhotoExtractor.java:161,198` — capture analyze + adjust), LeftoverPhotoExtractor, MealDescriptionExtractor, NutritionLabelExtractor, ServingHintExtractor, DrinkExtractor (`nutrition/DrinkExtractor.java:98`), DexaExtractor (`dexa/DexaExtractor.java:89`), BloodTestExtractor, DrugLookupService (`medication/DrugLookupService.java:110-118` — **with GoogleSearch grounding tool**, grounding fee unquantified), GeminiWorkoutCoachClient (`workoutprogram/GeminiWorkoutCoachClient.java:71`), GeminiWorkoutBlockClassifier, GeminiExerciseMetadataEnricher, GeminiExerciseFramePlanner, EquipmentParserService.

**Pro (`gemini-3.1-pro-preview`)** — GeminiGoalChatClient (`goals/GeminiGoalChatClient.java:94`, streaming, healthContext as systemInstruction, model per `application.yml:283`), GeminiWorkoutProgramChatClient (`workoutprogram/GeminiWorkoutProgramChatClient.java:102`, model per `application.yml:318`).

**Image generation (`gemini-3.1-flash-image-preview`)** — GeminiFoodImageGenerator (studio food/meal images, `application.yml:227`), DrugImageGenerator (`medication/DrugImageGenerator.java:330,352`), EquipmentImageService (nano tier, `application.yml:289-290`). **Prod override:** exercise media uses `gemini-3-pro-image-preview` — `backend/cloudbuild.yaml:96` `EXERCISE_MEDIA_MODEL=gemini-3-pro-image-preview` → $0.134/image, 2× flash-image (admin-gated per owner-hardcode).

**Dedup cache:** food/meal images are cached by subject key **only when there is no reference photo** — `core/nutrition/FoodEntryImageService.java:138-149` (`boolean cacheable = refBytes == null...`; "a photo-referenced meal is unique") and `core/nutrition/FoodImageService.java:150-156`. Every photo-based meal capture therefore pays full image generation.

### Per-action cost model

Assumptions [Guessing, bounded]: photo ≈ 1,300 input tokens; prompts sized from source (capture system prompt ≈ 600 tok, `MealPhotoExtractor.java:~60-123`); goals snapshot ≈ 1.5–3k tok ("compact... handful of labelled sections", `core/goals/chat/UserHealthSnapshotService.java:30-36`, 60s cache).

| User action | Calls | In tok × price | Out × price | ≈ Cost |
|---|---|---|---|---|
| Meal photo analysis | 1 flash | 2.2k × $0.75/M | 350 × $3.75/M | **$0.003** |
| Studio meal image (per photo capture, uncacheable) | 1 flash-image | 1.5k × $0.50/M | 1 image ($60/M tok) | **$0.068** |
| Adjust-with-AI (re-sends photo, `MealPhotoExtractor.adjust`) | 1 flash | ~3k | ~400 | $0.004 |
| Remove Leftovers (2 photos) | 1 flash | ~3.5k | ~400 | $0.005 |
| Label scan / describe / drink / serving hint | 1 flash | 1–2.5k | ~300 | $0.002–0.003 |
| Goals chat turn (pro) | 1 pro stream | ~6k × $2/M | ~1k × $12/M | **$0.024** |
| Program designer turn (pro) | 1 pro stream | ~6k × $2/M | ~1.5k × $12/M | **$0.030** |
| Drug add (lookup + image, cached per drug) | flash+grounding, 1 flash-image | — | 1 image | ~$0.07 + grounding |
| DEXA / blood test upload | 1 flash | PDF pages | ~600 | $0.005–0.01 |
| Exercise media regen (admin) | 1 pro-image | — | 1 image | $0.134 |

**Single most expensive routine user action: logging a meal by photo — ~$0.071, of which 96% is the generated studio image, not the analysis.**

### Per user-day and scale projection [Likely at best]

Typical day: 3.5 photo meals, 0.5 adjust/leftover, 2 goals turns/week, 15 program-designer turns/month, misc labels/drinks/recaps.

| Line | $/user-day |
|---|---|
| Capture analysis 3.5 × $0.003 | $0.011 |
| Studio images 3.5 × $0.068 | **$0.238** |
| Adjust/leftover | $0.002 |
| Goals chat (2/7 × $0.024) | $0.007 |
| Program designer (15 × $0.03 / 30) | $0.015 |
| Misc (labels, drinks, coach recap) | $0.005 |
| **Total** | **≈ $0.28/user-day ≈ $8.3/user-month** |

| Users | Gemini $/mo | Note |
|---|---|---|
| 1 | ~$8 (modeled; actual currently lower — Aug 2026 investigation found tesseta near-idle on Gemini [operator memory, citable]) | fixed costs dominate |
| 10 | ~$83 | Gemini ≈ passes Cloud Build; ≈ ties backend compute |
| 1,000 | ~$8,300 | Gemini image-gen is the bill; everything else < 10% |

Prices step up 2× on 2027-01-01 for flash text (fetched page: "$1.50 starting January 1, 2027") — the flash-text lines double but image-gen (already the driver) is priced per token on the image-output meter.

**Unbounded paths (cross-ref SEC-001, [Certain] on unboundedness):** open signup + zero `/api/**` rate limiting means capture/describe/regenerate are scriptable by any Google account; `POST /api/foods/{id}/image/regenerate` triggers image generation on any catalog food with no ownership check (SEC-001 evidence). No budget exists to even observe abuse (COST-003).

---

## 3. Cloud Run: the idle-burn analysis

- Deploy flags: `backend/cloudbuild.yaml:60-75` — `--memory=2Gi --cpu=2 --no-cpu-throttling --concurrency=16`; no `--min-instances` (default 0). Live service confirms `cpuIdle: null` (always-allocated → **instance-based billing**), scaling `{maxInstanceCount:100}`. [Certain]
- Measured (Cloud Monitoring, 7d to 2026-09-13): backend **avg 0.643 billable instances** (daily points 0.60–0.72); web **0.0016**; backend requests 6,155/7d ≈ **880/day**. [Certain]
- Model: 0.643 × 2.592M s/mo = 1.67M instance-s → 3.33M vCPU-s and 3.33M GiB-s. vCPU (3.33M − 240k free) × $0.000024 ≈ $74.2; RAM (3.33M − 450k) × $0.0000025 ≈ $7.2 → **≈ $81/mo** [Likely].
- Mechanism: with instance billing you pay for the whole instance lifetime; steady sync polling every few minutes keeps the instance from ever idling out for ~15h/day. This is not min-instances — it is traffic-shaped keepalive.
- Why the flag exists: detached `CompletableFuture.runAsync` background work (comment at `backend/cloudbuild.yaml:62-70`). But prod now runs the durable Cloud Tasks rail (`NUTRITION_JOBS_MODE=cloud-tasks`, `backend/cloudbuild.yaml:96`), and e.g. MealAdjustmentService already prefers the queue (`core/nutrition/MealAdjustmentService.java:172-177` — runAsync only when queue absent). Residual direct `runAsync` sites that still justify the flag: `core/nutrition/FoodImageService.java:103`, `SavedMealImageService.java:68`, `LeftoverService.java:101`, `MealCaptureService.java:149` (fallback), `MealDescriptionService.java:198`, plus admin-only exercise/equipment generators. [Certain on sites; Likely on "these are the only blockers"]
- Counterfactual: request-based billing at 26k req/mo, sub-second p50 → inside the free tier; **~$75/mo saved** at the cost of cold starts (latency tradeoff is D4's domain).

Cloud Run **Jobs** all reuse the backend image and run minutes/day (10 scheduled executions/day total) — inside free tier, fine as-is.

---

## 4. Cloud Build, CI, waste hunt

- **Cloud Build/mo (modeled from live durations × 30-day merge counts):** android 38 × ~11.7 min × $0.0624 ≈ **$27.7**; backend 29 × ~8.1 min × $0.006 ≈ $1.41; web 22 × ~5.7 min × $0.006 ≈ $0.75. Total ≈ **$30/mo**. Machine type from live builds: `E2_HIGHCPU_32` on `release-android-on-main`; trigger configs in `infra/triggers/{android,backend,web}.yaml`.
- **GitHub Actions/CodeQL: $0** — repo is public (`gh api repos/gte619n/tesseta` → `"private": false`; workflows `.github/workflows/`: android-ci, backend-ci, codeql, terraform-ci, web-ci).
- **Artifact Registry:** 14.29 GB, no cleanup policy (live). ~4 months of pushes (2 tags per merge per component, `backend/cloudbuild.yaml:12-14`) → ~3.5 GB/mo growth → +$0.35/mo compounding. [Certain on size/policy; Likely on growth rate]
- **GCS lifecycle: none anywhere** (`infra/scripts/bootstrap-gcp.sh:60-115` creates buckets with no lifecycle; zero `lifecycle` hits in `infra/`). `android-releases` is already 9.72 GB of accumulated APK/AABs (~$0.19/mo, unbounded); `firestore-exports` currently 0.01 GB (fine today, will grow if DATA-001's backups land without lifecycle); `nutrition-photos` retains every raw meal photo forever by design.
- **Cloud Logging:** default 30-day retention, only `_Required`/`_Default` sinks (D9 live read) — inside the free allotment at this volume. **Fine as-is; no action.** [Certain]
- **`(default)` Firestore DB:** live listCollectionIds returns `drugs, equipment, exercises, foodCatalog, mealCatalog, refreshTokens, users` — it is a populated second copy (also used by local dev per `backend/cloudbuild.yaml:79-81`, and it's where the only TTL policy points, DATA-002). **Cost is negligible** (single-user dataset, ≪1 GiB → cents). The issue is risk, not dollars: confirms D5's H1 — stale `users` PHI + a `refreshTokens` credential collection outside every backup/deletion story. Cross-ref DATA.

---

## 5. Per-user unit economics & the $100/mo line

- **Firestore per user-year:** ~10–15k docs (≈4 meal entries + rollup writes/day, metrics, workouts, sync metadata) × ~2–3 KB ≈ 30–45 MB → < $0.01/user-yr storage; ops ~3k writes + ~10–20k reads/day at heavy sync polling → at 1k users ≈ 150–600M reads/mo ≈ **$45–180/mo** — the sync-poll read fan-out, not storage, is the Firestore line to watch. [Guessing on fan-out multiplier — D4 owns the read-count measurement]
- **Photos per user-year:** 3.5 photos/day × ~2 MB raw + ~100 KB studio ≈ 2.7 GB/user-yr × $0.020/GB-mo → **~$0.65/user-yr, growing linearly forever** (no lifecycle, no downscaling of raws visible in the capture path).
- **When does the design exceed $100/mo?** It already does, at ~1 user (~$115–125 modeled: $81 backend + $30 CI + ~$8 misc/Gemini). The line that gets there is **backend idle compute**, not usage. If COST-001+COST-004 are fixed (base drops to ~$10–15/mo), the design re-crosses $100/mo at **~10–11 active users**, and the line that gets there is **meal-photo studio image generation**.

## 6. Third-party

Nothing paid outside Google is visible in code: Withings API (assumed free tier — unquantified, HYPOTHESES), Google Health webhooks (free), Auth.js/Google OAuth (free), FCM (free). No Sentry/Datadog/analytics SaaS in `web/package.json` or Android catalogs (per D9).

## 7. Cost of proposed migrations from other domains

The migrations proposed so far are cheap in run-rate terms. SUP (Boot 3.5→4.x, 3–5 days) adds only transient Cloud Build minutes on the default pool (~$0.05/build) and re-baselines Trivy — no recurring cost. D9's observability package is essentially free at this scale: Error Reporting, log-based metrics, alert policies, uptime checks (2), and email channels all sit inside monitoring free allotments; Crashlytics is free; a web Sentry DSN fits the free tier — budget ≈ $0–5/mo, versus the demonstrated cost of *not* having it (weeks of dead deploys, and no way to see a Gemini abuse spike, which makes it a cost-control as much as an ops control). DATA-001's backup schedule adds Firestore backup storage (GB-months of a tiny dataset — cents/mo today) plus export-bucket growth that should get the lifecycle rule from COST-005. The android sqlcipher swap and firestore.rules deploys are $0. None of these move the bill more than a few dollars; COST-001/COST-004 fund all of them ~20× over.

---

## Findings

### COST-001 — Backend `--no-cpu-throttling` + sync keepalive = ~$81/mo of idle compute at 1 user (top cost line)
- **Severity:** high · **Confidence:** certain (measurement) / likely (dollar model)
- **Evidence:** `backend/cloudbuild.yaml:60-75` (`--cpu=2 --memory=2Gi --no-cpu-throttling`); live Cloud Run GET (`cpuIdle:null`, min 0); Cloud Monitoring 7d: 0.643 avg billable instances, 6,155 req/7d; Cloud Run price sheet above (fetched 2026-09-13). Rationale-comment vs. current state: durable rail enabled `backend/cloudbuild.yaml:96`; queue-preferred pattern `core/nutrition/MealAdjustmentService.java:172-177`; residual `runAsync` at `core/nutrition/FoodImageService.java:103`, `SavedMealImageService.java:68`, `LeftoverService.java:101`, `MealCaptureService.java:149`, `MealDescriptionService.java:198`.
- **Impact:** ≈ $75/mo waste at 1 user; under load with instance billing at concurrency 16 this scales as instances × wall-time, not work done.
- **Effort_days:** 1.5 · **Autonomy:** agent-reviewed (prod deploy-flag change; needs a canary eye on image-gen completion)
- **Null_option_cost:** ~$900/yr now; grows with any traffic increase.
- **Prompt:** "Route the remaining detached background work onto the existing durable NutritionJobQueue rail (FoodImageService.generateNow, SavedMealImageService.generateNow, LeftoverService.runAnalysis, MealCaptureService fallback, MealDescriptionService fallback — mirror MealAdjustmentService.java:172-177's queue-preferred pattern; add job types if missing). Then remove `--no-cpu-throttling` from backend/cloudbuild.yaml (keep 2Gi/2cpu) so the service bills request-based. Verify via the existing day-read reconcile sweep + a canary capture that studio images still reach READY. Do not touch min-instances."

### COST-002 — Studio image generation dominates per-user Gemini economics (~$0.068/meal, ~90% of ~$8.3/user-mo); uncacheable for photo captures by design
- **Severity:** high (prospective — this is what makes multi-user expensive) · **Confidence:** likely (prices certain; token/usage shape modeled)
- **Evidence:** `core/nutrition/FoodEntryImageService.java:138-151` ("a photo-referenced meal is unique" → cache bypassed every capture); model `application.yml:227` (`gemini-3.1-flash-image-preview`); price $60/M image-out tok ≈ $0.067/1K image (ai.google.dev/gemini-api/docs/pricing, fetched 2026-09-13); analysis call is ~$0.003 by comparison (`MealPhotoExtractor.java:154-161`).
- **Impact:** ~$8.3/user-mo at design usage → ~$8.3k/mo at 1k users; 20× cheaper without the studio image. The image is cosmetic (the user already has their own photo of the meal).
- **Effort_days:** 1–2 · **Autonomy:** agent-reviewed (product call on the UX)
- **Null_option_cost:** none at 1 user; blocks unit economics at 10+; unbounded when combined with SEC-001.
- **Prompt:** "In backend nutrition: make photo-captured meal entries default their display image to the user's own capture photo (already stored in the nutrition bucket) instead of generating a studio image; keep GeminiFoodImageGenerator for described (photo-less) meals where the name-keyed cache works (FoodEntryImageService.java:138-151), and put studio generation for photo meals behind a per-user setting default-off. Preserve the READY/PENDING state machine and self-heal sweep. Add a counter log line per generation for future cost attribution."

### COST-003 — No budget, no billing alert, no per-user quota: every cost path is unbounded and unobserved
- **Severity:** high · **Confidence:** certain
- **Evidence:** Budget API `SERVICE_DISABLED` (D9 live read, 2026-09-13); zero alert policies/notification channels (D9); open signup + no `/api/**` rate limit (SEC-001, `auth/UserProvisioningFilter.java:32-34`, `application.yml:119-121` `/v1`-only limiter); Gemini spend has no per-user metering anywhere in the codebase (no counter, no quota check — domain-wide grep).
- **Impact:** a scripted abuser (or a client-side retry bug) can run image generation in a loop; first notice is the invoice. R6: the do-nothing cost is the tail risk, not the mean.
- **Effort_days:** 0.5 (budget+alert) — SEC-001 owns the limiter (2d) · **Autonomy:** operator-required (billing-account IAM; agent can produce the terraform)
- **Null_option_cost:** expected-case $0; tail-case a four-figure surprise invoice with weeks of latency before discovery.
- **Prompt:** "Enable billingbudgets.googleapis.com and add infra/terraform/budget.tf: a google_billing_budget on the project's billing account with monthly amount $50, threshold rules at 0.5/0.9/1.0 (and 1.0 forecasted), wired to a google_monitoring_notification_channel (email, variable). Document in infra/terraform/README.md that budget alerts do not stop spend — pair with SEC-001's rate limiter."

### COST-004 — Android release build on every android-touching merge at E2_HIGHCPU_32 ≈ $28/mo (2nd-largest line)
- **Severity:** medium · **Confidence:** certain (machine/duration/cadence measured) / likely ($)
- **Evidence:** live Cloud Build list: `release-android-on-main` on `E2_HIGHCPU_32`, 8.0–13.9 min, all recent builds; $0.0624/min (price sheet); 38 android-touching merges/30d (`git log --since='30 days ago' -- android`); trigger `infra/triggers/android.yaml`, `android/cloudbuild.yaml`.
- **Impact:** ~$28/mo for release artifacts of an app with ~1 install; also feeds the 9.7 GB `android-releases` bucket.
- **Effort_days:** 0.5 · **Autonomy:** high
- **Null_option_cost:** ~$340/yr.
- **Prompt:** "Change infra/triggers/android.yaml so release-android-on-main runs on tags (or manual approval) instead of every android-touching merge to main, keeping the free GitHub Actions android-ci.yml as the per-merge signal; alternatively drop the machineType to E2_HIGHCPU_8 and measure. Do not change backend/web triggers (deploys must stay per-merge)."

### COST-005 — No Artifact Registry cleanup policy (14.3 GB) and no GCS lifecycle rules on any bucket (android-releases 9.7 GB)
- **Severity:** low · **Confidence:** certain
- **Evidence:** live AR repositories list: `health-fitness` sizeBytes 14,289,851,365, `cleanupPolicies` absent; grep `lifecycle` across `infra/` = 0 hits; `bootstrap-gcp.sh:60-115` creates buckets bare; live bucket sizes (android-releases 9.72 GB, cloudbuild 0.08 GB).
- **Impact:** ~$1.6/mo today, compounding ~$0.4/mo/mo; also operational clutter (every image since May).
- **Effort_days:** 0.5 · **Autonomy:** high
- **Null_option_cost:** ~$20 next 12 months and rising; trivial but pure waste.
- **Prompt:** "Add to infra: (1) an Artifact Registry cleanup policy on the health-fitness repo — keep most-recent 10 versions per package + anything tagged `latest`, delete untagged older than 30d (gcloud artifacts repositories set-cleanup-policies, committed as a policy JSON + bootstrap script step in bootstrap-gcp.sh); (2) GCS lifecycle JSON: android-releases delete age>90d, `*_cloudbuild` delete age>30d, firestore-exports delete age>60d. Do NOT add lifecycle to user-data buckets (nutrition-photos, dexa, blood-tests, gym-photos, medication-images) — retention there is a DATA-domain policy decision."

### COST-006 — `(default)` Firestore DB is a live populated second copy (users + refreshTokens): cost negligible, risk is DATA's
- **Severity:** low (as a cost item) · **Confidence:** certain
- **Evidence:** live databases list (both exist; `(default)` created 2026-05-20); live listCollectionIds on `(default)`: `drugs, equipment, exercises, foodCatalog, mealCatalog, refreshTokens, users`; copy scripts `infra/scripts/copy-default-to-production-firestore.sh` / `copy-production-to-default-firestore.sh`; local dev intentionally targets it (`backend/cloudbuild.yaml:79-81`); the repo's only TTL policy targets it (`infra/terraform/firestore_ttl.tf:33`, DATA-002).
- **Impact:** storage cost is cents (single-user dataset ≪1 GiB free tier). The material issue — stale PHI + credential material outside backup/deletion scope — is DATA's (confirms D5 H1 with live evidence).
- **Effort_days:** 0.25 (decide + purge or document as the dev DB with no user data) · **Autonomy:** operator-required (data deletion)
- **Null_option_cost:** ~$0 in dollars; nonzero in privacy/regulated exposure (see DATA).
- **Prompt:** "Operator decision: either purge user-derived collections (users, refreshTokens) from the (default) database keeping only seed/catalog data for local dev, or promote it to an explicitly documented dev environment with synthetic data. Then align infra/terraform/firestore_ttl.tf to database=production (DATA-002)."

### COST-007 — Exercise media pinned to the 2×-price pro image model in prod
- **Severity:** low · **Confidence:** certain
- **Evidence:** `backend/cloudbuild.yaml:96` `EXERCISE_MEDIA_MODEL=gemini-3-pro-image-preview` overriding `application.yml:309` default (`gemini-3.1-flash-image-preview`); $0.134 vs $0.067/image (price sheet). Admin/owner-gated (flag-image hardcode), batch backfill job exists (`jobs/BackfillExerciseMediaJob.java`).
- **Impact:** only matters during exercise-catalog media backfills (hundreds of images → ~$13/100 images extra); a deliberate quality choice, cheap insurance to document it.
- **Effort_days:** 0 (accept) · **Autonomy:** n/a
- **Null_option_cost:** ≈$0 steady-state — do-nothing is fine (R6); flagged so the next 1,000-image backfill is a conscious $134-vs-$67 choice.
- **Prompt:** (none — note in ops docs if a large backfill is planned.)

---

## HYPOTHESES (invisible without billing/console access)

- **H1:** The actual invoice. No billing export/BigQuery sink is configured (D9: only `_Required`/`_Default` log sinks; Budget API disabled), and the Billing API exposes account linkage, not line items. All dollar totals here are models; the ~$115–125/mo estimate could be off by ±30% (e.g., committed-use discounts, credits, or an account-level console budget I can't see).
- **H2:** Google Search grounding fee for `DrugLookupService` — priced separately per grounded request on the Gemini pricing page's tools section, which the fetch didn't return; unquantified. Low volume (drug adds), but nonzero.
- **H3:** Image input/output token counts assumed (~1.3k tok/photo in; image out priced at the page's own ≈$0.067/1K-image equivalence). Real capture photos at 2–4 MB may tokenize higher; batch API (50% off) is unused and is a further lever.
- **H4:** Actual current Gemini spend — modeled ~$8/user-mo assumes design usage; the Aug 2026 investigation (operator memory) found tesseta near-idle, so today's real number is likely $1–5/mo.
- **H5:** Withings API commercial terms at scale (free tier limits unknown); Google Health passive.
- **H6:** Free-tier applicability of Cloud Run's 240k vCPU-s to instance-based billing was taken from a search-verified summary of cloud.google.com/run/pricing, not the raw table — if it applies only to request-based, COST-001's number rises by ~$6/mo (direction favors the finding).
- **H7:** `thumbnail-fn` Cloud Functions invocation volume (assumed negligible; gcf-artifacts is only 102 MB).
- **H8:** Whether any android Cloud Build runs happen off-main (PR builds would multiply COST-004); the 12 most recent builds were all main-trigger deploy/release builds, suggesting not.

---

```json
[
  {"id":"COST-001","title":"Backend --no-cpu-throttling + sync keepalive = ~$81/mo idle compute at 1 user (top cost line)","severity":"high","confidence":"certain-measurement/likely-dollars","evidence":["backend/cloudbuild.yaml:60-75","live Cloud Run GET cpuIdle:null min=0","Cloud Monitoring 7d avg 0.643 billable instances; 6155 req/7d","cloud.google.com/run/pricing fetched 2026-09-13: $0.000024/vCPU-s $0.0000025/GiB-s","residual runAsync: core/nutrition/FoodImageService.java:103, SavedMealImageService.java:68, LeftoverService.java:101, MealCaptureService.java:149, MealDescriptionService.java:198; queue-preferred pattern MealAdjustmentService.java:172-177; rail enabled backend/cloudbuild.yaml:96"],"impact":"~$75/mo waste at 1 user; instance-billing scales with wall-time not work","effort_days":1.5,"autonomy":"agent-reviewed","null_option_cost":"~$900/yr, grows with traffic","prompt":"Route remaining detached runAsync background work onto the durable NutritionJobQueue rail (mirror MealAdjustmentService queue-preferred pattern), then remove --no-cpu-throttling from backend/cloudbuild.yaml so billing is request-based; canary-verify studio images reach READY; do not touch min-instances."},
  {"id":"COST-002","title":"Studio image generation dominates Gemini unit economics (~$0.068/meal photo, ~90% of ~$8.3/user-mo), uncacheable for photo captures","severity":"high","confidence":"likely","evidence":["core/nutrition/FoodEntryImageService.java:138-151 photo-referenced = cache bypass","application.yml:227 gemini-3.1-flash-image-preview","ai.google.dev/gemini-api/docs/pricing fetched 2026-09-13: $60/M image-out tok ~= $0.067/1K image","analysis call ~$0.003: MealPhotoExtractor.java:154-161"],"impact":"~$8.3k/mo at 1k users at design usage; image is cosmetic (user already has own photo)","effort_days":2,"autonomy":"agent-reviewed","null_option_cost":"none at 1 user; breaks unit economics at 10+; unbounded with SEC-001","prompt":"Default photo-captured entries to the user's own capture photo as display image; keep studio generation only for described (photo-less) meals where the name-keyed cache works, behind a default-off setting for photo meals; preserve READY/PENDING state machine and self-heal sweep."},
  {"id":"COST-003","title":"No budget, no billing alert, no per-user Gemini quota: all cost paths unbounded and unobserved","severity":"high","confidence":"certain","evidence":["Budget API SERVICE_DISABLED (D9 live read 2026-09-13)","zero alert policies/channels (D9)","SEC-001: open signup UserProvisioningFilter.java:32-34; /v1-only limiter application.yml:119-121","no per-user Gemini metering anywhere (domain grep)"],"impact":"abuse or a retry bug is discovered via the invoice, weeks late","effort_days":0.5,"autonomy":"operator-required","null_option_cost":"expected $0; tail-case four-figure invoice","prompt":"Enable billingbudgets API; add infra/terraform/budget.tf with a $50/mo google_billing_budget, thresholds 0.5/0.9/1.0 + 1.0 forecasted, email notification channel; document that budgets alert, not stop — pair with SEC-001 limiter."},
  {"id":"COST-004","title":"Android release build per android-touching merge on E2_HIGHCPU_32 ~= $28/mo (2nd-largest line)","severity":"medium","confidence":"certain-shape/likely-dollars","evidence":["live Cloud Build list: release-android-on-main E2_HIGHCPU_32 8-14 min","cloud.google.com/build/pricing-update fetched 2026-09-13: $0.0624/min us-central1","git log --since 30d -- android = 38 merges","infra/triggers/android.yaml"],"impact":"~$28/mo of release builds for ~1 install; feeds 9.7GB android-releases bucket","effort_days":0.5,"autonomy":"high","null_option_cost":"~$340/yr","prompt":"Make release-android-on-main tag- or approval-triggered (GitHub Actions android-ci stays the per-merge signal), or drop machineType to E2_HIGHCPU_8 and measure; leave backend/web per-merge deploys untouched."},
  {"id":"COST-005","title":"No Artifact Registry cleanup policy (14.3GB) and zero GCS lifecycle rules (android-releases 9.7GB)","severity":"low","confidence":"certain","evidence":["live AR list: health-fitness sizeBytes 14289851365, cleanupPolicies absent","grep lifecycle infra/ = 0 hits; bootstrap-gcp.sh:60-115","live bucket sizes: android-releases 9.72GB","cloud.google.com/artifact-registry/pricing fetched 2026-09-13: $0.10/GB-mo"],"impact":"~$1.6/mo compounding ~$0.4/mo/mo","effort_days":0.5,"autonomy":"high","null_option_cost":"~$20 next 12mo, rising","prompt":"Add AR cleanup policy (keep 10 recent + latest tag, delete untagged >30d) and GCS lifecycle for android-releases (>90d), cloudbuild (>30d), firestore-exports (>60d); do NOT touch user-data buckets (DATA owns their retention)."},
  {"id":"COST-006","title":"(default) Firestore DB is a live populated second copy (users + refreshTokens) — cost negligible, risk cross-ref DATA","severity":"low","confidence":"certain","evidence":["live listCollectionIds (default): drugs,equipment,exercises,foodCatalog,mealCatalog,refreshTokens,users","infra/scripts/copy-default-to-production-firestore.sh","backend/cloudbuild.yaml:79-81 local dev targets (default)","infra/terraform/firestore_ttl.tf:33"],"impact":"cents of storage; confirms DATA H1 (stale PHI + credential collection outside backup/deletion scope)","effort_days":0.25,"autonomy":"operator-required","null_option_cost":"~$0 dollars; nonzero regulated-privacy exposure (DATA)","prompt":"Operator: purge user-derived collections (users, refreshTokens) from (default) keeping seed/catalog data for dev, or document it as a synthetic-data dev env; align firestore_ttl.tf to database=production."},
  {"id":"COST-007","title":"Exercise media pinned to 2x-price pro image model in prod (deliberate; document)","severity":"low","confidence":"certain","evidence":["backend/cloudbuild.yaml:96 EXERCISE_MEDIA_MODEL=gemini-3-pro-image-preview","application.yml:309 default flash-image","pricing fetched 2026-09-13: $0.134 vs $0.067/image"],"impact":"only during catalog media backfills (~+$67 per 1000 images)","effort_days":0,"autonomy":"n/a","null_option_cost":"~$0 steady-state; do-nothing fine (R6)","prompt":""}
]
```
