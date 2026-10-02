import SwiftUI

/// Sign-in screen (IMPL-IOS-01 Phase 2B).
///
/// Parity: Android's sign-in / onboarding entry. A single "Continue with Google"
/// action drives `GoogleSignInService` → `/api/auth/exchange`. Sign in with Apple
/// is deliberately absent in v1 (D13: TestFlight-only defers the App Store 4.8
/// mandate). In DEBUG builds a dev-login shortcut (backend-minted session, no
/// Google) is offered for the simulator + E2E harness (IMPL-E2E-01).
struct SignInView: View {
    @Environment(AuthState.self) private var auth
    @State private var service: GoogleSignInService?

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Text("HealthFitness")
                .font(.hfDisplayLg)
                .foregroundStyle(Theme.textPrimary)
            Text("Sign in to sync your data.")
                .font(.hfBodyMd)
                .foregroundStyle(Theme.textSecondary)

            if let message = auth.errorMessage, !message.isEmpty {
                Text(message)
                    .font(.hfBodySm)
                    .foregroundStyle(.red)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)
                    .accessibilityIdentifier("signin-error")
            }

            Button {
                Task { await resolvedService().signIn() }
            } label: {
                ZStack {
                    Text("Continue with Google")
                        .font(.hfBodyMd)
                        .opacity(auth.isSigningIn ? 0 : 1)
                    if auth.isSigningIn {
                        ProgressView().tint(.white)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .disabled(auth.isSigningIn)
            .padding(.horizontal, 32)
            .accessibilityIdentifier("signin-google-button")  // IMPL-E2E-01 shared id

            if AppConfig.allowDevLogin {
                Button("Dev sign-in (UAT)") {
                    Task { await resolvedService().devSignIn() }
                }
                .font(.hfBodySm)
                .foregroundStyle(Theme.textSecondary)
                .disabled(auth.isSigningIn)
                .accessibilityIdentifier("signin-dev-button")  // IMPL-E2E-01 shared id
            }

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .formMaxWidth()
        .background(Theme.canvas)
    }

    /// Lazily build the service bound to this view's `AuthState`. Kept in
    /// `@State` so a re-render doesn't spawn a fresh one mid sign-in.
    private func resolvedService() -> GoogleSignInService {
        if let service { return service }
        let created = GoogleSignInService(auth: auth)
        service = created
        return created
    }
}
