# Terraform — infra as code

Manages the durable GCP infrastructure for `health-fitness-160`. Everything here
is validated in CI (`terraform-ci.yml`: `fmt` + `validate`); `plan`/`apply` are
run by a human/operator against the remote state.

| File | What |
|---|---|
| `backend.tf` | Remote state in a versioned GCS bucket (shared + locked) |
| `variables.tf` | project/region + per-pipeline secret lists + `enable_staging` |
| `ci_iam.tf` | **Dedicated least-privilege CI service account** with **per-secret** access (replaces project-wide `secretAccessor`) |
| `staging.tf` | Staging Firestore database (opt-in via `enable_staging`) |
| `firestore_ttl.tf` | Idempotency-key TTL policy (pre-existing) |

> Tooling: `terraform` **or** `tofu` (OpenTofu) — commands below use `tofu`; they
> are drop-in with `terraform`.

## One-time bootstrap (remote state)

The state bucket can't be Terraform-managed (chicken-and-egg), so create it once:

```bash
PROJECT=health-fitness-160
gcloud storage buckets create gs://$PROJECT-tf-state \
  --project=$PROJECT --location=us-central1 --uniform-bucket-level-access
gcloud storage buckets update gs://$PROJECT-tf-state --versioning   # keep history
```

Then initialise + import any resources that already exist so the first plan is a
no-op (the TTL policy was created out-of-band per `../README.md`):

```bash
cd infra/terraform
tofu init                                   # uses the gcs backend
tofu import google_firestore_field.idempotency_keys_ttl \
  "projects/$PROJECT/databases/(default)/collectionGroups/idempotencyKeys/fields/expiresAt"
tofu plan                                   # review — should be additive only
```

## Rollout (ordered — verify between steps)

1. **CI service account + per-secret IAM** (additive; the old compute-SA grants
   stay, so nothing breaks yet):
   ```bash
   tofu apply -target=google_service_account.ci \
     -target=google_project_iam_member.ci_run_admin \
     -target=google_project_iam_member.ci_artifact_writer \
     -target=google_project_iam_member.ci_firebase_distro \
     -target=google_project_iam_member.ci_log_writer \
     -target=google_project_iam_member.ci_builds_builder \
     -target=google_service_account_iam_member.ci_actas_runtime \
     -target=google_secret_manager_secret_iam_member.ci_secret_access
   ```
2. **Switch the triggers to the new SA** (the trigger yamls already point at
   `tesseta-ci`): re-import them, then push a trivial commit and confirm the
   backend/web builds deploy green as `tesseta-ci`:
   ```bash
   bash ../scripts/setup-cloud-build-triggers.sh
   ```
3. **Remove the over-broad grant** only after step 2 is verified:
   ```bash
   gcloud projects remove-iam-policy-binding $PROJECT \
     --member="serviceAccount:$(gcloud projects describe $PROJECT --format='value(projectNumber)')-compute@developer.gserviceaccount.com" \
     --role=roles/secretmanager.secretAccessor --condition=None
   ```
4. **Staging (optional):** `tofu apply -var=enable_staging=true` creates the
   `staging` Firestore database (permanent — `prevent_destroy`). Deploy staging
   services by targeting `-staging` service names with
   `--set-env-vars=FIRESTORE_DATABASE_ID=staging`, promote to prod on green.

## Deploy safety (already in the pipeline)

`backend/` + `web/` `cloudbuild.yaml` now: **Trivy-scan** the image (fail on
fixable HIGH/CRITICAL) → deploy **`--no-traffic --tag=candidate`** →
**smoke-test** the candidate (`infra/scripts/canary-promote.sh`) → shift 100%
traffic only if healthy. Roll back any time with
`PROJECT_ID=$PROJECT infra/scripts/rollback.sh <service>`.

## CI-gated deploys

Deploys fire on push to `main`. Gate them by requiring green CI **before merge**:
enable branch protection on `main` requiring the `backend-ci` / `web-ci` /
`android-ci` status checks and a PR (no direct pushes). Then `main` only ever
holds CI-green commits, so Cloud Build only deploys green commits.

## Follow-ups (not yet done)

- Pin the Dockerfile base images by digest (Trivy already flags vulnerable
  bases; digest-pin adds reproducibility). Let Dependabot bump the digests.
- Import the Cloud Run services + Artifact Registry repo into Terraform (today
  they're deploy-managed) for full drift detection.
