# Android IMPL — Open Questions for Evan

This file collects questions, blockers, and assumptions surfaced by the
unattended overnight implementation of the IMPL-AND-00 .. IMPL-AND-06 specs
on the `feature/android_impl` branch.

Each implementing sub-agent appends to the relevant stage section below.
Review tomorrow morning.

---

## How to read this file

- **Question** — something that needs a decision before the work can be
  considered fully done. The agent made a best-guess assumption and the
  text of the assumption is recorded so you can confirm or reverse it.
- **Blocker** — something the agent could not work around. The work was
  partially landed or skipped. Marked with **BLOCKER** prefix.
- **Note** — useful context surfaced during implementation that doesn't
  need a decision (deferred follow-ups, surprising discoveries, etc).

When an item is resolved, replace its **Status:** line with `Resolved
{date} — {short note}`.

---

## Stage 00 — Foundations

### Note — `android/cloudbuild.yaml` updated for flavored build paths

**Status:** open — please review.

The IMPL-AND-00 spec adds `dev`/`staging`/`prod` flavors to `app/`, which
moves the release APK out from
`app/build/outputs/apk/release/app-release.apk` to
`app/build/outputs/apk/prod/release/app-prod-release.apk`, and replaces
the `:app:assembleRelease` umbrella task (which now assembles every
flavor's release variant) with `:app:assembleProdRelease` (only prod).

Updated `android/cloudbuild.yaml` so Cloud Build:
  - runs `:app:assembleProdRelease` instead of `:app:assembleRelease`;
  - pulls the APK from the flavored path for the Firebase App
    Distribution step and the artifacts upload.

If you'd rather Cloud Build distribute the `staging` variant during pre-
release weeks (so internal testers exercise the staging backend), flip
the task and path to `:app:assembleStagingRelease` /
`app-staging-release.apk`. No other CI files changed.

### Note — generated local `android/debug.keystore`

**Status:** informational, no action needed.

The repo gitignores `*.keystore` and ships no local debug keystore, so a
clean checkout doesn't have one. The implementing agent ran
`keytool -genkeypair ... -alias androiddebugkey -storepass android` to
unblock `:app:assembleDevDebug` (which references
`../debug.keystore` in the IMPL-02 signing config). The keystore stays
local and gitignored. If you want every developer to start from the same
SHA-1, either:
  - check the dev keystore into the repo (loosen `.gitignore`), or
  - document the `keytool` invocation in `android/README.md`.

### Note — `feature-*` modules now activate Hilt + KSP

**Status:** informational.

Per spec section "Feature modules": each empty placeholder module gains
Hilt + KSP plugins and an `implementation(project(":core-network"))`
line even though the modules contain no Kotlin code yet. This stabilises
Hilt's aggregating annotation-processor module list now so the first
real `@HiltViewModel` per feature doesn't re-trigger a full graph rescan.

### Note — `DispatcherModule` provider function names

**Status:** informational.

The spec sample uses provider names `fun io()`, `fun default()`,
`fun main()`. Kotlin allows `default` as an identifier in a function
position but Hilt's KSP processor errors with
`not a valid name: default`. Renamed to
`provideIoDispatcher` / `provideDefaultDispatcher` /
`provideMainDispatcher` — same `@Qualifier` annotations, same return
types. No behaviour change.

### Note — `AuthUiState.toLegacyAuthState()` shim

**Status:** informational, deferred to IMPL-AND-02.

Per spec, this IMPL keeps the existing `SignInScreen` untouched. The
screen still consumes the IMPL-02 `AuthState`. `AuthViewModel` exposes
`AuthUiState`; a small `toLegacyAuthState()` extension bridges the two
inside `app/`. The extension passes `idToken = ""` because
`SignInScreen` doesn't display it. Drop the extension once IMPL-AND-02
lifts the screen's signature to `AuthUiState`.

### Note — `:wear` does not depend on `:core-network` yet

**Status:** informational.

The wear app gains Hilt + the dev/staging/prod flavor scaffolding and
the `BACKEND_BASE_URL` BuildConfig field, but it does *not* take an
`implementation(project(":core-network"))` line in this IMPL. Wear has
nothing to call yet; the network module drops in for AND-08 when the
first wear-side surface needs the backend.

---

## Stage 01 — Dashboard live data

(no questions yet)

---

## Stage 02 — Settings & profile

(no questions yet)

---

## Stage 03 — Medications

(no questions yet)

---

## Stage 04 — Blood testing

(no questions yet)

---

## Stage 05 — Body composition & DEXA

(no questions yet)

---

## Stage 06 — Gym & equipment

(no questions yet)

---

## Cross-cutting

(no items yet)
