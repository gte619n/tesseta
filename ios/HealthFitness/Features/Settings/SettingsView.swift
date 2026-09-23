import SwiftUI

/// Settings hub (IMPL-IOS-01 Phase 2A stub — uses the real design-system
/// primitives to demonstrate them).
///
/// Parity target (Android): the settings graph — hub (SettingsRoutes.SETTINGS),
/// profile, drink settings, device connections (Withings OAuth via
/// ASWebAuthenticationSession), sync diagnostics (Wave A2).
/// Shared ViewModel (Phase 1D): SettingsViewModel.
struct SettingsView: View {
    @Environment(AuthState.self) private var auth
    @State private var useMetric = false
    @State private var units: String? = "imperial"

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Units", description: "Full profile settings land in Wave A2.") {
                    SegmentedChoice(
                        options: [("metric", "Metric"), ("imperial", "Imperial")],
                        selection: $units
                    )
                    ToggleRow(label: "Use metric", description: "kg, cm, °C", isOn: $useMetric)
                }
                SettingsCard(title: "Diagnostics") {
                    NavRow(label: "Sync log", subtitle: "Recorded sync activity") {}
                }
                SettingsCard(title: "Account") {
                    Button("Sign out", role: .destructive) { auth.signOut() }
                        .font(.hfBodyMd)
                }
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Settings")
    }
}
