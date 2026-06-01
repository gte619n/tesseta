# ADR-0004: Per-user Google Health refresh tokens, KMS-encrypted in Firestore

- Status: Accepted
- Date: 2026-05-21

## Context

The backend's [Google Health ingestion](../reference/patterns.md#google-health-ingestion)
is its first OAuth flow that needs **server-side, long-lived credentials**.

- The Google Health API requires user-delegated OAuth access tokens
  scoped to `googlehealth.health_metrics_and_measurements.readonly`.
- Access tokens expire every hour, so the backend needs **refresh
  tokens** to keep calling the API in the background — specifically,
  to hydrate webhook notifications that may arrive when the user is
  nowhere near a browser.
- Refresh tokens are bearer credentials that, if leaked, give the
  holder ongoing access to the user's health data until the user
  manually revokes them from their Google Account. Their blast radius
  is large.
- IMPL-02 deliberately avoided server-side session state by adopting
  Google ID tokens (JWTs) as the only auth mechanism. Refresh tokens
  break that property — they *are* server-side state — so the storage
  decision is significant and worth recording.

Four storage options were considered:

1. **Plaintext in Firestore.** Simple. Refresh tokens sit in the user
   document. Anyone with read access to Firestore (including a stolen
   service-account key) has them.
2. **Encrypted in Firestore via a single secret in Secret Manager.**
   Symmetric AES-GCM, key fetched from Secret Manager at startup. One
   key for all tokens.
3. **Per-token envelope encryption via Cloud KMS.** Each refresh token
   encrypted with a freshly generated data-encryption key (DEK); the
   DEK itself is wrapped by a long-lived KMS key (KEK). Both the
   ciphertext and the wrapped DEK live on the user document. Industry
   standard pattern.
4. **One Secret Manager entry per user.** Each user's refresh token is
   its own secret. Audit trail is automatic via Cloud Audit Logs.
   Pricing: $0.06 per secret per month, so $0.72/user/year. Plus per-
   access fees.

Constraints that influenced the choice:

- **Blast radius asymmetry.** A Firestore export, a compromised
  backend service account, or even a misconfigured IAM grant should
  not surface usable refresh tokens. The decryption capability has to
  be guarded behind a separate axis from the data access.
- **Operational cost.** A per-user Secret Manager entry would dominate
  the cost of running the service at any non-trivial user count.
- **Recoverability.** Losing the encryption key has to mean tokens
  become unrecoverable. We want users to re-grant consent in that
  case, not have plaintext lying around.
- **Familiar pattern.** Envelope encryption with Cloud KMS is the
  Google-recommended pattern for sensitive data at rest in Firestore
  and is documented in Google's security guides; we're not inventing
  a custom scheme.

## Decision

Adopt **per-token envelope encryption via Cloud KMS** (option 3).

- A single symmetric KMS key
  `projects/health-fitness-160/locations/us-central1/keyRings/auth/cryptoKeys/google-health-refresh-tokens`
  is the KEK. Key rotation is configured to **90 days, automatic**.
  Older key versions remain available for decryption indefinitely.
- For each connected user, the backend generates a 32-byte AES-256-GCM
  DEK at encrypt time, encrypts the refresh token with the DEK, then
  asks KMS to encrypt the DEK with the KEK. Both ciphertexts and the
  GCM nonce are stored on the user document:
  - `googleHealth.refreshTokenCiphertext` — `bytes`
  - `googleHealth.dekCiphertext` — `bytes`
- The Cloud Run runtime service account gets
  `roles/cloudkms.cryptoKeyEncrypterDecrypter` on that single key —
  bound at the key resource, not the project, so the blast radius of
  that grant is one key, not all KMS material in the project.
- Refresh tokens are decrypted on demand (just before exchanging for
  an access token) and **never retained** in process memory beyond
  the lifetime of the resulting access token. Access tokens, in turn,
  are cached in process for ~50 minutes per user (10-minute buffer
  before Google's 1-hour expiry).
- Raw refresh tokens never appear in logs. The `AccessTokenService`
  treats them as opaque and never `toString()`s them.

## Consequences

Positive:

- A Firestore data exfiltration leaks ciphertext, not usable tokens.
  Decrypting requires the additional `cloudkms.cryptoKeyDecrypter`
  permission on a specific key.
- Cost scales with KMS request volume (sub-cent per refresh on Google's
  current pricing), not per user.
- Key rotation is configuration, not a code change. Old DEKs remain
  decryptable because old KEK versions stay active.
- Pattern is reusable for any future server-side OAuth integrations
  (Fitbit-direct fallback, Withings, Garmin, etc.). The same key can
  envelope-encrypt their refresh tokens too.

Negative:

- Adds a hard dependency on KMS for any background operation that
  needs to call the Google Health API. KMS outage = no hydration of
  webhook notifications (they'd queue, Google retries for 7 days,
  recoverable but observable).
- A per-decrypt KMS call adds ~30 ms latency to webhook hydration.
  Mitigated by the per-process access-token cache, so the KMS call
  happens at most ~1×/hour per active user.
- Operational complexity: one more GCP service to monitor, IAM grant
  to maintain, key rotation policy to honor.
- If the KMS key is ever destroyed, all stored refresh tokens become
  unrecoverable. Mitigated by the fact that users can re-grant the
  scope at any time; mark the impact as "users see one extra consent
  prompt" rather than data loss.

## Revisit when

- A second OAuth integration ships and we need to evaluate whether
  per-integration KEKs are warranted (probably yes for least-privilege
  separation).
- KMS regional availability becomes a constraint (we'd move to a
  multi-region key location).
- A managed alternative emerges — e.g., if Google publishes a "stored
  user credentials" product that fits this use case with the same
  security properties — at which point we re-evaluate the operational
  trade.
