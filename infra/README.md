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
- Secret Manager: `session-signing-key` (ADR-0010). 48 random bytes,
  base64 (`openssl rand -base64 48`), used as the HS256 key for
  native-client session access tokens. Runtime SA bound to
  `roles/secretmanager.secretAccessor` on it; wired into the backend
  service as `SESSION_SIGNING_KEY` via `backend/cloudbuild.yaml`.
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

## IMPL-AND-20: Firestore TTL on idempotencyKeys

The offline-sync idempotency store (`FirestoreIdempotencyStore`) writes records
at `users/{uid}/idempotencyKeys/{scope#key}` carrying an `expiresAt` Firestore
`Timestamp`. A Firestore **TTL policy** on that field reaps expired records
automatically so the subcollection does not grow without bound. The in-app read
also re-checks `expiresAt`, so an unreaped-but-expired key still behaves as
absent — the TTL is purely a storage-hygiene reaper, not a correctness gate.

Declared as infra-as-code in
[`terraform/firestore_ttl.tf`](terraform/firestore_ttl.tf) (a
`google_firestore_field` with a `ttl_config` block on collection group
`idempotencyKeys`, field `expiresAt`). That file is the source of truth.

If Terraform is not yet being applied for this project, apply the **equivalent
one-time gcloud command** (idempotent; safe to re-run):

```bash
# Enable the TTL policy on idempotencyKeys.expiresAt (collection-group scope).
gcloud firestore fields ttls update expiresAt \
  --collection-group=idempotencyKeys \
  --enable-ttl \
  --project=health-fitness-160 \
  --database='(default)'

# Verify it is now SERVING:
gcloud firestore fields ttls list \
  --collection-group=idempotencyKeys \
  --project=health-fitness-160 \
  --database='(default)'
```

The policy takes effect asynchronously (state goes `CREATING` → `ACTIVE`);
Firestore then deletes documents within ~24h of their `expiresAt` passing.

## Firestore composite indexes

The runtime database is the **`production`** named database (not `(default)`).
The source of truth for composite indexes and field overrides is
[`firestore/firestore.indexes.json`](firestore/firestore.indexes.json). A query
that needs a composite index it doesn't have fails at runtime with
`FAILED_PRECONDITION: The query requires an index`, which the backend surfaces
as a 500.

**Indexes are not data.** `copy-default-to-production-firestore.sh` (and Firestore
export/import generally) copies **documents only** — it does **not** carry
composite indexes or field overrides. After that migration `production` had data
but was missing indexes, so program activation and other queries 500'd. Deploying
the index file to a database is therefore a separate, explicit step.

Apply the index file to a database (idempotent; safe to re-run):

```bash
infra/scripts/deploy-firestore-indexes.sh                 # target: production
infra/scripts/deploy-firestore-indexes.sh --dry-run       # show current, change nothing
infra/scripts/deploy-firestore-indexes.sh --force         # no prompts (CI)
infra/scripts/deploy-firestore-indexes.sh --database='(default)'
```

The script uses the firebase CLI (authenticated via gcloud Application Default
Credentials) because `gcloud firestore indexes` cannot express `COLLECTION_GROUP`
scope for single-field overrides. Composite indexes build asynchronously
(`CREATING` → `READY`); verify with:

```bash
gcloud firestore indexes composite list --project=health-fitness-160 --database=production
```

**Run this script whenever `firestore.indexes.json` changes, and as part of any
fresh-project or new-database bootstrap** — otherwise the live database silently
drifts from the repo. See the script header for a known firebase-tools caveat
(it will not repair the *scope* of an override that already exists).

## Terraform

`terraform/` currently holds only the Firestore TTL policy above
(`firestore_ttl.tf` + `versions.tf`). The rest of the bootstrap is still shell
scripts to keep the first run simple; new infra-as-code lands here as it is
adopted.
