import Foundation
// import GoogleSignIn   // SPM dependency declared in project.yml; wired in 2B.

/// Google Sign-In → backend session exchange (IMPL-IOS-01, D6). **STUB.**
///
/// Parity: mirrors Android's Credential Manager → `/api/auth/exchange` flow
/// (ADR-0010/0019). The GoogleSignIn iOS SDK yields a Google ID token; we POST
/// it to the backend, which mints an HS256 access token + a rotating refresh
/// token. Those go into `KeychainTokenStore` and `AuthState.didSignIn(...)`.
///
/// Backend prerequisite (D6): the iOS OAuth client ID must be added to
/// `OAUTH_ALLOWED_AUDIENCES` (config-only, Phase 0/2).
///
/// The real GoogleSignIn call + Ktor `/exchange` request land in Phase 2B; this
/// file pins the call shape so 2B is a fill-in, not a design.
@MainActor
final class GoogleSignInService {
    struct ExchangeResponse: Decodable {
        let accessToken: String
        let refreshToken: String
    }

    private let auth: AuthState

    init(auth: AuthState) {
        self.auth = auth
    }

    /// Present the Google Sign-In sheet, then exchange the ID token for a backend
    /// session. STUB — no network yet.
    func signIn() async throws {
        // 1) Present GoogleSignIn:
        //    let result = try await GIDSignIn.sharedInstance.signIn(
        //        withPresenting: rootViewController)
        //    let idToken = result.user.idToken?.tokenString
        //
        // 2) Exchange with the backend (parity: POST /api/auth/exchange):
        //    POST {baseURL}/api/auth/exchange
        //    Headers: Content-Type: application/json, X-Client: ios
        //    Body: { "provider": "google", "idToken": "<idToken>" }
        //    -> 200 { "accessToken": "...", "refreshToken": "..." }
        //
        // 3) Persist + flip state:
        //    auth.didSignIn(accessToken: resp.accessToken,
        //                   refreshToken: resp.refreshToken)
        _ = auth
        assertionFailure("GoogleSignInService.signIn is a Phase 2B stub")
    }
}
