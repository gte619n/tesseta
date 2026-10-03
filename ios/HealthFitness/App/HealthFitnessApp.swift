import SwiftUI
import GoogleSignIn
import SharedCore

/// App entry point (IMPL-IOS-01 Phase 2A shell).
///
/// Parity: mirrors the Android launch flow — an offline-first, cached-session
/// launch. On cold start `AuthState` synchronously reads the Keychain token
/// cache (`KeychainTokenStore`, D6) and, if a session is present, goes straight
/// to the main UI while sync runs in the background (Android's
/// `AuthCoordinator` cached-session launch: no blocking network call on start).
///
/// The root DI container (`AppState`) is created once here and injected into the
/// environment; per-screen `@Observable` bridges over shared KMP ViewModels
/// (via SKIE) hang off it. In Phase 2A everything below the shell is stubbed.
@main
struct HealthFitnessApp: App {
    /// Root DI/state container, owned for the app's lifetime.
    @State private var appState = AppState()
    @Environment(\.scenePhase) private var scenePhase
    /// Background execution + push (BGTask + FCM), driven via the shared sync graph (D7/D8).
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    init() {
        // Configure the GoogleSignIn SDK once (D6). Safe no-op if the client ID
        // is unset — SignInView reports that condition to the user.
        GoogleSignInService.configure()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(appState)
                .environment(appState.auth)
                .environment(appState.sync)
                .onOpenURL { url in
                    // The Google OAuth redirect (reversed-client-id scheme) comes
                    // back through here first; hand it to the SDK before our own
                    // healthfitness:// deep-link routing (D12 — dose-checklist,
                    // nutrition-adjust-review, withings-callback).
                    if GIDSignIn.sharedInstance.handle(url) { return }
                    appState.handleDeepLink(url)
                }
                .onChange(of: scenePhase) { _, phase in
                    switch phase {
                    case .active:
                        // Foreground-activation delta pull (D8) — mitigates iOS
                        // silent-push throttling. Only once signed in + wired.
                        if appState.auth.status == .signedIn { appState.sync.pullOnForeground() }
                    case .background:
                        // (Re)schedule the background refresh + outbox-drain tasks (D8).
                        appDelegate.scheduleBackgroundWork()
                    default:
                        break
                    }
                }
        }
    }
}

/// Composition root. Holds the long-lived collaborators the shell wires up:
/// auth coordinator, sync bridge, and (later) the shared KMP component graph
/// exposed through SKIE. Kept deliberately small in 2A — feature ViewModels are
/// resolved lazily per screen.
@Observable
@MainActor
final class AppState {
    let auth: AuthState
    let sync: SyncBridge

    init() {
        let tokenStore = KeychainTokenStore()
        self.auth = AuthState(tokenStore: tokenStore)
        self.sync = SyncBridge()
        // Wire the shared KMP REST client + offline-sync graph once (Phase 1C /
        // Phase E-core): networked screens reuse the existing backend endpoints
        // with the Keychain session token login established; the mirror DB is
        // AES-GCM-encrypted via the Keychain cipher and tagged with a stable
        // per-install device id. On-device screens (units) don't need the client.
        IosComposition.shared.configure(
            baseUrl: AppConfig.backendBaseURL.absoluteString,
            tokenProvider: KeychainTokenProvider(store: tokenStore),
            cipher: KeychainPayloadCipher(),
            deviceId: DeviceIdentity.current)
        // Offline-first cached-session launch (parity with Android
        // AuthCoordinator): resolve auth state from the Keychain synchronously,
        // no network on the launch path.
        self.auth.restoreCachedSession()
    }

    /// Entry point for `healthfitness://` deep links (D12). The concrete router
    /// (dose-checklist / nutrition-adjust-review / withings-callback) is built in
    /// the feature waves; here we only capture the intent.
    func handleDeepLink(_ url: URL) {
        // TODO(Phase 3): route by url.host — mirrors Android's
        // NavController.handleDeepLink forwarding in AppNavHost.
        _ = url
    }
}
