# Privacy & Compliance Posture

Tesseta stores **PHI-grade personal health data** — blood markers, medications &
adherence, body composition / DEXA, nutrition, and activity. This document states
how that data is protected and what the product is required to guarantee. It
consolidates rules previously embedded only in ADRs (notably
[ADR-0007](../decisions/ADR-0007-android-offline-first-sync.md)) and fills the
gaps that had no home.

> Status: **initial draft.** Items marked _(required)_ are the target and may not
> be implemented/verified yet — this is a posture statement to build toward, not
> a certification of compliance. This is not legal advice; a formal HIPAA/GDPR
> assessment is out of scope here and _(required)_ before any external launch.

## 1. Data classification

| Class | Examples | Handling |
|---|---|---|
| **PHI / sensitive health** | blood markers, medications, adherence, DEXA/body-comp, nutrition, activity | Per-user scoped, encrypted at rest & in transit, never logged |
| **Identity** | Google `sub`, email, display name | From the ID token; `sub` is the canonical key; email used for admin allow-list |
| **Secrets / tokens** | Google OAuth refresh tokens, session refresh tokens | Envelope-encrypted (KMS) or hashed; never stored in plaintext server-side |
| **Reference (non-personal)** | drug / food / exercise / equipment catalogs | Global, read-authenticated; admin-only writes |

## 2. Access control

- **Authentication:** Google ID tokens (web/phone/wear), validated as a JWT
  resource server. Native clients also use backend-issued session tokens
  (ADR-0010). Routing is **deny-by-default** (`SecurityConfig` ends in
  `anyRequest().denyAll()`).
- **Authorization / per-user scoping:** every `/api/me/**` read and write resolves
  the user from the token and scopes Firestore access to `users/{sub}/…`. No
  endpoint accepts a client-supplied user id (no IDOR). This scoping is a hard
  requirement and is covered by tests.
- **Admin:** drug/equipment/exercise catalog mutation is gated by `@AdminOnly`
  (backend) against an allow-list, requiring a **verified** email; the web mirrors
  the gate at `apiFetch` for `/api/admin/**`. Admin surfaces are web-only.

## 3. Encryption

- **In transit:** TLS everywhere (Cloud Run ingress; clients call HTTPS only;
  Android disallows cleartext).
- **At rest (server):** Firestore-managed encryption. OAuth/refresh-token secrets
  are additionally **envelope-encrypted** with a KMS-wrapped DEK (AES-256-GCM,
  per [ADR-0004] / `KmsTokenCipher`); refresh-token *secrets* are only ever
  stored as SHA-256 hashes.
- **At rest (Android):** the offline mirror is a **SQLCipher-encrypted** Room DB
  whose passphrase is wrapped by a non-exportable Android Keystore key
  (ADR-0007); the encrypted DB + passphrase are excluded from cloud backup and
  device-to-device transfer.

## 4. Consent & sub-processors

Health data is shared with these processors in the course of providing the
service; a user's use of the corresponding feature is the point of consent:

| Sub-processor | Data | Purpose |
|---|---|---|
| Google Cloud (Firestore, Cloud Run, KMS, GCS) | all stored data | hosting, storage, encryption |
| Google Health API | activity/device data | ingestion from Fitbit hardware |
| Google Gemini | text/images the user submits (lab PDFs, meal photos, chat) | extraction & AI features |

A user-facing **privacy policy** is published at `website/public/privacy.html`
(https://tesseta.com/privacy, last updated 2026-07-28). **However, the published
policy currently promises capabilities that are not built** (audit finding
COMP-001, 2026-09-13):

- §6/§7 promise record- and account-level **deletion** — no server-side account
  deletion or export exists (see §5 below).
- §5 promises "you can only ever reach your own records" — meal photos are
  currently served from a **public-read GCS bucket** (audit SEC-012).
- §3 promises Gemini content "is not used to train third-party models" — true
  only on the paid API tier, which is unverified.

Until those gaps close (or the policy is edited to match reality), treat the
policy text as aspirational, not descriptive. _(required, still open)_ an explicit
**consent record** (what was granted, when) — consent today is implicit in
feature use, and neither client links the policy in-product (audit COMP-002).

## 5. Data-subject rights _(required)_

- **Deletion:** a documented, testable path to delete all of a user's data
  (`users/{sub}/**`, refresh tokens, GCS objects, and the on-device mirror on
  sign-out — the last is implemented per ADR-0007). Server-side full-account
  deletion is not yet built.
- **Export:** a path to export a user's data in a portable format. Not yet built.
- **Retention:** a defined retention schedule per data class. Not yet defined;
  today data is retained indefinitely until deletion.

## 6. Logging & observability

- No PHI or tokens in logs. OkHttp logging is at `BASIC` (no bodies/headers);
  auth code logs exceptions, never token values; the webhook logs the health user
  id but not payloads. This is a standing requirement for any new logging.

## 7. Webhook integrity

The Google Health webhook re-hydrates data from Google's API using the user's own
OAuth token (it does not trust the webhook body's values), so a forged webhook
cannot inject fabricated health values. **ECDSA signature verification is
implemented but off by default** — enabling it (`GOOGLE_HEALTH_SIGNATURE_VERIFY`)
is _(required)_ once live signatures are confirmed to validate, to remove
reliance on the shared secret alone.

## 8. Open compliance items (tracked)

- **Reconcile the published privacy policy with reality** (§4; audit COMP-001):
  either ship deletion / privatize the photo bucket / verify the Gemini paid
  tier, or amend the policy — the published promises must not outrun capability.
- In-product policy links + consent/acceptance records (§4; audit COMP-002).
- Account deletion + data export endpoints; retention schedule (§5).
- Enable webhook signature verification; rotate the webhook secret (§7).
- Formal HIPAA/GDPR assessment and a sub-processor DPA review before external
  launch.

[ADR-0004]: ../decisions/ADR-0004-google-health-refresh-token-storage.md
