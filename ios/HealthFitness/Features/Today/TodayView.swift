import SwiftUI
// import SharedCore  // DashboardViewModel, DashboardUiState, CardState, TodayWorkout — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A1 — the home / Today dashboard.
///
/// Parity target (Android): `mobile/dashboard/PhoneTodayScreen.kt` (compact) +
/// `FoldableDashboardScreen.kt` (regular width, see `TodaySplitView`). Observes
/// the SHARED `DashboardViewModel`
/// (shared/.../presentation/dashboard/DashboardViewModel.kt) through the
/// `ObservableViewModel` bridge — the view is a pure function of the shared
/// per-card `DashboardUiState`, no dashboard business logic duplicated on iOS.
///
/// Follows the worked reference (`MedicationsListView`):
///   1. a local `@State` mirror of the shared UiState (`ScreenState` + the
///      dashlet models), deleted post-0D when we switch on the SKIE-bridged types
///   2. a `.task { await vm.observe(...) }` subscription (commented until the
///      XCFramework is built)
///   3. a `switch` on Loading / Ready / Error at the whole-screen grain, with
///      each card carrying its OWN per-card state (Android's `CardState`), so a
///      single failed card never blanks the dashboard.
struct TodayView: View {

    /// Local mirror of the shared `DashboardUiState`. Post-0D this is deleted and
    /// the view reads the SKIE-bridged `DashboardUiState` directly.
    enum ScreenState {
        case loading
        case ready(DashboardModel)
        case error(String)
    }

    @State private var state: ScreenState = .loading

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Today")
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(DashboardViewModel(
        //         bodyComp: DI.dashboardBodyComp, dailyMetrics: DI.dashboardMetrics,
        //         blood: DI.dashboardBlood, nutrition: DI.dashboardNutrition,
        //         recent: DI.dashboardRecent, workouts: DI.dashboardWorkouts,
        //         profile: DI.dashboardProfile))
        //     await vm.observe(vm.wrapped.uiState) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load your dashboard", systemImage: "square.grid.2x2",
                                   description: Text(message))
        case .ready(let model):
            ScrollView {
                VStack(alignment: .leading, spacing: 11) {
                    DashboardHeader(user: model.user, lastUpdatedLabel: model.lastUpdatedLabel)
                    MetricsDashlet(weight: model.bodyComposition, metrics: model.metrics,
                                   hiddenBiometrics: model.hiddenBiometrics)
                    if let workout = model.todayWorkout {
                        WorkoutDashlet(workout: workout)
                    }
                    NutritionDashlet(nutrition: model.nutrition)
                    DoseDashlet(doses: model.doses)
                }
                .padding(.horizontal, 18)
                .padding(.top, 6)
                .padding(.bottom, 16)
                .formMaxWidth()   // 600pt cap — iPad parity with Android's 600dp form width
            }
        }
    }

    // MARK: - Pure mapping (Phase 0D: `map(_ s: DashboardUiState) -> ScreenState`)
    //
    // Once the XCFramework lands, this folds the SKIE-bridged `DashboardUiState`
    // (whose cards are `CardState<T>` enums) into the local `DashboardModel`,
    // matching Android's `(ui.nutrition as? CardState.Loaded)?.data` extraction.
}

/// Header row: greeting + "Updated …" subtitle on the left, avatar on the right
/// (Android `PhoneHeader`). The greeting mirrors Android's fixture string.
struct DashboardHeader: View {
    let user: DashboardUserModel?
    let lastUpdatedLabel: String

    var body: some View {
        HStack(alignment: .center) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Good morning, Evan")
                    .font(.hfHeadingLg)
                    .foregroundStyle(Theme.textPrimary)
                Text(lastUpdatedLabel)
                    .font(.hfMonoSm)
                    .foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            AvatarSquare(initials: user?.initials ?? "EG")
        }
    }
}

/// Square initials avatar (Android `AvatarSquare`); the photo variant lands with
/// remote-image loading in a later polish pass.
struct AvatarSquare: View {
    let initials: String

    var body: some View {
        Text(initials)
            .font(.hfCapsSm)
            .foregroundStyle(Theme.textInverse)
            .frame(width: 38, height: 38)
            .background(Theme.accent, in: RoundedRectangle(cornerRadius: 10))
    }
}
