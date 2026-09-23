import SwiftUI

/// Observable auth coordinator (IMPL-IOS-01 Phase 2A).
///
/// Parity: mirrors Android's `AuthCoordinator`. The three-way status drives the
/// root gate in `RootView`. The launch path is offline-first (D6): on cold start
/// we read the Keychain token cache synchronously and go straight to `.signedIn`
/// if a session exists — no blocking network call. Silent token refresh and the
/// full `/api/auth/exchange` handshake are wired in Phase 2B via
/// `GoogleSignInService`; here we own only the state machine + token cache.
@Observable
@MainActor
final class AuthState {
    enum Status: Equatable {
        case loading
        case signedOut
        case signedIn
    }

    private(set) var status: Status = .loading

    private let tokenStore: KeychainTokenStore

    init(tokenStore: KeychainTokenStore) {
        self.tokenStore = tokenStore
    }

    /// Offline-first cached-session launch (parity with Android
    /// AuthCoordinator): resolve auth from the Keychain, no network.
    func restoreCachedSession() {
        if tokenStore.hasSession {
            status = .signedIn
        } else {
            status = .signedOut
        }
    }

    /// Called by `GoogleSignInService` after a successful `/api/auth/exchange`
    /// (Phase 2B). Persists tokens and flips to signed-in.
    func didSignIn(accessToken: String, refreshToken: String) {
        tokenStore.save(accessToken: accessToken, refreshToken: refreshToken)
        status = .signedIn
    }

    /// Sign-out wipe. Parity trap (known on Android as the account-switch data
    /// leak class, `SignOutSideEffects`): clearing tokens is NOT enough — the
    /// full wipe must also clear the local mirror DB and the outbox before the
    /// next account signs in. That teardown lands with the shared core in 2B;
    /// tracked here so the shell doesn't ship a half-wipe.
    func signOut() {
        tokenStore.clear()
        // TODO(2B): SignOutSideEffects parity — wipe mirror DB + outbox via the
        // shared core so a subsequent account cannot read the previous user's
        // cached data.
        status = .signedOut
    }
}
