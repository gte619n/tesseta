# infra

One-time GCP provisioning for `health-fitness-160` (region `us-central1`).
Idempotent — safe to re-run.

## Setup sequence

1. `gcloud auth login`
2. `gcloud config set project health-fitness-160`
3. `chmod +x scripts/*.sh`
4. `./scripts/enable-apis.sh`
5. `./scripts/bootstrap-gcp.sh`
6. Follow the two Console steps printed at the end of the bootstrap script
   (OAuth consent screen + OAuth 2.0 Client ID).
7. Store the OAuth client secret and client ID in Secret Manager using the
   `gcloud secrets create ...` commands printed at the end of the bootstrap.
8. `./scripts/setup-android-signing.sh` — generates the Android release
   keystore, the shared debug keystore, stores both in Secret Manager, and
   prints SHA-1 fingerprints. Then follow the three Console steps printed at
   the end (web + android phone + android wear OAuth clients). See
   [`docs/specs/IMPL-02-google-auth.md`](../docs/specs/IMPL-02-google-auth.md)
   for context.
9. Download the Google Health API Parity context file from
   <https://developers.google.com/health/get-started> and paste it into
   `AGENTS.md` at the repo root.

## What it creates

- Enabled APIs: Cloud Run, Cloud Build, Artifact Registry, Firestore,
  Secret Manager, IAM/credentials, Google Health, Logging, Monitoring.
- Artifact Registry repository `health-fitness` (Docker, `us-central1`).
- Runtime service account `health-fitness-runtime` with `datastore.user`,
  `secretmanager.secretAccessor`, `logging.logWriter`, `monitoring.metricWriter`.
- Cloud Build SA bindings to deploy to Cloud Run and write to Artifact Registry.
- Firestore default database (native mode, `us-central1`).

## Terraform

`terraform/` is a placeholder; no modules yet. Bootstrap is shell scripts
for now to keep the first run as simple as possible.
