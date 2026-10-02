import Foundation
import SwiftUI

/// Observable auth coordinator (IMPL-IOS-01 Phase 2A shell → 2B wiring).
///
/// Parity: mirrors Android's `AuthCoordinator` + `GoogleAuthRepository` state.
/// The three-way `status` drives the root gate in `RootView`. The launch path is
/// offline-first (D6): on cold start we read the Keychain token cache
/// synchronously and go straight to `.signedIn` if a session exists — no blocking
/// network call. `GoogleSignInService` owns the interactive Google handshake and
/// the `/api/auth/exchange` call; here we own the state machine, the token cache,
/// and the (presentation-only) identity decoded from the access token.
@Observable
@MainActor
final class AuthState {
    enum Status: Equatable {
        case loading
        case signedOut
        case signedIn
    }

    private(set) var status: Status = .loading

    /// True while an interactive sign-in is in flight — drives the SignInView
    /// spinner + disables the buttons. Parity with Android's AuthState.Loading
    /// during interactiveSignIn().
    private(set) var isSigningIn = false

    /// Last sign-in failure, surfaced inline on `SignInView`. Mirrors Android's
    /// `AuthState.Failed(message)`.
    var errorMessage: String?

    /// Presentation-only identity decoded from the access token JWT (unverified;
    /// the server is the authority). Populated on sign-in and cached-session
    /// restore so Settings can show the account without a network call.
    private(set) var displayName: String?
    private(set) var email: String?

    private let tokenStore: KeychainTokenStore

    init(tokenStore: KeychainTokenStore) {
        self.tokenStore = tokenStore
    }

    /// Offline-first cached-session launch (parity with Android
    /// AuthCoordinator): resolve auth from the Keychain, no network.
    func restoreCachedSession() {
        if let session = tokenStore.session {
            applyIdentity(from: session.accessToken)
            status = .signedIn
        } else {
            status = .signedOut
        }
    }

    func beginSignIn() {
        isSigningIn = true
        errorMessage = nil
    }

    func failSignIn(_ message: String) {
        isSigningIn = false
        errorMessage = message
    }

    /// Called by `GoogleSignInService` after a successful `/api/auth/exchange`
    /// (or dev-login). Persists the full token pair + expiries and flips to
    /// signed-in.
    func didSignIn(_ tokens: AuthApi.TokenResponse) {
        tokenStore.save(
            accessToken: tokens.accessToken,
            accessTokenExpiresAt: tokens.accessTokenExpiresAt,
            refreshToken: tokens.refreshToken,
            refreshTokenExpiresAt: tokens.refreshTokenExpiresAt)
        applyIdentity(from: tokens.accessToken)
        isSigningIn = false
        errorMessage = nil
        status = .signedIn
    }

    /// Sign-out wipe. Parity trap (known on Android as the account-switch data
    /// leak class, `SignOutSideEffects`): clearing tokens is NOT enough — the
    /// full wipe must also clear the local mirror DB and the outbox before the
    /// next account signs in. That teardown lands with the shared core in 2C;
    /// tracked here so the shell doesn't ship a half-wipe.
    func signOut() {
        tokenStore.clear()
        displayName = nil
        email = nil
        // TODO(2C): SignOutSideEffects parity — wipe mirror DB + outbox via the
        // shared core so a subsequent account cannot read the previous user's
        // cached data.
        status = .signedOut
    }

    // MARK: - Identity (presentation-only)

    private func applyIdentity(from accessToken: String) {
        let claims = Self.decodeJWTClaims(accessToken)
        displayName = claims["name"] as? String
        email = claims["email"] as? String
    }

    /// Decode a JWT payload's claims. Unverified — the backend is the authority;
    /// these drive display only. Mirrors Android's `decodeClaims`. `nonisolated`
    /// because it's pure (touches no actor state) and is called from tests too.
    nonisolated static func decodeJWTClaims(_ jwt: String) -> [String: Any] {
        let parts = jwt.split(separator: ".")
        guard parts.count >= 2 else { return [:] }
        var base64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        // Re-pad to a multiple of 4 for Foundation's base64 decoder.
        while base64.count % 4 != 0 { base64.append("=") }
        guard let data = Data(base64Encoded: base64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return json
    }
}
