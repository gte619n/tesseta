# UAT — Selenium end-to-end suite

Drives the **real web UI** against a **locally-booted backend + Firestore
emulator**, the way a user does. No GCP credentials, no Gemini key, no Google
sign-in — everything is localhost and reset-able.

## Run it

```bash
# One command: boots emulator + backend + web, runs this suite, tears down.
bash infra/scripts/uat.sh

# Leave the stack up to poke at it (sign in at /auth/dev):
bash infra/scripts/uat.sh --serve

# Against an already-running stack (e.g. while writing a flow):
cd uat && ./gradlew test \
  -Duat.webBaseUrl=http://localhost:3000 \
  -Duat.backendBaseUrl=http://localhost:8080
```

Port 8080 in use? `BACKEND_PORT=8095 bash infra/scripts/uat.sh`. Watch a run in a
real browser with `UAT_HEADLESS=false`.

## How it works (the three seams)

1. **Auth** — Google OAuth (`prompt=consent`) can't be automated, so UAT runs the
   backend with `app.auth.dev-login-enabled=true`, exposing `POST /api/auth/dev-login`
   which mints a **real backend session token** for any identity. The web app's
   `uat-dev` Credentials provider (gated by `UAT_AUTH_ENABLED=1`) calls it and
   stores the token as the session `idToken` — so the rest of the app, and the
   backend's session-token validation, are exercised unchanged. dev-mode stays
   **off**: tokens go through the same `sessionDecoder` Android uses in prod.
2. **Data** — backend points at the **Firestore emulator** (`FIRESTORE_EMULATOR_HOST`).
   Seed via the real REST API (`UatBackendClient`), wipe between runs via the
   emulator's `DELETE …/documents`. Per-test isolation is by **unique userId**.
3. **AI** — the AI-heavy features (goals/workout chat, DEXA, blood/nutrition
   capture, exercise media) are disabled; `UatStubConfig` supplies deterministic
   stubs for the two chat clients (+ the exercise enricher) so the context boots
   and chat SSE returns canned text. The medication feature is enabled with a
   *dummy* Gemini key (the client constructs without a network call) so its CRUD
   + custom-entry flows work — UAT never makes a real Gemini call.

Same three seams are what a future **Android** instrumented suite reuses: call
`/api/auth/dev-login`, point at the emulator, hit `10.0.2.2:8080`.

## Layout

```
support/
  UatConfig.kt        # endpoints + toggles from -D system properties
  Drivers.kt          # headless Chrome (Selenium Manager auto-fetches the driver)
  UatBackendClient.kt # dev-login, REST seeders, emulator wipe  ← the shared core
  TestUsers.kt        # per-test unique identities; admin()
  BaseUatTest.kt      # driver lifecycle, suite wipe, signIn() helper
pages/                # Page Objects (selectors target data-testid)
flows/                # @Test classes — one per feature flow
```

## Adding a flow

1. Add `data-testid` hooks to the page's key elements (nav link, create/add
   button, form inputs + submit, list rows, modal root). Convention:
   `data-testid="meds-add-btn"`.
2. Add a Page Object in `pages/` that targets those testids.
3. Add a `*FlowTest : BaseUatTest()` in `flows/`. Seed with `backend.*` as a fresh
   `TestUsers.fresh("...")`, `signIn(user)`, act through the Page Object, assert on
   the UI and/or read back through `backend.getJson(...)`.

## Flow catalog

| # | Flow | Status | What it covers |
|---|------|--------|----------------|
| 1 | Auth & shell | ✅ | dev sign-in, seeded data renders, unauth redirect |
| 2 | Dashboard | ✅ | seed blood reading → blood panel surfaces it |
| 3 | Profile | ✅ | edit height through UI → PATCH round-trip |
| 5 | Nutrition | ✅ | set macro target through UI → persisted |
| 6 | Goals | ✅ | create goal (goal→phase) through UI → list + backend |
| 7 | Workouts | ✅ | create gym through UI → list + backend |
| 8 | Body composition | ✅ | no Google Health → "Connect" CTA renders (not an error) |
| 9 | Blood | ✅ | add manual reading via modal → persisted |
| 10 | Admin | ✅ | admin reaches equipment catalog; non-admin redirected |
| 4 | Medications | ✅ | add a custom medication via the modal → list + backend |

Extending an existing flow (e.g. nutrition entry add/edit/delete, goal step
toggle, gym edit/delete) follows the same add-testid → page-object → test loop.

Features whose only path is real Gemini / file upload (DEXA PDF, blood PDF, drug
visual lookup, meal-photo capture) are intentionally out of automated scope while
AI is stubbed; tests assert the UI degrades gracefully (e.g. body-composition,
the blood/dashboard panels render readings even when the report subsystem is off).
A future opt-in "real-Gemini smoke" tier can cover the AI paths directly.
