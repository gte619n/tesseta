# iOS Push (FCM → APNs) — activation steps (D7)

The push CODE is fully wired (`ios/HealthFitness/App/AppDelegate.swift`): Firebase
init, APNs registration, FCM-token → `PUT /api/me/devices/fcm`, and silent
`content-available` push → delta pull + outbox drain. It is **guarded on a bundled
`GoogleService-Info.plist`** — absent that file the app runs normally (BGTask +
foreground pull) and simply receives no pushes. Activating push needs these
owner/deployment steps (they involve real Firebase + Apple secrets, which can't live
in the repo):

## 1. Firebase: add the iOS app
- Firebase project: **`health-fitness-160`** (project number `146599669983`) — the
  same project the Android app uses (`android/app/google-services.json`).
- In the Firebase console → Project settings → *Add app* → **iOS**, bundle id
  **`com.gte619n.healthfitness`** (matches `ios/project.yml`
  `PRODUCT_BUNDLE_IDENTIFIER`).
- Download the generated **`GoogleService-Info.plist`**.

## 2. Add the config to the app bundle (turnkey)
- Drop `GoogleService-Info.plist` into **`ios/HealthFitness/`** (the target's `sources`
  is the whole `HealthFitness/` folder), then `xcodegen generate`. It is now bundled,
  and `AppDelegate.configurePushIfAvailable()` will call `FirebaseApp.configure()` on
  next launch. (Do NOT commit it if it's considered secret — add to `.gitignore` and
  inject in CI, mirroring how `android/app/google-services.json` is handled.)

## 3. APNs auth key (Apple → Firebase)
- Apple Developer → Certificates, IDs & Profiles → **Keys** → create an **APNs Auth
  Key** (.p8); note the Key ID + your Team ID.
- Firebase console → Project settings → *Cloud Messaging* → *Apple app configuration*
  → upload the .p8 with Key ID + Team ID.
- Ensure the App ID has the **Push Notifications** capability and the app's
  provisioning profile includes it. (`UIBackgroundModes` remote-notification/fetch/
  processing are already declared in `Info.plist`.)

## 4. Verify (needs a real device — simulators don't get real APNs tokens)
- Launch on a device, accept the notification prompt. In the logs confirm an FCM token
  is obtained and the `PUT /api/me/devices/fcm {token, deviceId}` succeeds (check the
  backend `fcmTokens` registry for the user).
- Send a silent `sync` data push (`content-available: 1`) and confirm the app performs
  a delta pull + outbox drain in the background.

Until steps 1–3 are done the feature is dormant by design — no code change required to
turn it on, just the bundled config + APNs key.
