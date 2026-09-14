# Doc drift — truth pass (Phase 5, 2026-09-13)

Every row was verified by opening both the doc and the cited code/artifact in this
worktree. Resolution "fixed" = edited in the Phase 5 reconciliation commit.
Rows marked **fix the code** were deliberately *not* edited on either side — the
doc is right and the code/infra is wrong; they carry a finding pointer instead.

| # | Doc | Claim | Reality | Evidence | Resolution |
|---|---|---|---|---|---|
| 1 | `CLAUDE.md:77` | Local backend starts on `http://localhost:8080` | `dev.sh` binds the backend to **:8090** (`BACKEND_PORT=8090`; :8443 HTTPS via Tailscale serve) | `infra/scripts/dev.sh:38` | Fixed doc → 8090 |
| 2 | `README.md:42` | `dev.sh` = "backend :8080 + web :3000" | Backend is :8090 | `infra/scripts/dev.sh:38` | Fixed doc → 8090 |
| 3 | `docs/reference/deployment.md:41,166` | Local dev backend `:8080` | :8090 | `infra/scripts/dev.sh:38` | Fixed doc → 8090 (both occurrences) |
| 4 | `docs/reference/deployment.md:16` | "GitHub Actions **gates** what's *allowed* to merge" | Nothing gates merges: no branch protection (`gh api …/branches/main/protection` → 404), and a red-CI SHA (`15ecf18`) provably deployed | `docs/audit/2026-09-13/domains/11-cicd.md` CICD-001 | Fixed wording: CI validates but is **advisory**; nothing enforces green before merge/deploy |
| 5 | `docs/reference/deployment.md:74` | Backend pipeline "runs as `tesseta-ci@…`" | Repo YAML/terraform *intend* `tesseta-ci@`, but the **live** triggers run as the compute default SA (IaC drift) | CICD-006 | **Fix the code/infra** (reconcile trigger SA per CICD-006). Doc matches repo intent — not edited |
| 6 | `docs/architecture.md:17` | Android deploys via "Play (phone + wear share `applicationId`)" | No Play track exists; distribution is **Firebase App Distribution** (`internal-testers`), APK archived to GCS | `docs/reference/deployment.md:117`; `android/cloudbuild.yaml`; CICD-004 | Fixed table row |
| 7 | `docs/architecture.md:113-121` | `docs/plans/` = parity roadmap + bulk-import plan (with a self-admitted stale note); "only specs with open work remain under `docs/specs/`" | Both named plans and all 11 specs archived by the reaping pass; `docs/specs/` is now empty | reaping-plan.md §7 | Rewrote the "Decisions & plans" section post-archive |
| 8 | `backend/CLAUDE.md:25-35` | Root-package inventory lists `auth`/`config`/`admin`/`push`/`jobs` + the four layers | Root also contains **`platform`** (16 classes — OAuth platform authz/token/crypto/filters, ADR-0020) and **`uat`** (`UatStubConfig` — deterministic AI stubs for keyless UAT boots) | `backend/src/main/java/com/gte619n/healthfitness/platform/`, `…/uat/UatStubConfig.java` | Added both to the package list |
| 9 | `docs/requirements/privacy-and-compliance.md` §4 (:62-63), §8 (:90-96) | "_(required)_ A user-facing **privacy policy**… " — i.e. no policy exists yet | A policy **is published** (`website/public/privacy.html`, "Last updated: July 28, 2026") — but it **over-promises**: §7 deletion (unbuilt), §5 "only you can reach your own records" (meal-photo bucket is public-read), §3 no-training (contingent on unverified paid tier) | COMP-001 (`domains/14-compliance.md:52-64`); `website/public/privacy.html:54` | Updated posture doc: references the published policy and lists the unkept promises as open items. **`privacy.html` itself is a controlled record — flagged, not edited** |
| 10 | `CLAUDE.md:83-84` | `AGENTS.md` is a placeholder awaiting the Parity Tool context file | Never filled in; archived 2026-09 by the reaping pass | reaping-plan.md item 32 | Updated note to point at `docs/archive/2026-09/AGENTS.md` |
| 11 | `CLAUDE.md:20-21` | `docs/specs/` holds "open implementation specs (only those with remaining work)" | All specs were IMPLEMENTED and are archived; dir is empty/gone | reaping-plan.md §2 | Updated the doc map (plans pointer kept; specs → archive convention) |
| 12 | `docs/reference/feature-catalog.md` | No Drinks feature; Nutrition row = "logs + capture + describe + recents/relog" | Shipped: Drinks (`/api/me/drinks*` `DrinkController.java:42-172`, web `web/app/me/drinks/page.tsx`, android `DrinkApi.kt` + Drink Mode), **Adjust-with-AI** (`NutritionController.java:368-483`, #245) and **Remove Leftovers** (`NutritionController.java:506-567`, `EntryResponse.java:51-52`, #244) | XPLAT-007 (`domains/06-cross-platform.md:116-121`) | Added a Drinks row; extended the Nutrition row |
| 13 | `docs/reference/feature-catalog.md:63` | "`isAdmin()` allow-lists are hardcoded on both web and backend (TODO: move to env/DB)" | Backend is env-driven and fail-closed (`application.yml` `emails: ${ADMIN_EMAILS:}`, empty ⇒ no admins); web has hardcoded built-ins *extended* by `ADMIN_EMAILS` | `domains/02-security.md:163`; `web/lib/admin.ts:4-17` | Fixed the line to describe each side accurately |
| 14 | `docs/reference/api-surface.md` | Nutrition section ends at the food catalog; no drinks/adjust/leftover endpoints; no third-party platform surface | Missing: `/api/me/drinks*`, `…/entries/{id}/adjust/*`, `…/entries/{id}/leftovers/*`, `…/entries/{id}/reanalyze`, plus the shipped `/oauth/*` + `/v1/*` platform API and `/api/me/connected-apps` | `DrinkController.java`, `NutritionController.java:340-567`, `api/platform/*.java` (`/oauth`, `/api/me/connected-apps`), `api/v1/*.java` (`/v1`) | Added Drinks rows, adjust/leftover/reanalyze rows, and a Third-party platform section |
| 15 | `docs/reference/data-model.md` | `FoodEntry` row lists no leftover/adjust state; user doc lists no drink order; `foodCatalog` has no drink note | `FoodEntry` carries `leftover` + `adjustment` (`FoodEntry.java:52-55`); drink display order is a `drinkOrder` field on `users/{u}` (`FirestoreDrinkOrderRepository.java:28`); drinks are `CatalogFood` rows with `category="drink"` | code cited | Added the fields/notes |
| 16 | `infra/README.md:26` | Links `docs/specs/IMPL-02-google-auth.md` | File does not exist (pre-existing dead link — no IMPL-02 anywhere in the repo) | `ls docs/specs/` | Reworded to point at the durable reference docs |
| 17 | `infra/README.md:30` | "Drop the [Parity Tool] context file in `AGENTS.md` at the repo root" | `AGENTS.md` archived (never filled) | reaping item 32 | Updated pointer to the archive location |
| 18 | `docs/decisions/ADR-0020-third-party-oauth-platform-api.md:3` | Status: **Proposed** | Fully shipped: authz server + `/v1` read API + consent page live (`api/platform/`, `api/v1/`, `platform/`); prod config wired per PR #156 | reaping-plan.md §3/§4 | In-place status flip → **Accepted** with dated note (+ pointer to the relocated companion decision log) |
| 19 | `README.md:29-33` feature list | Nutrition described without drink logging | Drink Mode / drink sessions shipped (IMPL-DRINK-01) | `DrinkController.java`, `web/app/me/drinks/page.tsx` | Added "drink logging" to the feature sentence |
| 20 | Various remaining docs (`android/CLAUDE.md:16`, `functions/exercise-thumbnails/README.md:7`, ADR-0007/0008/0009/0013/0016/0017, `feature-catalog.md:6,50`) | Relative links into `docs/specs/`/`docs/plans/` files | Targets moved to `docs/archive/2026-09/{specs,plans}/` by the reaping pass | reaping-plan.md §7 | Links updated to archive paths (ADRs: link-path fixes only, no content changes) |

## Fix-the-code items (recorded, not edited)

- **Row 5 / CICD-006** — trigger service-account IaC drift: live Cloud Build triggers
  run as the compute default SA while repo YAML + terraform say `tesseta-ci@`.
  Reconcile the infra (re-import with role grants verified), then the doc is true.
- **`docs/architecture.md:88` "On-device Health Connect is not integrated"** — verified
  still true (no android Health Connect client dep); listed here only because it was
  spot-checked, no action.

## Controlled records flagged (never edited in this pass)

- `website/public/privacy.html` and `website/public/terms.html` — published legal
  documents. COMP-001's word-vs-capability gaps (deletion §6/§7, isolation §5,
  no-training §3) remain **in the published policy** until the operator runs the
  COMP-001 fix (edit policy or ship the capabilities). This pass only made the
  internal posture doc (`docs/requirements/privacy-and-compliance.md`) tell the truth
  about that state.
