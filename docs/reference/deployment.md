# Deployment & CI/CD

How code gets from a branch to users. Read the **[TL;DR](#tldr)** first, then the
section for whatever you're doing.

There are **two independent CI systems** and they do different jobs — do not
confuse them:

| System | Lives in | Fires on | Does |
|---|---|---|---|
| **GitHub Actions** | `.github/workflows/` | every PR + push to `main` | **Validates** (test/lint/typecheck/coverage/SAST). Never deploys. |
| **GCP Cloud Build** | `infra/triggers/` + `*/cloudbuild.yaml` | **push to `main`** (path-filtered) | **Builds + deploys** to production. |

The golden rule: **merging to `main` is the production deploy.** There is no
separate "deploy" button or command for the normal path. GitHub Actions gates
what's *allowed* to merge; Cloud Build ships whatever lands on `main`.

---

## TL;DR

- **Ship to production** → open a PR, get CI green, **merge to `main`**. The
  Cloud Build trigger for the changed component builds, scans, canary-deploys,
  smoke-tests, and shifts traffic automatically. Nothing else to run.
- **Only the component you touched deploys.** Triggers are path-filtered
  (`backend/**`, `web/**`, `android/**`). A backend-only PR does not rebuild web
  or android.
- **Roll back** → `PROJECT_ID=health-fitness-160 infra/scripts/rollback.sh <service>`.
- **"Development" is local**, not a deployed environment. `bash infra/scripts/dev.sh`.
  A real staging environment exists only as opt-in Terraform scaffold — see
  [Staging](#staging-development-environment).
- **Everything targets one GCP project:** `health-fitness-160`, region
  `us-central1`.

---

## Environments

| Environment | Where | Firestore DB | Notes |
|---|---|---|---|
| **Local dev** | your machine (`infra/scripts/dev.sh`) | `(default)` | Backend `:8080`, web `:3000`. Secrets pulled live from Secret Manager. |
| **Production** | Cloud Run in `health-fitness-160` / `us-central1` | `production` (named DB) | The only continuously-deployed environment. Backend sets `FIRESTORE_DATABASE_ID=production`. |
| **Staging** | *scaffold only* — see below | `staging` (named DB, opt-in) | No deploy pipeline wired yet. Terraform can create the DB; deploys would be manual. |

There is **one GCP project** for everything (dev, prod share it, separated by
Firestore database + service names). There is no separate prod GCP project today.

---

## Deploy targets

Everything that gets deployed, and by what:

| Target | Type | Deployed by | Public URL |
|---|---|---|---|
| `health-fitness-backend` | Cloud Run service | `backend/cloudbuild.yaml` (on `backend/**` merge) | `https://api.tesseta.com` |
| `health-fitness-web` | Cloud Run service | `web/cloudbuild.yaml` (on `web/**` merge) | `https://app.tesseta.com` |
| Android release APK | Firebase App Distribution (`internal-testers`) | `android/cloudbuild.yaml` (on `android/**` merge) | testers get an email; APK also in `gs://health-fitness-160-android-releases/<sha>/` |
| `goals-sustained-reeval` | Cloud Run **Job** | backend pipeline updates its image each deploy; created once via `deploy-goals-sustained-job.sh` | — (scheduled) |
| Other Cloud Run Jobs (seed foods/workouts, exercise media/plan, thumbnails fn) | Jobs / Function | **manual** `infra/scripts/deploy-*.sh` | — |
| Firestore indexes | config | **manual** `infra/scripts/deploy-firestore-indexes.sh` | — |

> The custom domains `api.tesseta.com` / `app.tesseta.com` are Cloud Run domain
> mappings configured out-of-band in the console (not in this repo). The
> pipelines reference them via env vars (`BACKEND_URL`, `AUTH_URL`,
> `CORS_ALLOWED_ORIGINS`); they don't create them.

---

## The production pipeline (what happens on merge to `main`)

### Backend — `backend/cloudbuild.yaml`

Runs as `tesseta-ci@health-fitness-160.iam.gserviceaccount.com`.

1. **build-image** — `docker build` → tag `backend:$SHORT_SHA` + `backend:latest`.
2. **push-image** — push both tags to Artifact Registry
   (`us-central1-docker.pkg.dev/health-fitness-160/health-fitness/backend`).
3. **scan-image** — **Trivy** scans the image. **Fails the build** on fixable
   HIGH/CRITICAL CVEs (`--ignore-unfixed`). A vulnerable image never deploys.
4. **deploy-candidate** — `gcloud run deploy … --no-traffic --tag=candidate`.
   New revision comes up serving **zero** traffic under a stable `candidate`
   URL; the live revision keeps serving. Sets memory 2Gi / cpu 2 /
   concurrency 16 and injects env vars + secrets (see [Config](#config--secrets)).
5. **promote-canary** — `infra/scripts/canary-promote.sh` smoke-tests the
   candidate URL at `/actuator/health` (up to 30 tries). On success, shifts
   **100%** traffic to it. **On failure, traffic is left untouched** — a bad
   build never reaches users.
6. **update-goals-sustained-job** — points the `goals-sustained-reeval` Cloud
   Run Job at the new image (`|| true`, so a missing job doesn't fail the deploy).

### Web — `web/cloudbuild.yaml`

Same shape as backend: build → push → Trivy scan → `--no-traffic --tag=candidate`
deploy → `canary-promote.sh` smoke-tests `/` (accepts 2xx/3xx; root redirects to
sign-in) → shift 100% traffic. No jobs step.

### Android — `android/cloudbuild.yaml`

Not Cloud Run — builds a signed APK and distributes to testers. Machine type
`E2_HIGHCPU_32`, 30-min timeout.

1. **fetch-secrets** — pulls `oauth-web-client-id` (a public identifier compiled
   into the APK). Release builds are signed with the **committed
   `android/debug.keystore`**, so no signing secrets are fetched.
2. **compute-version** — derives `versionCode` (commit count on `main`) and a
   `versionName`, and generates plain-English release notes (Gemini, with a
   deterministic git-log fallback). "Last released build" is tracked by a marker
   object `gs://health-fitness-160-android-releases/last-release-sha`.
3. **build-release-apk** — `./gradlew :app:assembleRelease` (R8-minified,
   arm64) on the `cimg/android` image.
4. **distribute-firebase** — uploads to **Firebase App Distribution**, group
   `internal-testers`, with retry/backoff on transient 5xx. On success, updates
   the `last-release-sha` marker. APK is also archived to
   `gs://health-fitness-160-android-releases/<sha>/`.

There is **no Play Store / production track** wired — Android "release" today
means *distribute to the internal tester group.*

---

## How to deploy — step by step

### Production (the normal path)

1. Branch, make your change.
2. Open a PR. Ensure the matching **GitHub Actions** check is green
   (`backend-ci` / `web-ci` / `android-ci`; also `terraform-ci`, `codeql`).
3. **Merge to `main`.** That's the deploy. The path-filtered Cloud Build trigger
   for the changed component(s) fires automatically.
4. Watch it: `gcloud builds list --ongoing --project=health-fitness-160`
   (or the [Cloud Build console](https://console.cloud.google.com/cloud-build/builds?project=health-fitness-160)).
5. Verify after promote:
   - Backend: `curl https://api.tesseta.com/actuator/health`
   - Web: open `https://app.tesseta.com`
   - Android: testers receive the build; check
     [App Distribution](https://console.firebase.google.com/project/health-fitness-160/appdistribution).

If the pipeline's own smoke test fails, traffic is **not** shifted and the build
goes red — the previous revision is still live, so there's nothing to undo.

> **Safety net — enable branch protection on `main`** so only CI-green commits
> can merge (require the `*-ci` status checks + PR, no direct pushes). Then Cloud
> Build only ever deploys green commits. See `infra/terraform/README.md`
> → *CI-gated deploys*.

### Manually trigger a build (re-deploy without a code change)

Deploys are tied to `main` commits, but you can run a pipeline by hand:

```bash
# Re-run the current main HEAD through a component pipeline:
gcloud builds submit backend/ --config=backend/cloudbuild.yaml --project=health-fitness-160
gcloud builds submit web/     --config=web/cloudbuild.yaml     --project=health-fitness-160
gcloud builds submit android/ --config=android/cloudbuild.yaml --project=health-fitness-160
```

(`gcloud builds submit <dir>/` uploads just that subdir as `/workspace`, where
each cloudbuild's `dir:` becomes a no-op — see the comment atop each file.)

### Staging / development environment

**There is no deployed staging today.** For day-to-day development, run locally:

```bash
bash infra/scripts/dev.sh   # backend :8080 + web :3000, secrets from Secret Manager
```

A staging environment is prepared but **opt-in and not wired to a pipeline**:

- `infra/terraform/staging.tf` can create a separate `staging` Firestore
  database: `tofu apply -var=enable_staging=true` (permanent — `prevent_destroy`).
- Deploying staging services is **manual**: deploy to `-staging` service names
  with `--set-env-vars=FIRESTORE_DATABASE_ID=staging`, then promote to prod on
  green. There is no `deploy-*-on-staging` trigger or `cloudbuild-staging.yaml`
  yet — building one is a follow-up.

So "development deployment" = local (`dev.sh`) or a hand-rolled staging deploy;
it is **not** an automated target.

---

## Rollback

Every promote **retains** the previous revision. To revert a bad production
deploy, shift traffic back — no rebuild:

```bash
PROJECT_ID=health-fitness-160 infra/scripts/rollback.sh health-fitness-backend
PROJECT_ID=health-fitness-160 infra/scripts/rollback.sh health-fitness-web
```

`rollback.sh` moves 100% traffic to the previous ready revision. Region defaults
to `us-central1`. (Android has no rollback — distribute a corrected build.)

---

## Config & secrets

Runtime config is injected at deploy time by each `cloudbuild.yaml`, not baked
into images.

- **Env vars** (non-secret) are set with `--set-env-vars`. Key ones:
  - Backend: `GCP_PROJECT_ID`, `FIRESTORE_DATABASE_ID=production`,
    `CORS_ALLOWED_ORIGINS`, `ADMIN_EMAILS`, `GOOGLE_HEALTH_KMS_KEY`.
  - Web: `BACKEND_URL=https://api.tesseta.com`, `AUTH_URL=https://app.tesseta.com`,
    `AUTH_TRUST_HOST=true`.
- **Secrets** come from **Secret Manager** via `--set-secrets` (mounted as env
  vars at runtime). The exact per-pipeline secret lists are the source of truth
  in `infra/terraform/variables.tf` (`backend_secrets` / `web_secrets` /
  `android_secrets`). Examples: `session-signing-key`, `oauth-web-client-secret`,
  `authjs-secret`, `gemini_api_key`, `google-health-webhook-secret`.

To change config, edit the relevant `cloudbuild.yaml` and merge — the next
deploy picks it up. To rotate a secret, add a new Secret Manager version; the
`:latest` reference picks it up on the next deploy.

### Service accounts

| SA | Used by | Has |
|---|---|---|
| `tesseta-ci@…` | the three Cloud Build triggers | run.admin, artifactregistry.writer, firebaseappdistro.admin, logWriter, builds.builder, actAs runtime SA, and **per-secret** accessor (only the secrets its pipelines read — see `infra/terraform/ci_iam.tf`). |
| `health-fitness-runtime@…` | the deployed Cloud Run services/jobs | datastore.user, secretAccessor, storage.objectAdmin, logWriter, metricWriter, KMS encrypt/decrypt on the health-token key. |

The dedicated `tesseta-ci` SA replaced the Compute Engine default SA
specifically to remove project-wide secret access. If you add a new secret to a
pipeline, add it to the matching list in `variables.tf` and re-apply, or the
build will 403 reading it.

---

## First-time / fresh-project setup

Not needed for normal deploys (the project is already provisioned). To stand up
the pipeline on a new project, in order:

1. `infra/scripts/enable-apis.sh` — enable required GCP APIs.
2. `infra/scripts/bootstrap-gcp.sh` — Artifact Registry, runtime SA + roles,
   buckets, `(default)` Firestore. Follow its printed OAuth-console steps.
3. `infra/scripts/setup-android-signing.sh` — keystores + Secret Manager entries.
4. Store OAuth + other secrets in Secret Manager (see `infra/README.md`).
5. `infra/scripts/setup-cloud-build-triggers.sh` — import the three triggers and
   grant SA roles. **Requires the Cloud Build GitHub App installed on the repo.**
6. (Optional) apply Terraform for the least-privilege CI SA and staging DB —
   `infra/terraform/README.md`.
7. Deploy Firestore indexes to `production`:
   `infra/scripts/deploy-firestore-indexes.sh`.

See **[`infra/README.md`](../../infra/README.md)** for the authoritative setup
sequence and every provisioning detail.

---

## Out-of-band deploys (not part of the auto pipeline)

These ship on their own schedule via scripts under `infra/scripts/`:

- **Firestore indexes** — `deploy-firestore-indexes.sh` (run whenever
  `infra/firestore/firestore.indexes.json` changes; indexes are **not** copied
  by data migrations).
- **Cloud Run Jobs** — `deploy-goals-sustained-job.sh`, `deploy-seed-foods-job.sh`,
  `deploy-seed-workouts-job.sh`, `deploy-exercise-media-job.sh`,
  `deploy-exercise-plan-job.sh`, `deploy-split-workout-blocks-job.sh`.
- **Thumbnail Cloud Function** — `deploy-thumbnail-fn.sh` (ADR-0017).
- **Firestore data copies** — `copy-default-to-production-firestore.sh` /
  `copy-production-to-default-firestore.sh` (documents only, **not** indexes).
- **Google Health webhook subscriber** — `setup-google-health-subscriber.sh`.

---

## Quick reference

```bash
# Watch in-flight deploys
gcloud builds list --ongoing --project=health-fitness-160

# Health checks
curl https://api.tesseta.com/actuator/health
open  https://app.tesseta.com

# Roll back production
PROJECT_ID=health-fitness-160 infra/scripts/rollback.sh health-fitness-backend
PROJECT_ID=health-fitness-160 infra/scripts/rollback.sh health-fitness-web

# List Cloud Run revisions / current traffic split
gcloud run services describe health-fitness-backend --region=us-central1 --project=health-fitness-160

# Manually re-run a pipeline
gcloud builds submit backend/ --config=backend/cloudbuild.yaml --project=health-fitness-160
```
