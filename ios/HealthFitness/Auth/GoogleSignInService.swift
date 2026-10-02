import Foundation
import UIKit
import GoogleSignIn

/// Google Sign-In → backend session exchange (IMPL-IOS-01, D6).
///
/// Parity: mirrors Android's `GoogleAuthRepository.interactiveSignIn` — the ONLY
/// path that shows Google account UI. It obtains a Google ID token once, then
/// exchanges it at `/api/auth/exchange` for a backend access + refresh pair
/// (ADR-0010). The backend validates the ID token's audience against
/// `OAUTH_ALLOWED_AUDIENCES`, so `AppConfig.googleIOSClientID` MUST be listed
/// there (D6).
///
/// Like Android, the whole interactive body is guarded: this is launched from a
/// UI action, so any escaping error is degraded to `AuthState.failSignIn(...)`
/// (rendered inline on `SignInView`) rather than crashing the app.
@MainActor
final class GoogleSignInService {
    private let auth: AuthState
    private let api: AuthApi

    init(auth: AuthState, api: AuthApi = AuthApi()) {
        self.auth = auth
        self.api = api
    }

    /// Configure the GoogleSignIn SDK once at launch. No-op (with a logged
    /// warning) if the iOS client ID is unset — the build is misconfigured, but
    /// we don't crash; the sign-in button will report the same condition.
    static func configure() {
        guard let clientID = AppConfig.googleIOSClientID else {
            NSLog("[GoogleSignIn] HF_GOOGLE_IOS_CLIENT_ID is unset — Google sign-in disabled.")
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(
            clientID: clientID,
            serverClientID: AppConfig.googleServerClientID)
    }

    /// Present the Google account sheet, then exchange the ID token for a backend
    /// session. Drives `AuthState` throughout (begin → didSignIn / failSignIn).
    func signIn() async {
        guard AppConfig.googleIOSClientID != nil else {
            auth.failSignIn("Sign-in isn't configured for this build (missing Google client ID).")
            return
        }
        guard let presenter = Self.topViewController() else {
            auth.failSignIn("Couldn't present sign-in (no active window).")
            return
        }

        auth.beginSignIn()

        let idToken: String
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let token = result.user.idToken?.tokenString else {
                auth.failSignIn("Google didn't return an ID token. Try again.")
                return
            }
            idToken = token
        } catch {
            // User cancel is not an error worth shouting about — clear the inline
            // message and bail quietly, matching a dismissed Credential Manager.
            if (error as NSError).code == GIDSignInError.canceled.rawValue {
                auth.failSignIn("")
                return
            }
            auth.failSignIn("Google sign-in failed: \(error.localizedDescription)")
            return
        }

        do {
            let tokens = try await api.exchange(googleIDToken: idToken)
            auth.didSignIn(tokens)
        } catch {
            auth.failSignIn(errorText(error))
        }
    }

    /// UAT / simulator path: skip Google entirely and have the backend mint a
    /// session (`/api/auth/dev-login`, non-prod only). Parity with the web UAT
    /// sign-in; used by the local E2E harness (IMPL-E2E-01).
    func devSignIn(userId: String = "ios-dev-user",
                   email: String = "ios-dev@example.com",
                   name: String = "iOS Dev") async {
        auth.beginSignIn()
        do {
            let tokens = try await api.devLogin(userId: userId, email: email, name: name)
            auth.didSignIn(tokens)
        } catch {
            auth.failSignIn(errorText(error))
        }
    }

    private func errorText(_ error: Error) -> String {
        (error as? AuthApi.AuthError)?.errorDescription ?? error.localizedDescription
    }

    /// The frontmost view controller to present the Google sheet from. SwiftUI
    /// gives us no direct handle, so we walk the active foreground window scene.
    static func topViewController() -> UIViewController? {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        guard var top = scene?.keyWindow?.rootViewController else { return nil }
        while let presented = top.presentedViewController { top = presented }
        return top
    }
}
