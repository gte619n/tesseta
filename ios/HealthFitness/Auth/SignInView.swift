import SwiftUI

/// Sign-in screen (IMPL-IOS-01 Phase 2A shell).
///
/// Parity: Android's sign-in / onboarding entry. A single "Continue with Google"
/// action drives `GoogleSignInService` (Phase 2B). Sign in with Apple is
/// deliberately absent in v1 (D13: TestFlight-only defers the App Store 4.8
/// mandate).
struct SignInView: View {
    @Environment(AuthState.self) private var auth

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Text("HealthFitness")
                .font(.hfDisplayLg)
                .foregroundStyle(Theme.textPrimary)
            Text("Sign in to sync your data.")
                .font(.hfBodyMd)
                .foregroundStyle(Theme.textSecondary)

            Button {
                // Phase 2B: GoogleSignInService(auth:).signIn()
            } label: {
                Text("Continue with Google")
                    .font(.hfBodyMd)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)
            .padding(.horizontal, 32)
            .accessibilityIdentifier("signin-google-button")  // IMPL-E2E-01 shared id

            Spacer()
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .formMaxWidth()
        .background(Theme.canvas)
    }
}
