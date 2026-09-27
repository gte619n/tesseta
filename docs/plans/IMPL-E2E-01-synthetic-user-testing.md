# IMPL-E2E-01 — Synthetic user testing across iOS, Android, and web

> Status: **scaffolded (local-first)** · Created 2026-09-27 · Owner request:
> "full synthetic user testing for all features with UI tests for all three
> platforms," run locally on the M4 at ~$0.

## Goal

End-to-end UI tests that drive each client like a real user through every
feature, authored **once per journey** and run on all three platforms, executing
locally on the M4 Mac (near-zero marginal cost) with an occasional real-device
cloud pass for fragmentation.

## Architecture: one journey catalog, three runners

The failure mode is three divergent suites. We avoid it the same way the KMP
core avoids contract drift — **define journeys once, map each platform to them**:

- **Journey catalog = the parity matrix.** `docs/plans/ios-parity-matrix.md`
  already enumerates every screen/feature; each row owns one or more user
  journeys (id `J<n>`). That file is the single source of truth for "what a
  synthetic user does," and every platform's test maps to the same journey ids.
- **Mobile (iOS + Android): Maestro.** The *same* flow YAML under `maestro/flows/`
  runs on both — Maestro targets the accessibility layer, so one flow drives
  SwiftUI and Compose alike. Chosen over Appium for built-in auto-wait/retry
  (kills most E2E flakiness) and readable declarative flows.
- **Web: Playwright.** Already wired (`web/playwright.config.ts`, `web/e2e/`);
  mature, best-in-class trace observability. Journeys live in `web/e2e/journeys/`.

## The shared id vocabulary (the linchpin)

For one Maestro flow to drive both native apps, an element must carry the **same
identifier** on every platform:

| Platform | Mechanism |
|---|---|
| iOS (SwiftUI) | `.accessibilityIdentifier("<id>")` |
| Android (Compose) | `Modifier.testTag("<id>")` (+ `semantics { testTagsAsResourceId = true }` so Maestro/UiAutomator can see it) |
| Web (React) | `data-testid="<id>"` |

Ids are kebab-case, feature-scoped. Seeded so far (extend per journey):
`signin-google-button`, `signin-devlogin-button`, `first-sync-gate`,
`today-dashboard`, `meds-list`, `meds-add-button`, `med-add-screen`. Tab-bar /
bottom-nav items are targeted by their **visible title** ("Today", "Medications")
rather than an id — robust across iOS's `Tab` API and Android's bottom nav.

## What runs today vs. what's gated

| Leg | Status |
|---|---|
| **Web (Playwright)** | ✅ Runs now — `scripts/e2e/run-local.sh --platform web`. Route-mocked, boots `next dev`, no backend needed. |
| **Android (Maestro)** | ⚙️ Flows authored + valid; needs the arm64 emulator (`scripts/e2e/boot-android.sh`) + the debug APK installed, and `testTag`s added to the shipping Compose screens (per-journey work). Runner self-skips green until then. |
| **iOS (Maestro)** | ⚙️ Flows authored + valid; `accessibilityIdentifier`s seeded on the shell screens. Blocked on the SwiftUI app building (IMPL-IOS-01 Phase 0D → XCFramework) + install on a booted simulator. Runner self-skips green until then. |

The runner scripts self-skip a leg (exit green) when its device/app isn't
present, so the workflow is usable incrementally as each platform comes online.

## Determinism (the hard part, not the UI driving)

Synthetic journeys need seeded state or they flake. Use the backend **`dev-login`**
(non-prod test-user mint) to start each journey from a known account, and seed
per-journey state via the API before the flow. The flagship cross-cutting
journey is **cross-client convergence**: act on iOS → assert it appears on
Android + web through the real sync round-trip (the live-system analogue of the
shared `SyncConvergenceTest`).

## Running locally (M4)

```bash
scripts/e2e/setup-mac.sh                    # one-time: maestro, arm64 image, playwright browsers
scripts/e2e/boot-android.sh                 # boot the arm64 emulator (Android leg)
scripts/e2e/run-local.sh --subset           # fast per-PR spine (smoke tag), all platforms
scripts/e2e/run-local.sh                     # full suite, all platforms
scripts/e2e/run-local.sh --platform web      # one platform
scripts/e2e/run-local.sh --flow maestro/flows/auth/sign-in-first-sync.yaml
```

Cost ≈ electricity (~1–2¢/run). Wall-clock: subset ~5–10 min, full suite
~30–90 min depending on device parallelism (RAM-bound).

## Self-hosted runner (CI on the M4)

`.github/workflows/e2e-local.yml` runs on a runner labelled
`[self-hosted, macOS, ARM64, e2e]` — PRs run the smoke subset (merge gate),
nightly runs the full suite. To register the mini:

```bash
# GitHub → repo Settings → Actions → Runners → New self-hosted runner (macOS/arm64)
# follow the token'd ./config.sh, add labels: e2e
./run.sh    # or install as a launchd service: ./svc.sh install && ./svc.sh start
```

## Real-device coverage

Local sims/emulators miss OEM fragmentation (worst on Android). Budget an
occasional **Firebase Test Lab** real-device pass for release candidates (fits
the existing GCP/Firebase footprint) — the same Maestro flows upload there.

## Next steps (grow coverage)

1. Add `testTag`s to the shipping Android screens for J1/J3 (Compose,
   `testTagsAsResourceId`), then wire APK build + install into the Android leg.
2. Add a `dev-login` affordance (`signin-devlogin-button`) to the sign-in
   surfaces (all 3) so journeys authenticate without the real Google flow.
3. Fan out journeys from the parity matrix — one Maestro flow + one Playwright
   spec per row — starting with the highest-value spine (auth, log meal, log
   workout, med reminder, cross-client convergence).
