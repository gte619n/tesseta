# IMPL-SAFETY-PERF-01 — Decision Log

> Implementation of the 2026-09-13 audit's week-one safety batch + IMPL-PERF-01
> workstreams A and B. Branch: `feature/audit-remediation`. Every non-obvious
> decision made during autonomous implementation is recorded here for operator
> review. Format: `DEC-NNN — decision — rationale — reversal`.

## Orchestration decisions (lead agent)

**DEC-001 — Infra & GitHub changes are delivered as code + apply-scripts + docs, not applied live.**
Rationale: GCP ADC is unavailable this session (`gcloud auth application-default
print-access-token` fails), so Firestore/GCS/monitoring changes cannot be
applied. For symmetry and safety, the GitHub branch ruleset is likewise NOT
auto-applied (`gh` works, but a misconfigured ruleset can lock the repo, and the
workflow early-exit refactor must land on `main` before required-check gating is
safe). All such changes are committed as terraform / scripts / workflow edits
with the exact apply command documented. Reversal: operator runs the documented
apply commands (or I apply once ADC is restored).

**DEC-002 — SEC-012 (private meal-photo bucket) is de-scoped from this batch into its own spec `IMPL-SEC-01-private-media-buckets.md`.**
Rationale: clients consume raw public GCS URLs
(`MealPhotoStorage.publicUrl:110`, `FoodImageStorage.publicUrl:253`, and the
drug/equipment/exercise/location siblings), and `photoRef` (a public URL) is
persisted on `FoodEntry` and re-read server-side. A naive bucket flip breaks
image loading across 6 buckets and 3 clients. The correct fix (backend
signed-URL/proxy serving + client migration + persisted-URL migration) is a
multi-day cross-client project that exceeds a "safety batch" and carries prod
breakage risk. Delivered as a sequenced spec instead of a harmful flip.
Reversal: prioritize IMPL-SEC-01 next; nothing here blocks it.

**DEC-003 — Work is partitioned across three concurrent agents by disjoint file tree to avoid edit/build collisions: (1) backend code+build, (2) infra authoring, (3) CI governance.**
Rationale: only the backend agent runs Gradle (avoids daemon/build-dir
collisions); `backend/`, `infra/`, `.github/` are non-overlapping. Agents return
their decisions; the lead agent consolidates them below (no concurrent writes to
this file). Reversal: n/a (process).

**DEC-004 — SOTA-002 (Gemini image model preview→GA) treated as a within-family de-risking change, not a new-model introduction requiring an ADR.**
Rationale: `gemini-3.1-flash-image-preview` → `gemini-3.1-flash-image` is the
GA of the same model, and the audit (SOTA-002) fetched evidence the preview is
past its earliest-shutdown date. CLAUDE.md's "no new Gemini model without an
ADR" targets new families/providers, not GA-promotion of an already-sanctioned
model. The backend agent re-verifies the GA id resolves before committing.
Reversal: revert the 4 default-value edits in `application.yml`.

---

## Agent 1 — Backend (PERF-001, PERF-002/COST-001, SOTA-002, SUP-002)

Status: all four items DONE. Backend compiles; new `SyncEnumerationBoundsTest`
(5) passes; full suite 840 tests / 0 failures / 19 skipped.

- **DEC-101 — netty 4.1.137→4.1.138.Final** (`backend/build.gradle.kts:34` +
  CVE comment). Verified 4.1.138.Final on Maven Central (released 2026-09-09)
  and that it resolves on `runtimeClasspath`. Reversal: restore 4.1.137.
- **DEC-102 — Gemini image model GA id = `gemini-3.1-flash-image`** (no
  suffix), confirmed on ai.google.dev. Applied to the 4 `application.yml`
  defaults (227/289/309/345) and, by lead-agent follow-up, the 3 Java `@Value`
  fallback defaults (GeminiExerciseMediaService:257, GeminiFoodImageGenerator:221,
  EquipmentImageService:51). Reversal: revert the value edits.
- **DEC-103 — CDS without AOT** in `backend/Dockerfile`: layered-jar extract +
  training run (`-Dspring.context.exit=onRefresh -XX:ArchiveClassesAtExit`) +
  runtime `-XX:SharedArchiveFile … -XX:+AutoCreateSharedArchive`, per the Spring
  Boot 3.5 CDS reference. AOT explicitly out of scope. Reversal: drop the CDS
  stage + flags.
- **DEC-104 — `AutoCreateSharedArchive` + `test -f app.jsa` build gate** so a
  CDS training failure degrades to slower start / loud build failure, never a
  broken runtime.
- **DEC-105 — PERF-001 backdate slack = 35 days** for the cursor→date-floor
  bound (no server-side backdate limit exists); correctness is guaranteed
  regardless by the full-scan valve. Reversal: tune the constant in
  `SyncEnumerationBounds`.
- **DEC-106 — full-scan valve every ~16 syncs** (deterministic on cursor
  seconds; no per-user server-side sync counter). Backdated edits beyond the
  slack still converge via this periodic full enumeration.
- **DEC-107 — window push-down NOT applied to the Firestore query.** A
  `where(dateField)` must be the first `orderBy` and collides with the
  `updatedAt` cursor ordering, requiring new composite indexes (infra-owned).
  Kept `windowAllows` as the in-app belt; the enumeration bound is the real win.
- **DEC-108 — `goals/phases/steps` left fully enumerated** — not safely
  date/`updatedAt`-keyed; bounded by entity count, not account age (per PERF-008
  it's a separate, lower-severity fix).
- **DEC-109 — cloudbuild deploy flags documented-only, not applied**
  (`backend/cloudbuild.yaml` recommendation comment). Per DEC-001 the
  min-instances/throttling change is an operator cost decision.
- **COST-001 async inventory result:** no site genuinely needs always-on CPU —
  all durable nutrition work is on the Cloud Tasks rail in prod; `runAsync` is
  the local-mode fallback; webhook/backfill/cache-warmer virtual threads are
  harmless-if-throttled. **Recommendation: Option 2** (drop
  `--no-cpu-throttling`, add `--min-instances=1`) → warm ~1.3s p50 AND ≈ −$40
  to −$55/mo with CDS.
- **PENDING (not blocking):** emulator verification of the reader — the ~19
  emulator tests self-skip in the forked test JVM (firebase not on PATH) and
  none exercise `FirestoreSyncChangeReader`. Recommended follow-up: an emulator
  test seeding 730 `nutritionDays` docs asserting the RPC drop + a beyond-slack
  backdated edit converging via the valve. **CDS training-run risk:** if a
  fail-closed bean blocks context refresh without live creds, the training step
  fails the image build — operator may need dummy training-run env vars.
- **Flagged, not changed:** `backend/cloudbuild.yaml:96` prod override
  `EXERCISE_MEDIA_MODEL=gemini-3-pro-image-preview` is a deliberate,
  operator-owned pro-image choice (audit COST-007) — left as-is.

## Agent 2 — Infra (DATA-001, DATA-002, OBS-001)

Status: DONE as ready-to-apply IaC (not applied — ADC unavailable, DEC-001).
Files: new `infra/terraform/firestore_backup.tf`, `monitoring.tf`, `APPLY.md`;
changed `firestore_ttl.tf`, `variables.tf`, `README.md`, `infra/README.md`.
`tofu fmt` clean; `tofu validate` NOT run (needs `init`/network) → manual
syntax review + provider-arg names verified against hashicorp/google docs
(2026-09-13).

- **DEC-201 — backups daily-7d + weekly-14w** (`604800s` / `8467200s`); 14w is
  the provider/service max. Reversal: adjust retention args.
- **DEC-202 — GCS noncurrent-version lifecycle = 30 days** (bounds the
  versioning safety net; matches audit COST-005).
- **DEC-203 — Firestore DB + 9 buckets adopted via terraform `import` blocks**
  (they were script-created) with `prevent_destroy` / `ignore_changes` on
  location/type/IAM so the plan is updates-only, never a destructive recreate;
  out-of-band gcloud commands also documented as an alternative. **Operator must
  confirm the import IDs resolve and `plan` shows no replace.**
- **DEC-204 — DATA-002 fix:** TTL policy re-targeted from `(default)` to
  `var.firestore_database` (= `production`); parameterized rather than
  hardcoded so `(default)`/`staging` stay explicit.
- **DEC-205 — `ignore_changes` on bucket IAM** so SEC-012's public-read grants
  are left untouched (SEC-012 de-scoped to IMPL-SEC-01).
- **DEC-206 — Cloud Build failure alert via log-based metric**
  (`resource.type="build"`, status FAILURE/INTERNAL_ERROR/TIMEOUT) — cleanest
  all-terraform path, no Pub/Sub notifier. Operator should confirm the payload
  field (`jsonPayload.status` vs `protoPayload`) against live logs.
- **DEC-207 — budget alert gated by `count` on empty `billing_account_id`** so
  everything else applies without billing-account IAM; operator supplies the
  id + `roles/billing.costsManager` to enable it.
- **DEC-208 — notification channel = email evan.ruff@oxos.com.**
- **Alerts created:** backend 5xx rate, `/actuator/health` uptime check + alert,
  Cloud Build failure, Cloud Run Job error, budget (gated).
- **Flag for operator (couldn't `validate` offline):** the two `import` blocks,
  the Cloud Build log-metric filter field, and the uptime alert aggregation —
  eyeball on first `plan`/console.

## Agent 3 — CI governance (CICD-001)

Status: DONE (workflow refactor + ruleset-as-code; ruleset NOT applied per
DEC-001). Changed `.github/workflows/{android,backend,web,terraform}-ci.yml`;
new `.github/branch-ruleset.json`, `.github/scripts/apply-branch-ruleset.sh`,
`.github/README.md`. codeql.yml left unchanged (already always reports). All
5 workflows parse; ruleset JSON valid; script `bash -n` clean; aggregate job
names verified to match the ruleset's required contexts.

- **DEC-301 — early-exit pattern:** each component workflow always runs on
  PR/push to main; a `changes` guard job (`dorny/paths-filter`, SHA-pinned
  v3.0.2) carries the old path lists; heavy jobs `needs: changes` + `if:
  changed=='true'`; a final aggregate job **named after the workflow**
  (`android-ci`/`backend-ci`/`web-ci`/`terraform-ci`), `if: always()`, passes
  when heavy jobs skipped OR all succeeded. Unchanged component → aggregate goes
  green fast (no required-check wedge); real failure → aggregate fails.
- **DEC-302 — one aggregate context per workflow** (not per-heavy-job required
  checks) — avoids the two-`build`-jobs name collision and the
  skipped-counts-as-unsatisfied wedge.
- **DEC-303 — required checks = ** `android-ci, backend-ci, web-ci,
  terraform-ci, analyze (javascript-typescript), analyze (java-kotlin)`.
- **DEC-304 — 0 required approvals** (solo operator; the gate is the checks,
  not review — audit CICD-012).
- **DEC-305 — do NOT enforce-for-admins** (bypass retained) so the operator
  keeps a break-glass path to recover a misconfigured ruleset / emergency
  hotfix. Documented how to tighten later.
- **DEC-306 — `strict`/up-to-date-branches OFF** — avoids a serial rebase
  treadmill at ~2 merges/day for no safety gain; documented how to flip on.
- **DEC-307 — ruleset applied by operator AFTER this merges to main** (the
  aggregate contexts must exist on main first, else required checks reference
  jobs main doesn't define). Live state confirmed read-only: no branch
  protection, no existing rulesets (clean slate).
- **CLAUDE.md callout:** this change edits `.github/workflows/` (all four
  component workflows) — must be surfaced in the PR description per CLAUDE.md.
- **Couldn't verify without running Actions:** `dorny/paths-filter` base
  resolution, `needs.<job>.result` expansion, and check-run naming — verified
  statically only.

## Final verification notes (lead agent)

- **Backend:** `compileJava` SUCCESS with all edits (incl. the 3 Java `@Value`
  model-default follow-ups). `test` for `core.sync.*` + `SyncContractIntegrationTest`
  = 26 tests, 0 failures (SyncEnumerationBoundsTest 5/5). Agent's full-suite run
  was 840/0/19. No `collectionGroup` introduced (grep — only explanatory
  comments) → ADR-0021 upheld. No active preview image-model default remains.
- **Infra:** `tofu fmt -check -recursive` clean. `tofu validate` not run
  (needs network `init`) — flagged for operator on first `plan`.
- **CI:** all 4 refactored workflows parse (ruby YAML); `branch-ruleset.json`
  valid (4 rules); `apply-branch-ruleset.sh` `bash -n` clean; the 4 aggregate
  job names exactly match the ruleset's 4 workflow contexts (+2 codeql).
- **Cleanup:** reverted `docs/test_reports/workout_logs/seed_preview.json` — a
  regenerated test artifact, not part of the remediation.
- **Not applied this session (DEC-001):** all `infra/terraform` resources and
  the GitHub branch ruleset. Apply steps: `infra/terraform/APPLY.md` and
  `.github/scripts/apply-branch-ruleset.sh` (run after this merges to main).
- **De-scoped (DEC-002):** SEC-012 → `IMPL-SEC-01-private-media-buckets.md`.

### Operator action items after review
1. Apply infra (`infra/terraform/APPLY.md`) — confirm `plan` shows imports +
   updates only, never replace/destroy; supply `billing_account_id` to enable
   the budget alert.
2. Decide PERF-002/COST-001 compute option (recommended: Option 2) and apply
   the documented `backend/cloudbuild.yaml` flag delta.
3. After this branch merges to main and CI reports green, run
   `.github/scripts/apply-branch-ruleset.sh`.
4. Prioritize IMPL-SEC-01 (private meal-photo bucket) — the one safety-batch
   item deliberately deferred.
5. Optional: add the emulator sync test (730-day fixture) noted under Agent 1
   PENDING; verify the CDS training run against a creds-free context.
