# ADR-0010: Backend-issued session tokens for native clients

- Status: Accepted
- Date: 2026-06-04

## Context

[ADR-0002](ADR-0002-google-id-tokens-as-auth.md) made the Google ID token the
direct API bearer for all three clients. On Android that token was kept fresh by
calling Credential Manager (`CredentialManager.getCredential` with
`GetGoogleIdOption`, auto-select) — once on launch when the cache was stale, and
again on every `401` via the `TokenAuthenticator`.

That has a user-visible flaw: **`getCredential()` shows Google's "Signing you
in…" UI even on a silent / auto-select request.** When the app comes to the
foreground after the ~1h token has expired, the dashboard fires many requests at
once; each one 401s, and OkHttp invokes the `Authenticator` independently per
failed request. The result was the sign-in overlay flashing ~10 times in a row.
Well-behaved apps never show auth UI on a routine foreground.

The root cause is structural: a Google ID token is an *identity assertion*, not a
*refresh credential*. There is no UI-free way to re-mint one on Android — silent
refresh and the account picker are the same Credential Manager call. So no amount
of de-duplication removes the flash entirely; only one silent refresh per expiry
would remain, and it would still show UI.

Options considered:

1. **Single-flight the existing refresh.** Collapse the 401 storm to one
   `getCredential()`. Removes the *repeated* flash but not the one-per-expiry
   flash, because the call still shows UI.
2. **Exchange the Google token for backend-issued session tokens.** The Google
   token is used exactly once — at sign-in — to obtain a short-lived backend
   access token plus a long-lived refresh token. Refresh is then a plain HTTPS
   call with no Credential Manager involvement, so it is genuinely UI-free.
3. **Migrate everything (including web) to backend sessions.** Web already
   refreshes its Google token server-side (Auth.js) with no UI, so it has no
   problem to solve; migrating it only adds risk.

## Decision

Adopt **backend-issued session tokens for the native clients (Android phone +
Wear), while web stays on Google ID tokens.** The backend resource server
accepts both token families.

- **Access token** — a short-lived (1h) HS256 JWT minted by the backend
  (`SessionTokenService`), `iss=tesseta-backend`, `sub=<Google sub>`, carrying
  `email`/`name` so `CurrentUser` resolution is unchanged. HMAC (symmetric) was
  chosen over asymmetric/JWKS because the backend is the only issuer *and*
  validator — there is no external verifier to justify a public-key/JWKS setup.
  The signing key lives in Secret Manager (`SESSION_SIGNING_KEY`).
- **Refresh token** — an opaque 256-bit secret stored only as a SHA-256 hash in
  a top-level `refreshTokens/{tokenId}` collection (single direct read on
  refresh; no per-user scan). Rotated on every use; presenting an
  already-rotated token is treated as theft and revokes the user's whole family.
  60-day lifetime.
- **Endpoints** (`AuthController`): `POST /api/auth/exchange` (authenticated by a
  Google ID token in the header — the *only* call that needs one), `POST
  /api/auth/refresh` (public; opaque token in body), `POST /api/auth/logout`
  (public; revokes).
- **Resource server** routes by the `iss` claim
  (`SecurityConfig.jwtDecoder`): `accounts.google.com` → the existing Google
  decoder (web, Wear-relayed, and the exchange call); `tesseta-backend` → the
  HS256 decoder. Both yield a `JwtAuthenticationToken` with `sub`=userId, so
  `UserProvisioningFilter` and `CurrentUserProvider` are untouched. With no
  signing key configured the backend silently accepts Google tokens only.
- **Android** (`GoogleAuthRepository`): `interactiveSignIn()` is the sole
  Credential Manager caller — Google token → `/api/auth/exchange` → store.
  `silentRefresh()` is now a plain `/api/auth/refresh` HTTP call, single-flighted
  by a mutex so a 401 storm does one refresh and the rest reuse the freshly
  stored token. The bearer + refresh token live in the existing `IdTokenCache`
  (DataStore `hf-auth`). A dead refresh token clears the session → the sign-in
  screen (a real re-auth, where UI is expected).
- **Wear** keeps the phone-relay model: the phone now relays the *access* token,
  and a Wear `401` → `/auth/refresh-request` → the phone refreshes over HTTP and
  republishes. No Credential Manager on the refresh path, so nothing flashes.

## Consequences

### Positive

- Foregrounding never shows auth UI. Within the access-token TTL there is no
  network call at all; after expiry the refresh is silent HTTP. Credential
  Manager UI appears only on first sign-in and on a genuine re-auth.
- Refresh tokens are individually revocable (logout, rotation, theft response) —
  the per-device revocation gap called out in ADR-0002 is closed for native
  clients.
- Backwards-compatible rollout: the backend accepts Google tokens throughout, so
  an un-updated app keeps working with no flag day.

### Negative

- The backend is no longer a *pure* stateless resource server for native
  clients: it now mints tokens and stores refresh-token hashes (one small
  Firestore collection, one Secret Manager secret). ADR-0002's "no session
  store" property no longer holds for Android/Wear.
- Two token families to reason about; the decoder routes by an unverified `iss`
  peek (the chosen decoder still verifies the signature, so a forged issuer only
  mis-routes to a decoder that rejects it — never a trust bypass).
- `photoUrl` (Google `picture` claim) is absent from session-token requests; we
  never persist it, so `GET /api/me` returns a null photoUrl for native callers.
- Web and native now use different mechanisms — a deliberate asymmetry, not a
  unified model.

## Revisit when

- Apple/email sign-in is added (ties into the ADR-0002 UID-unification revisit)
  — the exchange endpoint becomes the natural single integration point.
- Web needs the same revocation guarantees, at which point migrating web onto
  the same session tokens (option 3) is worth the risk.
- Refresh-token volume or revocation-query patterns outgrow a flat
  `refreshTokens/` collection (e.g. needs a TTL/cleanup job or per-user index).
