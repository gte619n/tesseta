import SwiftUI

/// Adaptive navigation root (IMPL-IOS-01 Phase 2A, D16).
///
/// Gating order mirrors Android's `MainActivity` → `AppNavHost`:
///   1. auth == .loading  → a lightweight splash (cached-session resolve is
///      synchronous, so this is momentary).
///   2. auth == .signedOut → `SignInView`.
///   3. auth == .signedIn  → the main shell, but first the sync gate: until the
///      shared SyncEngine reports `firstSyncComplete`, show `FirstSyncGateView`
///      ("Setting up", parity with Android SettingUpScreen).
///
/// Layout adaptivity (D16): Android switches on the 600dp width breakpoint
/// (`WindowWidthSizeClass`). The iOS equivalent is `horizontalSizeClass`:
///   - `.compact` (iPhone portrait) → `TabView` with a "More" tab.
///   - `.regular` (iPad, iPhone landscape max, multitasking wide) →
///     `NavigationSplitView` sidebar, all destinations flat in the sidebar
///     (no "More" bucket needed — parity with Android's tablet rail).
struct RootView: View {
    @Environment(AuthState.self) private var auth
    @Environment(SyncBridge.self) private var sync

    var body: some View {
        switch auth.status {
        case .loading:
            SplashView()
        case .signedOut:
            SignInView()
        case .signedIn:
            if sync.firstSyncComplete {
                MainShell()
            } else {
                FirstSyncGateView()
            }
        }
    }
}

/// Top-level destinations. Order matches the Android bottom-nav / rail.
enum AppDestination: String, CaseIterable, Identifiable, Hashable {
    case today
    case workouts
    case nutrition
    case medications
    case blood
    case bodyComposition
    case goals
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .today: "Today"
        case .workouts: "Workouts"
        case .nutrition: "Nutrition"
        case .medications: "Medications"
        case .blood: "Blood"
        case .bodyComposition: "Body"
        case .goals: "Goals"
        case .settings: "Settings"
        }
    }

    var systemImage: String {
        switch self {
        case .today: "square.grid.2x2"
        case .workouts: "figure.strengthtraining.traditional"
        case .nutrition: "fork.knife"
        case .medications: "pills"
        case .blood: "drop"
        case .bodyComposition: "figure"
        case .goals: "target"
        case .settings: "gearshape"
        }
    }

    /// Primary tabs shown directly on the compact (iPhone) tab bar. The rest live
    /// under "More" — parity with Android's MoreScreen feature directory.
    static let phonePrimary: [AppDestination] = [.today, .workouts, .nutrition, .medications]

    /// Destinations bucketed under "More" on phones.
    static let moreBucket: [AppDestination] = [.blood, .bodyComposition, .goals, .settings]

    @ViewBuilder
    var screen: some View {
        switch self {
        case .today: TodayView()
        case .workouts: WorkoutsHubView()
        case .nutrition: NutritionTodayView()
        case .medications: MedicationsListView()
        case .blood: BloodOverviewView()
        case .bodyComposition: BodyCompositionView()
        case .goals: GoalsListView()
        case .settings: SettingsView()
        }
    }
}

/// The authenticated, first-sync-complete main UI. Picks TabView vs. split view
/// on the horizontal size class (D16).
private struct MainShell: View {
    @Environment(\.horizontalSizeClass) private var hSize

    var body: some View {
        if hSize == .regular {
            SplitLayout()
        } else {
            TabLayout()
        }
    }
}

/// Compact (iPhone) layout: four primary tabs + a "More" tab that lists the
/// remaining feature areas (mirrors Android's bottom nav + MoreScreen).
private struct TabLayout: View {
    var body: some View {
        TabView {
            ForEach(AppDestination.phonePrimary) { dest in
                Tab(dest.title, systemImage: dest.systemImage) {
                    NavigationStack { dest.screen }
                }
            }
            Tab("More", systemImage: "ellipsis") {
                NavigationStack { MoreView() }
            }
        }
    }
}

/// Regular-width (iPad / wide) layout: a sidebar with every destination flat,
/// detail rendered in the content column (mirrors Android's tablet rail).
private struct SplitLayout: View {
    @State private var selection: AppDestination? = .today

    var body: some View {
        NavigationSplitView {
            List(AppDestination.allCases, selection: $selection) { dest in
                Label(dest.title, systemImage: dest.systemImage)
                    .tag(dest)
            }
            .navigationTitle("HealthFitness")
        } detail: {
            NavigationStack {
                (selection ?? .today).screen
            }
        }
    }
}

/// Momentary splash shown while the cached session resolves.
private struct SplashView: View {
    var body: some View {
        ProgressView()
            .controlSize(.large)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.canvas)
    }
}
