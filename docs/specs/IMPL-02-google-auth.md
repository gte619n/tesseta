# IMPL-02: Google authentication across web, Android phone, Wear OS

## Goal

Every `/api/me/*` request to the backend arrives with a verified Google
identity. Each client has a sign-in flow that, once completed, persists
indefinitely until the user explicitly signs out. The existing
`/api/hello` endpoint remains public so the IMPL-00 smoke test continues
to pass.

See [ADR-0002](../decisions/ADR-0002-google-id-tokens-as-auth.md) for the
rationale behind using Google ID tokens directly instead of Firebase Auth.

## Scope

In scope:

- Backend rewritten as an OAuth2 resource server validating Google ID tokens.
- First-login provisioning of `users/{sub}` in Firestore.
- Web sign-in via Auth.js v5 with the Google provider, bearer-forwarding to
  the backend, indefinite session via refresh-token rotation.
- Android phone sign-in via Credential Manager `GetGoogleIdOption`, silent
  refresh on each app start, bearer-forwarding on all Retrofit calls.
- Wear OS sign-in via phone-to-wear token relay over the Wearable Data Layer.
- CORS allow-list for the web origin.
- Local development bypass enabled only in `application-test.yml`.
- Android release + shared debug keystore creation and registration.

Out of scope (deferred):

- Firebase Auth, Apple Sign-In, email/password, anonymous accounts.
- Server-side per-device session revocation (denylist).
- Firestore security rules. Clients never read or write Firestore directly
  per ADR-0001.
- Webhook authentication for Pub/Sub push subscriptions (lands with IMPL-05).

## Decisions

| Topic | Decision |
|---|---|
| Token format | Google ID token (JWT), issuer `https://accounts.google.com`. |
| User ID | The verified `sub` claim, used directly as `users/{sub}`. |
| Backend library | `spring-boot-starter-oauth2-resource-server`. Replace `spring-boot-starter-oauth2-client`. |
| Web sign-in | Auth.js v5 with `GoogleProvider`, `access_type=offline`, `prompt=consent`. |
| Web session | Encrypted cookie, `maxAge: 365d`, `updateAge: 24h`. Refresh-token rotation in the `jwt` callback. |
| Android sign-in | `androidx.credentials` Credential Manager with `GetGoogleIdOption`. |
| Android session | "Signed in once" flag in DataStore. Silent re-fetch via `setFilterByAuthorizedAccounts(true).setAutoSelectEnabled(true)` on every app start. |
| Wear sign-in | Phone-to-wear token relay over Wearable Data Layer (`MessageClient`, path `/auth/id-token`). |
| Audience claims | Three OAuth client IDs (web, android phone, android wear). Backend accepts all three. |
| Dev-mode bypass | `app.auth.dev-mode=true` accepts `X-Dev-User` header. Set only in `application-test.yml`. Production `application.yml` does not read the property. |

## Backend deliverables (`backend/`)

### Dependencies (`backend/gradle/libs.versions.toml`)

- Add `spring-boot-starter-oauth2-resource-server` (managed by the Spring BOM).
- Remove `spring-boot-starter-oauth2-client` from `app/build.gradle.kts`.

### Code

- `core/src/main/java/com/gte619n/healthfitness/core/auth/CurrentUser.java`
  Java record `(String userId, String email, String displayName)`.
- `core/src/main/java/com/gte619n/healthfitness/core/auth/CurrentUserProvider.java`
  Interface, request-scoped. Two implementations:
  - `JwtCurrentUserProvider` (in `app`) — reads `JwtAuthenticationToken`,
    extracts `sub`, `email`, `name`.
  - `DevHeaderCurrentUserProvider` (in `app`, conditional on
    `app.auth.dev-mode=true`) — reads `X-Dev-User`.
- `core/src/main/java/com/gte619n/healthfitness/core/user/UserService.java`
  `provisionIfAbsent(CurrentUser)` writes `users/{sub}` on first request.
  Pure Firestore call through `UserRepository` (interface in core, impl in
  persistence — implementation lands with IMPL-03 alongside the rest of
  the persistence layer; until then, the bean is a no-op shim that logs).
- `app/src/main/java/com/gte619n/healthfitness/SecurityConfig.java` rewrite:
  - `/api/hello`, `/actuator/health` → `permitAll`.
  - `/api/me/**` → `authenticated()`, with JWT issuer
    `https://accounts.google.com` and audiences from
    `app.auth.allowed-audiences`.
  - Anything else → 401.
- `app/src/main/java/com/gte619n/healthfitness/auth/UserProvisioningFilter.java`
  `OncePerRequestFilter` ordered after JWT validation. Calls
  `UserService.provisionIfAbsent`.
- `app/src/main/java/com/gte619n/healthfitness/auth/CorsConfig.java`
  `WebMvcConfigurer` reading `app.cors.allowed-origins` (CSV).
- `api/src/main/java/com/gte619n/healthfitness/api/auth/WhoAmIController.java`
  `GET /api/me` returns the resolved `CurrentUser`. Manual smoke-test endpoint.

### Config (`app/src/main/resources/application.yml`)

```yaml
app:
  auth:
    allowed-audiences: ${OAUTH_ALLOWED_AUDIENCES}  # CSV
    dev-mode: false
  cors:
    allowed-origins: ${CORS_ALLOWED_ORIGINS}       # CSV
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://accounts.google.com
```

The existing `spring.security.oauth2.client.registration: {}` block is
removed.

### Test config (`app/src/test/resources/application-test.yml`)

```yaml
app:
  auth:
    dev-mode: true
    allowed-audiences: test-audience
  cors:
    allowed-origins: http://localhost:3000
```

### Tests

- `SecurityConfigTest`
  - `/api/hello` → 200 with no auth.
  - `/api/me` with no `Authorization` → 401.
  - `/api/me` with JWT signed by a test key (mocked JWKS via WireMock) and
    correct audience → 200.
  - `/api/me` with wrong audience → 401.
- `DevHeaderAuthTest`
  - `/api/me` with `X-Dev-User: u1` and `app.auth.dev-mode=true` → 200,
    `CurrentUser.userId == "u1"`.
- `WhoAmIControllerTest`
  - Returns the fields from the resolved `CurrentUser`.

## Web deliverables (`web/`)

### Dependencies (`web/package.json`)

```jsonc
{
  "dependencies": {
    "next-auth": "5.0.0-beta.25"
  }
}
```

### Code

- `app/api/auth/[...nextauth]/route.ts`
  Auth.js handler. `GoogleProvider({ clientId, clientSecret, authorization:
  { params: { access_type: "offline", prompt: "consent", scope: "openid
  email profile" } } })`.
- `auth.ts` (project root)
  Auth.js config. `session: { strategy: "jwt", maxAge: 31536000,
  updateAge: 86400 }`. `callbacks.jwt` stores `id_token`,
  `refresh_token`, and `expires_at` from the Google response; on
  subsequent calls, if `expires_at < now + 5min`, exchanges the refresh
  token for a new ID token via Google's `/token` endpoint.
- `lib/api.ts`
  Server-only fetch wrapper. `getApiClient()` returns a function that
  calls `auth()` to get the current session, attaches
  `Authorization: Bearer ${session.id_token}` to every request to
  `${BACKEND_URL}`.
- `middleware.ts`
  Auth.js middleware. Unauthenticated requests to `/me` or `/(authed)/*`
  redirect to `/auth/signin`. `/auth/*` and `/api/auth/*` pass through.
- `app/auth/signin/page.tsx`
  Single "Continue with Google" button calling `signIn("google",
  { redirectTo: "/me" })`.
- `app/me/page.tsx`
  Server Component. Calls `/api/me` through `lib/api.ts`, renders
  `{userId, email, displayName}`. Includes a Sign Out form posting to
  `/api/auth/signout`.
- `.env.local.example`
  ```
  AUTH_GOOGLE_ID=
  AUTH_GOOGLE_SECRET=
  AUTH_SECRET=
  BACKEND_URL=http://localhost:8080
  ```

### Tests

- Playwright spec: visit `/me`, redirect to `/auth/signin`, mock Google
  with a test provider returning a canned JWT, return to `/me`, assert
  the user's email renders.

## Android phone deliverables (`android/`)

### Dependencies (`android/gradle/libs.versions.toml`)

```toml
[versions]
credentials = "1.3.0"
googleid = "1.1.1"

[libraries]
androidx-credentials = { module = "androidx.credentials:credentials", version.ref = "credentials" }
androidx-credentials-play-services-auth = { module = "androidx.credentials:credentials-play-services-auth", version.ref = "credentials" }
google-id = { module = "com.google.android.libraries.identity.googleid:googleid", version.ref = "googleid" }
```

Wire into `app/build.gradle.kts`. Add a `BuildConfig` field
`WEB_OAUTH_CLIENT_ID` populated from
`signingProperties["web.oauth.client.id"]` — required by Google: Android
sign-in via Credential Manager uses the **web** client ID as the
audience, not the Android client ID.

### Code

- `core-data/src/main/java/com/gte619n/healthfitness/data/auth/GoogleAuthRepository.kt`
  Wraps `CredentialManager`. Two methods:
  - `interactiveSignIn(): Result<IdTokenCredential>` — first sign-in,
    `setFilterByAuthorizedAccounts(false)`, UI shows account picker.
  - `silentRefresh(): Result<IdTokenCredential>` —
    `setFilterByAuthorizedAccounts(true).setAutoSelectEnabled(true)`, no
    UI when the user has previously signed in.
  - `signOut(): Unit` — `clearCredentialState()` plus DataStore wipe.
- `core-data/src/main/java/com/gte619n/healthfitness/data/auth/IdTokenCache.kt`
  DataStore-backed, stores `idToken: String`, `expiresAt: Long`,
  `signedIn: Boolean`.
- `core-data/src/main/java/com/gte619n/healthfitness/data/auth/AuthInterceptor.kt`
  OkHttp `Interceptor`. Reads cached token, attaches bearer header. If
  cached token is within 60s of expiry, calls `silentRefresh()` first.
- `core-data/src/main/java/com/gte619n/healthfitness/data/auth/TokenRefreshAuthenticator.kt`
  OkHttp `Authenticator`. On 401, calls `silentRefresh()` and retries
  once.
- `core-data/src/main/java/com/gte619n/healthfitness/data/di/NetworkModule.kt`
  Wire `AuthInterceptor` + `TokenRefreshAuthenticator` into the Retrofit
  `OkHttpClient`.
- `app/src/main/java/com/gte619n/healthfitness/mobile/auth/SignInScreen.kt`
  Compose. Single button → `viewModel.signIn()`.
- `app/src/main/java/com/gte619n/healthfitness/mobile/auth/AuthViewModel.kt`
  Hilt-injected `GoogleAuthRepository`. Holds a `SignInState` sealed
  class: `Loading`, `SignedOut`, `SignedIn(user)`.
- `app/src/main/java/com/gte619n/healthfitness/mobile/MainActivity.kt`
  On launch: `viewModel.bootstrap()` calls `silentRefresh()` if
  `IdTokenCache.signedIn == true`, otherwise stays `SignedOut`. Shows
  `SignInScreen` or the existing dashboard accordingly.
- Add a Sign Out menu item to the dashboard top bar.

### Tests

- `GoogleAuthRepositoryTest` (Robolectric) with a fake `CredentialManager`
  returning a canned `GoogleIdTokenCredential`.
- `AuthInterceptorTest` (Robolectric) verifies:
  - Bearer header attached when cached token is fresh.
  - `silentRefresh` invoked when token within 60s of expiry.
- `TokenRefreshAuthenticatorTest` verifies single retry on 401.

## Wear OS deliverables (`android/wear/`)

### Dependencies (`android/gradle/libs.versions.toml`)

```toml
[versions]
wearableServices = "18.2.0"

[libraries]
google-play-services-wearable = { module = "com.google.android.gms:play-services-wearable", version.ref = "wearableServices" }
```

Wire into both `app/build.gradle.kts` (phone — `PhoneTokenPublisher`) and
`wear/build.gradle.kts` (wear — `PhoneTokenSyncService`).

### Code (phone side, in `android/app/`)

- `app/src/main/java/com/gte619n/healthfitness/mobile/wear/PhoneTokenPublisher.kt`
  After every successful `interactiveSignIn` / `silentRefresh`, iterates
  connected wear nodes via `Wearable.getNodeClient(this).connectedNodes`
  and sends the ID token bytes to path `/auth/id-token` via
  `Wearable.getMessageClient(this).sendMessage(...)`.
- Wire into `GoogleAuthRepository` as an injectable
  `TokenObserver` so the repository fires the publisher on every
  successful token fetch.

### Code (wear side, in `android/wear/`)

- `wear/src/main/java/com/gte619n/healthfitness/wear/auth/PhoneTokenSyncService.kt`
  `WearableListenerService`. On `MessageEvent(path = "/auth/id-token")`,
  persists the JWT to wear-side DataStore.
- `wear/src/main/java/com/gte619n/healthfitness/wear/auth/WearIdTokenCache.kt`
  DataStore-backed, mirror of phone-side cache.
- `wear/src/main/java/com/gte619n/healthfitness/wear/auth/AuthInterceptor.kt`
  Same shape as phone-side. On 401, sends a `MessageEvent` to phone path
  `/auth/refresh-request` and waits up to 5s for the phone's reply.
- `wear/src/main/java/com/gte619n/healthfitness/wear/auth/SignInRequiredScreen.kt`
  Compose. Single text "Sign in on your phone."
- `wear/src/main/AndroidManifest.xml`
  Register `PhoneTokenSyncService` with an intent filter for
  `com.google.android.gms.wearable.MESSAGE_RECEIVED` and path filter
  `/auth/`.

### Code (phone side — handle wear refresh requests)

- `app/src/main/java/com/gte619n/healthfitness/mobile/wear/PhoneRefreshRequestService.kt`
  `WearableListenerService` listening on `/auth/refresh-request`. Calls
  `GoogleAuthRepository.silentRefresh()` then re-publishes the new
  token via `PhoneTokenPublisher`.
- `app/src/main/AndroidManifest.xml`
  Register `PhoneRefreshRequestService`.

### Tests

- `PhoneTokenPublisherTest` (Robolectric) with a mocked `MessageClient`
  verifies the message path and payload.
- `WearAuthInterceptorTest` verifies the refresh-request fallback path.

## Infrastructure deliverables

### Android signing setup

`infra/scripts/setup-android-signing.sh`:

1. Generate `health-fitness-release.keystore` if absent.
2. Generate a shared `android/debug.keystore` checked into the repo
   (debug keystores are public-by-design).
3. Upload the release keystore + both passwords to Secret Manager:
   - `android-release-keystore` (binary)
   - `android-release-keystore-password`
   - `android-release-key-password`
4. Print SHA-1 fingerprints for both keystores.
5. Print the exact `gcloud` command and the Cloud Console URL needed to
   register the two Android OAuth clients (phone + wear).

Idempotent: re-running checks for existing artifacts and skips.

### Cloud Build changes (out of scope for this IMPL)

Android Cloud Build config does not exist yet. Adding it is a follow-up
IMPL — the signing helper makes the keystore available; CI consumption
lands later.

### GCP / Secret Manager additions

Manual one-time steps documented in `infra/README.md` updates:

1. Run `setup-android-signing.sh`, register the printed SHA-1 fingerprints.
2. In Cloud Console, create three OAuth 2.0 client IDs:
   - **Web** — type Web Application. Redirect URIs:
     `https://<web-cloud-run>/api/auth/callback/google`,
     `http://localhost:3000/api/auth/callback/google`.
   - **Android phone** — type Android. Package
     `com.gte619n.healthfitness`. SHA-1s from step 1.
   - **Android wear** — type Android. Same package. Same SHA-1s.
3. Store each client ID in Secret Manager:
   - `oauth-web-client-id`, `oauth-web-client-secret`
   - `oauth-android-phone-client-id`
   - `oauth-android-wear-client-id`
   - `oauth-allowed-audiences` — CSV of all three client IDs
   - `authjs-secret` — random 32-byte secret for Auth.js session
     encryption (`openssl rand -base64 32`)

## Acceptance

1. `curl -i $BACKEND_URL/api/hello` → 200, unchanged from IMPL-00.
2. `curl -i $BACKEND_URL/api/me` → 401.
3. `curl -i -H "Authorization: Bearer <real Google ID token>" $BACKEND_URL/api/me`
   → 200 with `{userId, email, displayName}` and a `users/{sub}` document
   exists in Firestore.
4. Visit `https://<web-cloud-run>/me` in a regular (non-incognito) Chrome
   window. Sign in with Google. `/me` renders the user's email. Close the
   browser, reopen days later, hit `/me` directly — still signed in.

   *Known caveat:* Chrome incognito blocks third-party cookies, which
   breaks Google's `accounts.youtube.com/SetSID` session-distribution
   step at the end of the OAuth flow. The user lands on a Google 400
   page instead of `/me`. This is structural to Google's OAuth + Chrome's
   incognito cookie policy, not a bug in our setup. Test sign-in in a
   regular window; if you want to use a clean profile, create a new
   Chrome profile rather than incognito.
5. Fresh install of the phone APK. Tap "Continue with Google", complete
   the picker. Dashboard loads. Force-stop. Reopen weeks later — no
   re-prompt, dashboard loads silently with a fresh token.
6. Pair phone with a wear emulator. Sign in on the phone. The wear app
   exits the "Sign in on your phone" screen within ~5s and successfully
   calls `/api/me`.
7. Sign Out on each client clears local state and forces the picker on
   the next sign-in attempt.
8. `./gradlew test` (backend) passes. `pnpm test` (web) passes. Android
   `connectedAndroidTest` passes on a single device.

## Open questions resolved before implementation

- **`sub` as userId** — Accepted. Migration plan documented in ADR-0002.
- **Long-lived sign-in** — Implemented via web refresh-token rotation +
  Android silent Credential Manager refresh.
- **Release keystore** — Generated by `setup-android-signing.sh` as part
  of this IMPL.
- **Dev-mode bypass** — Hard-gated to `application-test.yml` only.
