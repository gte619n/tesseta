# iOS fastlane

fastlane automation for the IMPL-IOS-01 iOS app. These lanes back the CI
(`.github/workflows/ios-ci.yml`) and release
(`.github/workflows/release-ios-on-main.yml`) pipelines.

## Why GitHub Actions (not Cloud Build)

Every other deploy in this repo runs on Google Cloud Build on a merge to main.
The iOS release lane is the one exception: **Cloud Build has no macOS pool**, and
building/signing an iOS app and uploading to TestFlight requires a Mac. Per
decision **D14**, this deploy therefore runs on a `macos-15` GitHub Actions
runner. This is the only GitHub-Actions-based deploy in the project.

## Lanes → plan

| Lane | Maps to | What it does |
| --- | --- | --- |
| `beta` | release lane (`release-ios-on-main.yml`) | Sync signing (readonly match), build with `gym`, upload to the **internal-testers** TestFlight group. Upload retries up to 5x for parity with the android release lane. |
| `sync_certs` | code-signing bootstrap/refresh | `match appstore`; readonly in CI so runners only consume existing certs. |
| `tests` | local parity with `ios-ci` build-test job | Runs the suite on an **iPhone 16** and an **iPad Pro 11-inch (M4)** simulator. |

Build number for `beta` comes from `BUILD_NUMBER` (CI sets it to
`git rev-list --count HEAD`) and falls back to the same computation locally.

## Prerequisites — owner-provided (Phase 0E human steps)

The release lane cannot succeed until the owner completes Apple provisioning.
These are not code changes:

1. **Apple Developer Program** enrollment for the publishing team.
2. **App Store Connect app record** created with the app's bundle identifier.
3. **App Store Connect API key** issued (Keys tab) — gives the key id, issuer
   id, and a `.p8` private key.
4. **match certificate store** seeded once locally
   (`fastlane match appstore` with `readonly:false`) into the GCS bucket (D15).

## Secrets (D15)

Canonical copies live in **GCP Secret Manager** and must be **mirrored into
GitHub Actions repository secrets** — a macOS Actions runner has no
Secret Manager access. Required by `release-ios-on-main.yml`:

- `APP_STORE_CONNECT_API_KEY_ID`
- `APP_STORE_CONNECT_API_ISSUER_ID`
- `APP_STORE_CONNECT_API_KEY` — the `.p8`, **base64-encoded**
- `MATCH_PASSWORD`
- `MATCH_GIT_URL` — git-backed match fallback (GCS store uses
  `MATCH_GOOGLE_CLOUD_BUCKET_NAME` + `GOOGLE_APPLICATION_CREDENTIALS` instead)

Environment-driven config (Appfile / Matchfile placeholders):
`APP_IDENTIFIER`, `APPLE_ID`, `APPLE_TEAM_ID`, and optionally `ITC_TEAM_ID`,
`APP_SCHEME`, `MATCH_STORAGE_MODE`, `MATCH_GOOGLE_CLOUD_BUCKET_NAME`.
