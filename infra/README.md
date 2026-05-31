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
  `secretmanager.secretAccessor`, `storage.objectAdmin`, `logging.logWriter`,
  `monitoring.metricWriter`. (`storage.objectAdmin` lets the Cloud Run service
  upload generated images / user documents to GCS — without it, image
  generation fails with `storage.objects.create` 403s.)
- Public-read image bucket `${PROJECT_ID}-nutrition-photos` (food/studio
  images, served as public URLs). Other image buckets (`-medication-images`,
  `-equipment`, `-gym-photos`) follow the same allUsers:objectViewer pattern.
- Cloud Build SA bindings to deploy to Cloud Run and write to Artifact Registry.
- Firestore default database (native mode, `us-central1`).

## IMPL-04: Google Health API additions

The first IMPL-04 deploy adds the following resources (already provisioned
on `health-fitness-160` — listed here so a fresh-project re-bootstrap
covers them):

- Cloud KMS API enabled.
- Keyring `auth` in `us-central1` with symmetric key
  `google-health-refresh-tokens` (90-day automatic rotation). Runtime SA
  bound to `roles/cloudkms.cryptoKeyEncrypterDecrypter` on that key only
  (not project-wide).
- Secret Manager: `google-health-webhook-secret`. Stored as
  `Bearer <random32>` because Google requires the full Authorization
  header value with scheme prefix.
- Runtime SA bound to `roles/secretmanager.secretAccessor` on the new
  secret.
- Firestore composite index: `bodyComposition (metric ASC, sampleTime
  DESC)` — see `infra/firestore/firestore.indexes.json`.
- Project owner / developer user granted `roles/health.admin`. Project
  ownership does **not** inherit Health API permissions; you must grant
  this explicitly even if you already have `roles/owner`.
- One webhook subscriber registered via
  `scripts/setup-google-health-subscriber.sh`. Note the script uses the
  project **number** (not ID) — the Health API rejects the ID form with
  a generic 403.

### OAuth scope — production review pending

The Google Health API scopes are **Restricted**, which means:

- In the OAuth client's "Testing" audience, up to **100 test users** can
  grant the scope without any review. Test users must be listed
  explicitly on the [OAuth consent screen page](https://console.cloud.google.com/apis/credentials/consent?project=health-fitness-160).
- To support more than 100 users — i.e., to move the OAuth client from
  "Testing" to "In production" — Google requires a third-party security
  review. Submission is via the same OAuth consent screen page:
  *Publishing status* → *Publish app* → fills out the questionnaire and
  the security review form. Turnaround historically 4–6 weeks.
- Test-audience refresh tokens expire after 7 days. Production refresh
  tokens generally don't expire. If the connect flow stops working
  after a week, the cause is the test-audience refresh-token TTL —
  reconnecting from the body-comp page mints a fresh one.

Current state: `evan.ruff@oxos.com` is on the test users list. No
production review submitted yet.

## Terraform

`terraform/` is a placeholder; no modules yet. Bootstrap is shell scripts
for now to keep the first run as simple as possible.
