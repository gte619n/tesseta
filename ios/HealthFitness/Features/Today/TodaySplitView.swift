import SwiftUI
// import SharedCore  // DashboardViewModel, DashboardUiState — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A1 — the regular-width (iPad) two-pane dashboard.
///
/// Parity target (Android): `mobile/dashboard/FoldableDashboardScreen.kt` — a
/// narrow icon rail on the leading edge plus the dashboard content column, with
/// the vitals laid out in a single row (rather than the compact two-up flow) and
/// the blood/nutrition cards side by side.
///
/// The dashlet card subviews (`MetricsDashlet`, `WorkoutDashlet`,
/// `NutritionDashlet`, `DoseDashlet`) are SHARED with the compact `TodayView`;
/// only the outer layout differs, mirroring how Android reuses the same cards
/// across `PhoneTodayScreen` and `FoldableDashboardScreen`.
///
/// `TodayView` chooses between this and the compact layout on horizontal size
/// class post-0D; kept as its own view so the split layout can be previewed and
/// iterated independently (Wave F iPad polish).
struct TodaySplitView: View {

    @State private var state: TodayView.ScreenState = .loading

    var body: some View {
        HStack(spacing: 0) {
            FoldableRail()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Theme.canvas)
        .navigationTitle("Dashboard")
        // Post-0D: same shared VM subscription as TodayView (see TodayView.body).
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .ready(let model):
            ScrollView {
                VStack(alignment: .leading, spacing: 11) {
                    DashboardHeader(user: model.user, lastUpdatedLabel: model.lastUpdatedLabel)
                    // Regular width: vitals in one row (three-up), no two-up flow.
                    MetricsDashlet(weight: model.bodyComposition, metrics: model.metrics,
                                   hiddenBiometrics: model.hiddenBiometrics)
                    if let workout = model.todayWorkout {
                        WorkoutDashlet(workout: workout)
                    }
                    // Nutrition + doses side by side on the wide layout.
                    HStack(alignment: .top, spacing: 8) {
                        NutritionDashlet(nutrition: model.nutrition)
                            .frame(maxWidth: .infinity)
                        DoseDashlet(doses: model.doses)
                            .frame(maxWidth: .infinity)
                    }
                }
                .padding(.horizontal, 22)
                .padding(.vertical, 18)
            }
        }
    }
}

/// The leading icon rail (Android `FoldableSidebar`): a vertical stack of
/// section icons over a settings/avatar footer. Purely navigational scaffolding
/// for the split layout; destinations wire up in Wave F.
private struct FoldableRail: View {
    private struct RailItem: Identifiable {
        let id = UUID()
        let symbol: String
        let label: String
        var active: Bool = false
    }

    private let items: [RailItem] = [
        RailItem(symbol: "square.grid.2x2", label: "Dashboard", active: true),
        RailItem(symbol: "scalemass", label: "Body"),
        RailItem(symbol: "drop", label: "Blood"),
        RailItem(symbol: "figure.strengthtraining.traditional", label: "Workouts"),
        RailItem(symbol: "pills", label: "Meds"),
        RailItem(symbol: "fork.knife", label: "Nutrition"),
    ]

    var body: some View {
        VStack(spacing: 3) {
            ForEach(items) { item in
                Image(systemName: item.symbol)
                    .font(.system(size: 18))
                    .foregroundStyle(item.active ? Theme.accentDim : Theme.textTertiary)
                    .frame(width: 42, height: 38)
                    .background(item.active ? Theme.accentBg : .clear,
                                in: RoundedRectangle(cornerRadius: 8))
                    .accessibilityLabel(item.label)
            }
            Spacer()
            Rectangle().fill(Theme.borderStrong).frame(height: 0.5)
            Image(systemName: "gearshape")
                .font(.system(size: 18))
                .foregroundStyle(Theme.textTertiary)
                .frame(width: 42, height: 38)
                .accessibilityLabel("Settings")
            AvatarSquare(initials: "EG")
        }
        .padding(.vertical, 14)
        .frame(width: 64)
        .frame(maxHeight: .infinity)
        .background(Theme.canvasMuted)
        .overlay(Rectangle().fill(Theme.borderStrong).frame(width: 0.5), alignment: .trailing)
    }
}
