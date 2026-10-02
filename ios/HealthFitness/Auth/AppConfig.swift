import Foundation

/// Build-time configuration for auth + networking (IMPL-IOS-01 Phase 2B).
///
/// Parity: mirrors Android's `BuildConfig.BACKEND_BASE_URL` /
/// `BuildConfig.WEB_OAUTH_CLIENT_ID` (android/app/build.gradle.kts). On iOS the
/// values are injected into the bundle's Info.plist from build settings (see
/// `project.yml` → `HF_BACKEND_BASE_URL` / `HF_GOOGLE_IOS_CLIENT_ID` /
/// `HF_GOOGLE_SERVER_CLIENT_ID`), so a release archive bakes in whatever the CI
/// lane exported, exactly like the Android Gradle resolution.
///
/// Defaults match Android: an unset base URL falls back to the deployed Cloud
/// Run service so a device/simulator build talks to prod without extra config.
enum AppConfig {
    /// Backend origin for the Ktor/URLSession clients. Trailing slash stripped so
    /// callers can append `api/...` paths unambiguously.
    static let backendBaseURL: URL = {
        let raw = nonEmpty(infoString("HF_BACKEND_BASE_URL"))
            ?? "https://health-fitness-backend-mbysudfbja-uc.a.run.app"
        let trimmed = raw.hasSuffix("/") ? String(raw.dropLast()) : raw
        // Force-unwrap is safe: the default is a valid URL and a malformed
        // override is a build misconfiguration we want to fail loudly on.
        return URL(string: trimmed)!
    }()

    /// The iOS OAuth client ID (`...apps.googleusercontent.com`). This is the
    /// `aud` of the Google ID token the sign-in SDK mints, so the backend's
    /// `OAUTH_ALLOWED_AUDIENCES` MUST include it (D6). Empty until configured —
    /// `GoogleSignInService` reports a clear error rather than crashing.
    static let googleIOSClientID: String? = nonEmpty(infoString("HF_GOOGLE_IOS_CLIENT_ID"))

    /// Optional: the WEB OAuth client ID. Passed to GoogleSignIn as
    /// `serverClientID` so the SDK can also return a `serverAuthCode` for offline
    /// access. NOT required for the ID-token exchange (the token's audience is
    /// the iOS client ID above), so this stays optional.
    static let googleServerClientID: String? = nonEmpty(infoString("HF_GOOGLE_SERVER_CLIENT_ID"))

    /// Whether the dev-login affordance (no Google account; backend mints a
    /// session directly) is offered. Backend-gated to non-prod regardless — the
    /// prod guard 404s `/api/auth/dev-login` (see AuthController.isDevLoginPermitted)
    /// — but we only surface the button in DEBUG so release UI stays clean.
    /// Parity: the web UAT dev sign-in (UAT_AUTH_ENABLED).
    static var allowDevLogin: Bool {
        #if DEBUG
        return true
        #else
        return false
        #endif
    }

    // MARK: - Info.plist helpers

    private static func infoString(_ key: String) -> String? {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nonEmpty(_ value: String?) -> String? {
        guard let value, !value.isEmpty else { return nil }
        return value
    }
}
