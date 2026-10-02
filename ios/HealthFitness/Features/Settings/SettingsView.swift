import SwiftUI
// import SharedCore  // SettingsViewModel, UnitsViewModel, CoachAudioSettingsViewModel,
//                       WorkoutPreferencesViewModel + their UiState — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A2 (Settings) — the settings HUB.
///
/// Parity target (Android): the settings graph — hub, profile, units, coach
/// audio, workout preferences, device connections (Withings OAuth via
/// ASWebAuthenticationSession), drinks, sync diagnostics. Each row navigates to
/// the matching feature view; the shared ViewModels
/// (shared/.../presentation/settings/*) own all logic, the views are pure
/// functions of their state.
///
/// Post-0D, the units / coach-audio / workout-prefs cards bind to the shared VMs:
///   @State var units = ObservableViewModel(UnitsViewModel(repo: DI.unitPrefs))
///   ...
/// and this file switches on their StateFlows via `.task { await …observe(…) }`.
struct SettingsView: View {
    @Environment(AuthState.self) private var auth

    // Local mirrors of the shared preference state (replaced by the SKIE-bridged
    // flows once the XCFramework is built — see ObservableBridge.swift).
    @State private var heightUnit: HeightUnitChoice = .feetInches
    @State private var weightUnit: WeightUnitChoice = .pounds
    @State private var temperatureUnit: TemperatureUnitChoice = .fahrenheit
    @State private var restBeep = true
    @State private var voiceAnnouncements = true

    // Settings › About (SettingsViewModel.versionName / versionCode).
    private let versionName = "—"      // vm.wrapped.versionName post-0D
    private let versionCode = 0        // vm.wrapped.versionCode post-0D

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                profileCard
                unitsCard
                coachAudioCard
                workoutPrefsCard
                connectionsCard
                drinksCard
                diagnosticsCard
                aboutCard
                accountCard
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Settings")
        .navigationDestination(for: SettingsRoute.self) { route in
            switch route {
            case .profile: ProfileView()
            case .units: UnitsView()
            case .connections: DeviceConnectionsView()
            case .drinks: DrinkSettingsView()
            case .diagnostics: SyncDiagnosticsView()
            }
        }
    }

    // MARK: Profile

    private var profileCard: some View {
        SettingsCard(title: "Profile", description: "Height, biological sex, date of birth") {
            settingsNavRow("Edit profile", subtitle: "Feeds your calorie estimate", to: .profile)
        }
    }

    // MARK: Units (UnitsViewModel)

    private var unitsCard: some View {
        // Routes to UnitsView, the first screen on real shared KMP state
        // (UnitsViewModel, NSUserDefaults-backed). Raw Kotlin/Native interop
        // (SKIE disabled for Xcode-26 compatibility).
        SettingsCard(title: "Units", description: "How measurements are shown") {
            settingsNavRow("Measurement units", subtitle: "Height, weight, temperature", to: .units)
        }
    }

    // MARK: Coach audio (CoachAudioSettingsViewModel)

    private var coachAudioCard: some View {
        SettingsCard(title: "Coach audio", description: "Hands-free cues during a workout") {
            ToggleRow(label: "Rest beep",
                      description: "Beep when a rest period ends",
                      isOn: $restBeep)          // .onChange → coach.setRestBeep($0)
            ToggleRow(label: "Voice announcements",
                      description: "Speak the exercise, weight, and reps at each set",
                      isOn: $voiceAnnouncements) // .onChange → coach.setVoiceAnnouncements($0)
        }
    }

    // MARK: Workout preferences (WorkoutPreferencesViewModel)

    private var workoutPrefsCard: some View {
        SettingsCard(title: "Workout preferences",
                     description: "Standing instructions the program designer honors") {
            WorkoutPreferencesEditor()
        }
    }

    // MARK: Device connections (WithingsViewModel + Google Health)

    private var connectionsCard: some View {
        SettingsCard(title: "Connections", description: "Sync from your scale and wearables") {
            settingsNavRow("Devices", subtitle: "Withings, Google Health", to: .connections)
        }
    }

    // MARK: Drinks (DrinkSettingsViewModel)

    private var drinksCard: some View {
        SettingsCard(title: "Drinks", description: "Manage your saved drinks") {
            settingsNavRow("My drinks", subtitle: "Add, edit, reorder, or archive", to: .drinks)
        }
    }

    // MARK: Diagnostics

    private var diagnosticsCard: some View {
        SettingsCard(title: "Diagnostics") {
            settingsNavRow("Sync log", subtitle: "Recorded sync activity", to: .diagnostics)
        }
    }

    // MARK: About (SettingsViewModel)

    private var aboutCard: some View {
        SettingsCard(title: "About") {
            HStack {
                Text("Version").font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text("\(versionName) (\(versionCode))")
                    .font(.hfMonoSm).foregroundStyle(Theme.textTertiary)
            }
        }
    }

    // MARK: Account (SettingsViewModel.signOut)

    private var accountCard: some View {
        SettingsCard(title: "Account") {
            Button("Sign out", role: .destructive) {
                // Post-0D: vm.wrapped.signOut { auth.signOut() }. The shared VM
                // clears the session; AuthState flips the root gate.
                auth.signOut()
            }
            .font(.hfBodyMd)
        }
    }

    // MARK: Helpers

    @ViewBuilder
    private func labeledChoice<Content: View>(_ label: String,
                                              @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            content()
        }
    }

    /// A value-based NavigationLink styled as the design-system NavRow (label +
    /// subtitle + trailing chevron). Keeps the row look while letting the nav
    /// stack own the transition.
    private func settingsNavRow(_ label: String,
                                subtitle: String? = nil,
                                to route: SettingsRoute) -> some View {
        NavigationLink(value: route) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                    if let subtitle {
                        Text(subtitle).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    }
                }
                Spacer(minLength: 12)
                Image(systemName: "chevron.right")
                    .font(.hfBodyMd).foregroundStyle(Theme.textTertiary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Routes

enum SettingsRoute: Hashable {
    case profile
    case units
    case connections
    case drinks
    case diagnostics
}

// MARK: - Local unit choice mirrors
// Mirror the shared `HeightUnit` / `WeightUnit` / `TemperatureUnit` enums so the
// SegmentedChoice options are typed; post-0D these map 1:1 to the SKIE-bridged
// Kotlin enums (SharedCore).

enum HeightUnitChoice: CaseIterable, Hashable {
    case feetInches, centimeters
    var label: String { self == .feetInches ? "ft / in" : "cm" }
}

enum WeightUnitChoice: CaseIterable, Hashable {
    case pounds, kilograms
    var label: String { self == .pounds ? "lb" : "kg" }
}

enum TemperatureUnitChoice: CaseIterable, Hashable {
    case fahrenheit, celsius
    var label: String { self == .fahrenheit ? "°F" : "°C" }
}
