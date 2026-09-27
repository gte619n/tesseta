import SwiftUI

/// "Setting up" screen shown after sign-in until the shared SyncEngine reports
/// `firstSyncComplete` (IMPL-IOS-01 Phase 2C).
///
/// Parity: Android's SettingUpScreen — the first full delta pull of the whole
/// account can take a moment, so we hold the main UI behind this gate rather than
/// render half-populated dashlets.
struct FirstSyncGateView: View {
    @Environment(SyncBridge.self) private var sync

    var body: some View {
        VStack(spacing: 20) {
            ProgressView()
                .controlSize(.large)
                .tint(Theme.accent)
            Text("Setting up")
                .font(.hfHeadingLg)
                .foregroundStyle(Theme.textPrimary)
            Text("Syncing your data for the first time. This only happens once.")
                .font(.hfBodyMd)
                .foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 40)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.canvas)
        .accessibilityIdentifier("first-sync-gate")  // IMPL-E2E-01 shared id
        .task { sync.start() }
    }
}
