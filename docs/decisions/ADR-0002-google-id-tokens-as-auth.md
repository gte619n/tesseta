# ADR-0002: Google ID tokens (direct, not Firebase Auth) as the only auth mechanism

- Status: Accepted
- Date: 2026-05-21

## Context

The backend needs to know which user is making each request to `/api/me/*`,
and the three clients (web, Android phone, Wear OS) need a sign-in flow.
Three approaches were considered:

1. **Direct Google ID tokens.** Each client obtains a Google ID token (JWT
   issued by `https://accounts.google.com`). Backend validates against
   Google's published JWKS. The token's `sub` claim is the user ID.
2. **Firebase Auth wrapper.** Each client uses the Firebase SDK to sign in
   with Google. Firebase exchanges the Google token for a Firebase ID token
   with a stable Firebase UID, and the backend validates against Firebase's
   JWKS instead.
3. **Server-side session cookies.** Backend runs the OAuth2 dance server-
   side (Spring `oauth2-client`), issues an opaque session cookie, validates
   the cookie on every request. Android would need a parallel token-exchange
   endpoint.

Constraints that influenced the choice:

- **ADR-0001** says clients never read or write Firestore directly — the
  backend is the only Firestore client. This removes the usual decisive
  advantage of Firebase Auth (Firestore security rules that read
  `request.auth.uid` only work with Firebase ID tokens).
- **Wear OS** cannot run a browser-based OAuth dance reliably and Firebase
  Auth's Wear story is also weak. Both options route through the paired
  phone in practice.
- The OAuth2 starter wired in IMPL-00 was left inert deliberately — there is
  no sunk cost to keeping or removing it.
- One-hour token expiry is the same in all three options; long-lived sign-in
  is a client-side concern (refresh tokens on web, silent Credential Manager
  refresh on Android, phone-to-wear relay on Wear).

## Decision

Adopt **direct Google ID tokens** as the sole authentication mechanism.

- Backend runs as an OAuth2 resource server only. The
  `spring-boot-starter-oauth2-client` dependency is removed.
- Backend validates every `/api/me/*` request against Google's JWKS at
  `https://www.googleapis.com/oauth2/v3/certs`, with allowed audiences
  configured per environment (one client ID per platform).
- The user ID is the verified `sub` claim, used directly as the Firestore
  document ID `users/{sub}`.
- Clients obtain ID tokens through their platform-native paths:
  - Web: Auth.js v5 with the Google provider; ID token forwarded as a
    bearer header on every backend call.
  - Android phone: Credential Manager with `GetGoogleIdOption`.
  - Wear OS: phone-to-wear relay over the Wearable Data Layer.

## Consequences

Positive:

- One JWT format and one verification path across all three clients.
- No Firebase SDK on any client; no Firebase project to configure.
- The backend stays a pure resource server. There is no session store, no
  refresh-token storage, no cookie domain to manage.
- Sign-out is local to each client — no server state to clear.

Negative:

- The `sub` claim is opaque to the user (e.g. `108527834729384759201`),
  which is fine for IDs but means logs and admin tools will need to render
  email or display name alongside it.
- Adding a second auth provider (Apple Sign-In, email/password, anonymous
  accounts) requires building the unification ourselves — there is no
  pre-existing UID layer to inherit. A one-time migration script
  (`users/{googleSub}` → `users/{newCanonicalId}`) would be needed.
- We cannot revoke a single device's session globally without a custom
  denylist; this is out of scope for IMPL-02 and accepted for now.

## Revisit when

- A second sign-in provider becomes scope (Apple, email/password) — at
  that point Firebase Auth's UID unification or a custom equivalent must
  be designed before the second provider ships.
- Clients need direct Firestore access for low-latency reads — Firestore
  security rules require Firebase ID tokens, which would force the switch.
- The user base exceeds the size at which a one-time UID migration is
  cheap to execute.
