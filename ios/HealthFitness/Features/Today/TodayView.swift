import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave A1 — the home / Today dashboard, now bound to the
/// SHARED `DashboardViewModel`
/// (shared/.../presentation/dashboard/DashboardViewModel.kt) over the real
/// backend (seven `Http*Dashboard*Repository` impls wired in `IosComposition`).
/// Parity target (Android): `mobile/dashboard/PhoneTodayScreen.kt` (compact) +
/// `FoldableDashboardScreen.kt` (regular width, see `TodaySplitView`).
///
/// Follows the proven SKIE-free pattern (`BloodOverviewView`/`NutritionTodayView`):
/// subscribe to the Kotlin `StateFlow` via `IosComposition.collectFlow` and fold
/// each emission through [map] into the local `DashboardModel` mirror, so the
/// dashlet subviews (which read the app-local mirror structs) are unchanged.
///
/// `DashboardUiState` is NOT a whole-screen loading/ready/error sealed state — it
/// is a single struct whose CARDS each carry their own `CardState<T>` (Android's
/// per-card envelope), so one failed card never blanks the dashboard. [map] folds
/// each `CardState` (`.Loaded` → value; `.Loading`/`.Error` → the card's nil/empty
/// placeholder the dashlets already render), so the screen is always `.ready`.
struct TodayView: View {

    /// Local mirror of the shared `DashboardUiState`. The dashboard has no
    /// whole-screen loading/error (each card owns its state), so after the first
    /// emission this is always `.ready`; `.loading` only covers the pre-emission
    /// gap on a cold open.
    enum ScreenState {
        case loading
        case ready(DashboardModel)
    }

    private let vm: DashboardViewModel
    @State private var state: ScreenState = .loading
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.dashboardViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Today")
            .accessibilityIdentifier("today-dashboard")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.uiState) { value in
                    if let s = value as? DashboardUiState { state = .ready(Self.map(s)) }
                }
            }
            .onDisappear { subscription?.cancel() }
            .refreshable { vm.refresh(force: true) }
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
                    MetricsDashlet(weight: model.bodyComposition, metrics: model.metrics,
                                   hiddenBiometrics: model.hiddenBiometrics)
                    if let workout = model.todayWorkout {
                        WorkoutDashlet(workout: workout)
                    }
                    NutritionDashlet(nutrition: model.nutrition)
                    if !model.blood.isEmpty {
                        BloodDashlet(markers: model.blood)
                    }
                    if !model.recentActivity.isEmpty {
                        RecentActivityDashlet(entries: model.recentActivity)
                    }
                    DoseDashlet(doses: model.doses)
                }
                .padding(.horizontal, 18)
                .padding(.top, 6)
                .padding(.bottom, 16)
                .formMaxWidth()   // 600pt cap — iPad parity with Android's 600dp form width
            }
        }
    }

    // MARK: - Pure mapping — DashboardUiState → DashboardModel
    //
    // Folds each per-card `CardState<T>` (the SKIE-free exports are flat:
    // `CardStateLoaded<AnyObject>` with `.data`, `CardStateError` with `.message`,
    // `CardStateLoading`) into the local mirror, matching Android's
    // `(ui.nutrition as? CardState.Loaded)?.data` extraction. A `.Loading`/`.Error`
    // card maps to the dashlet's nil/empty placeholder (never a fabricated value).
    static func map(_ s: SharedCore.DashboardUiState) -> DashboardModel {
        // Each per-card `data` is read generically: a `.Loaded` card exports as
        // `CardStateLoaded<AnyObject>` with an `Any?` `.data`; a `.Loading`/`.Error`
        // card returns nil here and the dashlet falls back to its placeholder.
        let bodyData = (s.bodyComposition as? CardStateLoaded<AnyObject>)?.data
        let metricsData = (s.dailyMetrics as? CardStateLoaded<AnyObject>)?.data
        let nutritionData = (s.nutrition as? CardStateLoaded<AnyObject>)?.data
        let bloodData = (s.blood as? CardStateLoaded<AnyObject>)?.data
        let recentData = (s.recentActivity as? CardStateLoaded<AnyObject>)?.data

        // Body composition (nil unless Loaded).
        let weight: WeightModel? = (bodyData as? SharedCore.WeightSummary).map { w in
            WeightModel(
                latestLb: w.latestLb,
                sevenDayDeltaLb: w.sevenDayDeltaLb?.doubleValue,
                bodyFatPct: w.latestBodyFatPct?.doubleValue,
                leanMassLb: w.latestLeanMassLb?.doubleValue
            )
        }

        // Daily metrics (empty unless Loaded).
        let metrics: [DailyMetricModel] = (metricsData as? [SharedCore.DailyMetricPoint])?
            .map { DailyMetricModel(steps: $0.steps?.intValue, sleepMinutes: $0.sleepMinutes?.intValue) }
            ?? []

        // Nutrition totals + target (nil unless Loaded).
        let nutrition: NutritionModel? = (nutritionData as? SharedCore.NutritionDay).map { day in
            let totals = day.totals
            let target = day.target
            return NutritionModel(
                caloriesConsumed: totals.caloriesKcal?.doubleValue,
                caloriesTarget: target?.caloriesKcal?.doubleValue,
                proteinConsumed: totals.proteinGrams?.doubleValue,
                proteinTarget: target?.proteinGrams?.doubleValue,
                carbsConsumed: totals.carbsGrams?.doubleValue,
                carbsTarget: target?.carbsGrams?.doubleValue,
                fatConsumed: totals.fatGrams?.doubleValue,
                fatTarget: target?.fatGrams?.doubleValue
            )
        }

        // Blood markers (empty unless Loaded) — the loaded-but-previously-orphaned
        // dashlet the VM exposes; folded into a lightweight display mirror.
        let blood: [BloodMarkerModel] = (bloodData as? [SharedCore.BloodMarkerSummary])?
            .map { m in
                BloodMarkerModel(
                    key: m.markerKey,
                    name: m.displayName,
                    value: m.value,
                    unit: m.unit,
                    isGood: m.tone == MarkerTone.good
                )
            }
            ?? []

        // Recent activity (empty unless Loaded).
        let recent: [RecentActivityModel] = (recentData as? [SharedCore.RecentActivityEntry])?
            .map { e in
                RecentActivityModel(id: "\(e.kind)-\(e.timestamp.toEpochMilliseconds())",
                                    title: e.title, subtitle: e.subtitle)
            }
            ?? []

        // Today's workout pick — the shared `TodayWorkout` sealed type.
        let workout = mapWorkout(s.todayWorkout)

        let user = s.user.map { DashboardUserModel(initials: $0.initials, photoUrl: $0.photoUrl) }
        let hidden = Set((s.hiddenBiometrics as? Set<String>) ?? [])

        return DashboardModel(
            user: user,
            lastUpdatedLabel: lastUpdatedLabel(s.lastUpdated),
            hiddenBiometrics: hidden,
            bodyComposition: weight,
            metrics: metrics,
            nutrition: nutrition,
            todayWorkout: workout,
            doses: [],          // The shared DashboardViewModel exposes no doses source.
            blood: blood,
            recentActivity: recent
        )
    }

    /// `TodayWorkout` sealed type → the local `WorkoutModel` (nil == Hidden).
    private static func mapWorkout(_ w: any SharedCore.TodayWorkout) -> WorkoutModel? {
        switch w {
        case let r as SharedCore.TodayWorkoutResume:
            return .resume(label: r.label, setsLogged: Int(r.setsLogged))
        case let s as SharedCore.TodayWorkoutStart:
            return .start(label: s.label, isToday: s.isToday)
        case let c as SharedCore.TodayWorkoutCompleted:
            return .completed(label: c.label, durationSeconds: c.durationSeconds?.intValue,
                              totalSets: Int(c.totalSets), totalWeightLbs: c.totalWeightLbs,
                              estimatedCalories: c.estimatedCalories?.intValue)
        default:
            return nil   // TodayWorkoutHidden
        }
    }

    /// "Updated HH:mm" from the shared last-refresh Instant (nil → generic).
    private static func lastUpdatedLabel(_ instant: SharedCore.Kotlinx_datetimeInstant?) -> String {
        guard let instant else { return "Your day at a glance" }
        let date = Date(timeIntervalSince1970: Double(instant.toEpochMilliseconds()) / 1000.0)
        let f = DateFormatter()
        f.timeStyle = .short
        f.dateStyle = .none
        return "Updated \(f.string(from: date))"
    }
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
