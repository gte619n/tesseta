import SwiftUI
// import SharedCore  // WeightSummary, DailyMetricPoint, NutritionDay, TodayWorkout, DueDose — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A1 — the Today dashboard's local view-model mirror
/// and dashlet card subviews. These plain-Swift structs mirror the shared
/// `DashboardUiState` cards (and the dashboard-scoped domain models in
/// shared/.../data/DashboardRepositories.kt) so the SwiftUI views compile and
/// render before the XCFramework is built (Phase 0D). Post-0D the mapping fills
/// them from the SKIE-bridged types; the card VIEWS are unchanged.
///
/// Parity target: Android `mobile/dashboard/{TodayCard, TodayWorkoutCard,
/// StatCard/MetricVitals}` + `feature-medical/.../today/TodaysDosesCard`.

// MARK: - Local mirror models (deleted post-0D; the shared types replace them)

struct DashboardModel {
    var user: DashboardUserModel?
    var lastUpdatedLabel: String
    var hiddenBiometrics: Set<String>
    var bodyComposition: WeightModel?      // nil while its card is Loading/Error
    var metrics: [DailyMetricModel]
    var nutrition: NutritionModel?         // nil → em-dash placeholders (Android TodayCard)
    var todayWorkout: WorkoutModel?        // nil == TodayWorkout.Hidden (render nothing)
    var doses: [DoseRowModel]
    var blood: [BloodMarkerModel]          // empty → dashlet hidden
    var recentActivity: [RecentActivityModel]  // empty → dashlet hidden
}

/// Mirror of the loaded `BloodMarkerSummary` (dashboard-scoped) — just the fields
/// the compact Today blood dashlet renders.
struct BloodMarkerModel: Identifiable {
    var id: String { key }
    let key: String
    let name: String
    let value: Double
    let unit: String
    let isGood: Bool
}

/// Mirror of a `RecentActivityEntry` row (title + optional subtitle).
struct RecentActivityModel: Identifiable {
    let id: String
    let title: String
    let subtitle: String?
}

struct DashboardUserModel {
    let initials: String
    let photoUrl: String?
}

/// Mirror of `WeightSummary` (dashboard-scoped). Weight FORMATTING is a view
/// concern here (Android formats via `UnitFormat`/`WeightUnit` at the UI edge),
/// so the shared VM exposes `latestLb` and the view renders it.
struct WeightModel {
    let latestLb: Double
    let sevenDayDeltaLb: Double?
    let bodyFatPct: Double?
    let leanMassLb: Double?
}

/// Mirror of `DailyMetricPoint` — only the fields the vitals grid reads.
struct DailyMetricModel {
    let steps: Int?
    let sleepMinutes: Int?
}

/// Mirror of `NutritionDay.totals` + `.target` (the Today card's inputs).
struct NutritionModel {
    let caloriesConsumed: Double?
    let caloriesTarget: Double?
    let proteinConsumed: Double?
    let proteinTarget: Double?
    let carbsConsumed: Double?
    let carbsTarget: Double?
    let fatConsumed: Double?
    let fatTarget: Double?
}

/// Mirror of the shared `TodayWorkout` sealed interface.
enum WorkoutModel {
    case resume(label: String?, setsLogged: Int)
    case start(label: String?, isToday: Bool)
    case completed(label: String?, durationSeconds: Int?, totalSets: Int,
                   totalWeightLbs: Double, estimatedCalories: Int?)
}

struct DoseRowModel: Identifiable {
    let id: String        // DueDose.key
    let name: String
    let doseSummary: String
}

// MARK: - Pure formatting helpers (unit-tested in TodayDashletFormattingTests)

enum DashboardFormat {
    static let dash = "—"

    /// Group-separated calories, e.g. 1247.0 → "1,247" (Android `formatCalories`).
    static func calories(_ kcal: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        return f.string(from: NSNumber(value: kcal.rounded())) ?? "\(Int(kcal.rounded()))"
    }

    /// A macro's grams, rounded (nil/absent → "0") — Android `macroGrams`.
    static func macroGrams(_ value: Double?) -> String {
        String(Int((value ?? 0).rounded()))
    }

    /// Consumed-vs-target fraction in [0,1]; no positive target → 0 so a ring
    /// reads empty rather than guessing (Android `macroFraction`).
    static func macroFraction(consumed: Double?, target: Double?) -> Double {
        guard let target, target > 0 else { return 0 }
        return min(max((consumed ?? 0) / target, 0), 1)
    }
}

// MARK: - MetricsDashlet (Android PhoneVitalsGrid / StatCard)

/// The vitals grid: Weight (live), Sleep, Steps — minus any hidden in settings.
/// Two-up flow so hiding one tile doesn't leave a gap (Android `chunked(2)`).
struct MetricsDashlet: View {
    let weight: WeightModel?
    let metrics: [DailyMetricModel]
    let hiddenBiometrics: Set<String>

    private var tiles: [VitalTile] {
        var out: [VitalTile] = []
        if !hiddenBiometrics.contains("WEIGHT") {
            out.append(VitalTile(
                key: "WEIGHT", label: "Body",
                value: weight.map { String(format: "%.1f", $0.latestLb) } ?? DashboardFormat.dash,
                unit: "lb",
                delta: weight?.sevenDayDeltaLb.map { d in
                    (String(format: "%@ %.1f 7d", d <= 0 ? "↓" : "↑", abs(d)), d <= 0)
                }
            ))
        }
        if !hiddenBiometrics.contains("SLEEP") {
            let mins = metrics.last?.sleepMinutes
            out.append(VitalTile(key: "SLEEP", label: "Sleep",
                                 value: mins.map { "\($0 / 60)h \($0 % 60)m" } ?? DashboardFormat.dash,
                                 unit: nil, delta: nil))
        }
        if !hiddenBiometrics.contains("STEPS") {
            let steps = metrics.last?.steps
            out.append(VitalTile(key: "STEPS", label: "Steps",
                                 value: steps.map(String.init) ?? DashboardFormat.dash,
                                 unit: nil, delta: nil))
        }
        return out
    }

    var body: some View {
        let rows = stride(from: 0, to: tiles.count, by: 2).map { Array(tiles[$0..<min($0 + 2, tiles.count)]) }
        VStack(spacing: 9) {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, rowTiles in
                HStack(spacing: 9) {
                    ForEach(rowTiles) { tile in StatCardView(tile: tile).frame(maxWidth: .infinity) }
                    if rowTiles.count == 1 { Spacer().frame(maxWidth: .infinity) }
                }
            }
        }
    }
}

struct VitalTile: Identifiable {
    var id: String { key }
    let key: String
    let label: String
    let value: String
    let unit: String?
    /// (label, isGood) — green when good (weight going down), else warn.
    let delta: (String, Bool)?
}

/// One vital tile (Android `StatCard`).
struct StatCardView: View {
    let tile: VitalTile

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(tile.label.uppercased())
                .font(.hfCapsSm)
                .foregroundStyle(Theme.textTertiary)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(tile.value)
                    .font(.hfDisplayMd)
                    .foregroundStyle(Theme.textPrimary)
                if let unit = tile.unit {
                    Text(unit).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                }
            }
            if let (label, isGood) = tile.delta {
                Text(label)
                    .font(.hfMonoSm)
                    .foregroundStyle(isGood ? Theme.good : Theme.warn)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.borderDefault, lineWidth: 0.5))
    }
}

// MARK: - WorkoutDashlet (Android TodayWorkoutCard)

/// The home "Today's workout" card: resume / start / completed-recap. Renders
/// nothing on a rest day (the parent gates on `todayWorkout != nil`).
struct WorkoutDashlet: View {
    let workout: WorkoutModel

    var body: some View {
        SettingsCard(title: "Today's workout") {
            switch workout {
            case .resume(let label, let sets):
                dashletRow(primary: label ?? "Resume workout",
                           secondary: "\(sets) set\(sets == 1 ? "" : "s") logged • tap to resume",
                           action: "Resume")
            case .start(let label, let isToday):
                dashletRow(primary: label ?? (isToday ? "Start today's workout" : "Start next workout"),
                           secondary: isToday ? "Ready when you are" : "Next up",
                           action: "Start")
            case .completed(let label, let duration, let sets, let volume, let calories):
                VStack(alignment: .leading, spacing: 8) {
                    Text(label ?? "Workout complete")
                        .font(.hfBodyMd)
                        .foregroundStyle(Theme.textPrimary)
                    HStack(spacing: 16) {
                        recapStat("TIME", Self.durationLabel(duration))
                        recapStat("SETS", "\(sets)")
                        recapStat("VOLUME", "\(Int(volume.rounded())) lb")
                        recapStat("EST. KCAL", calories.map(String.init) ?? DashboardFormat.dash)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func dashletRow(primary: String, secondary: String, action: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(primary).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Text(secondary).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            }
            Spacer(minLength: 12)
            Text(action.uppercased())
                .font(.hfCapsSm)
                .foregroundStyle(Theme.textInverse)
                .padding(.horizontal, 14).padding(.vertical, 7)
                .background(Theme.accent, in: RoundedRectangle(cornerRadius: 7))
        }
    }

    private func recapStat(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
            Text(value).font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
        }
    }

    /// "45m" / "1h 05m" from a seconds count (nil → em-dash).
    static func durationLabel(_ seconds: Int?) -> String {
        guard let seconds, seconds > 0 else { return DashboardFormat.dash }
        let m = seconds / 60
        return m < 60 ? "\(m)m" : String(format: "%dh %02dm", m / 60, m % 60)
    }
}

// MARK: - NutritionDashlet (Android TodayCard)

/// Today's macros: calories consumed / target + a donut, and protein/carbs/fat
/// progress. `nil` nutrition → em-dash placeholders (never fabricated numbers).
struct NutritionDashlet: View {
    let nutrition: NutritionModel?

    var body: some View {
        SettingsCard(title: "Today") {
            let loaded = nutrition != nil
            let caloriesCurrent = loaded ? DashboardFormat.calories(nutrition?.caloriesConsumed ?? 0) : DashboardFormat.dash
            let caloriesTarget = loaded
                ? (nutrition?.caloriesTarget.map { DashboardFormat.calories($0) } ?? DashboardFormat.dash)
                : DashboardFormat.dash
            let caloriesPct = loaded
                ? DashboardFormat.macroFraction(consumed: nutrition?.caloriesConsumed, target: nutrition?.caloriesTarget)
                : 0

            HStack {
                VStack(alignment: .leading, spacing: 3) {
                    Text("CALORIES").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                    HStack(alignment: .firstTextBaseline, spacing: 4) {
                        Text(caloriesCurrent).font(.hfDisplayMd).foregroundStyle(Theme.textPrimary)
                        Text("/ \(caloriesTarget)").font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    }
                }
                Spacer()
                CaloriesDonut(pct: caloriesPct)
            }

            HStack(spacing: 8) {
                macroCell("Protein", nutrition?.proteinConsumed, nutrition?.proteinTarget, loaded, Theme.accent)
                macroCell("Carbs", nutrition?.carbsConsumed, nutrition?.carbsTarget, loaded, Theme.good)
                macroCell("Fat", nutrition?.fatConsumed, nutrition?.fatTarget, loaded, Theme.muted)
            }
        }
    }

    private func macroCell(_ label: String, _ consumed: Double?, _ target: Double?,
                           _ loaded: Bool, _ color: Color) -> some View {
        let value = loaded ? DashboardFormat.macroGrams(consumed) : DashboardFormat.dash
        let pct = loaded ? DashboardFormat.macroFraction(consumed: consumed, target: target) : 0
        return VStack(alignment: .leading, spacing: 5) {
            Text(label.uppercased()).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
            HStack(alignment: .firstTextBaseline, spacing: 2) {
                Text(value).font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
                Text("g").font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            }
            ProgressView(value: pct)
                .tint(color)
                .scaleEffect(x: 1, y: 0.6, anchor: .center)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// A thin ring with the percent in the middle (Android `CaloriesDonut`).
struct CaloriesDonut: View {
    let pct: Double

    var body: some View {
        ZStack {
            Circle().stroke(Theme.canvasMuted, lineWidth: 4)
            Circle()
                .trim(from: 0, to: pct)
                .stroke(Theme.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
            Text("\(Int(pct * 100))%")
                .font(.hfMonoSm)
                .foregroundStyle(Theme.textPrimary)
        }
        .frame(width: 42, height: 42)
    }
}

// MARK: - BloodDashlet (Android BloodPanel, compact)

/// The latest tracked blood markers (testosterone / LDL / ApoB / HbA1c) with a
/// good/warn tint. Rendered only when the VM's blood card is Loaded & non-empty.
struct BloodDashlet: View {
    let markers: [BloodMarkerModel]

    var body: some View {
        SettingsCard(title: "Blood markers") {
            ForEach(markers) { marker in
                HStack {
                    Text(marker.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                    Spacer(minLength: 12)
                    HStack(alignment: .firstTextBaseline, spacing: 3) {
                        Text(valueLabel(marker.value)).font(.hfMonoSm)
                            .foregroundStyle(marker.isGood ? Theme.good : Theme.warn)
                        Text(marker.unit).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    }
                }
                .padding(.vertical, 5)
            }
        }
    }

    private func valueLabel(_ v: Double) -> String {
        v == v.rounded() ? String(Int(v)) : String(format: "%.1f", v)
    }
}

// MARK: - RecentActivityDashlet (Android RecentFeed)

/// The recent cross-source activity feed (workouts / weigh-ins / sleep / food /
/// meds). Rendered only when the VM's recent-activity card is Loaded & non-empty.
struct RecentActivityDashlet: View {
    let entries: [RecentActivityModel]

    var body: some View {
        SettingsCard(title: "Recent") {
            ForEach(entries) { entry in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(entry.title).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                        if let subtitle = entry.subtitle, !subtitle.isEmpty {
                            Text(subtitle).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                        }
                    }
                    Spacer(minLength: 12)
                }
                .padding(.vertical, 5)
            }
        }
    }
}

// MARK: - DoseDashlet (Android TodaysDosesCard)

/// Today's outstanding doses (overdue + currently-due). Empty → a done state.
struct DoseDashlet: View {
    let doses: [DoseRowModel]

    var body: some View {
        SettingsCard(title: "Today's doses",
                     description: doses.isEmpty ? "You're all caught up." : nil) {
            ForEach(doses) { dose in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(dose.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                        Text(dose.doseSummary).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    }
                    Spacer(minLength: 12)
                    Image(systemName: "circle").foregroundStyle(Theme.textTertiary)
                }
                .padding(.vertical, 6)
            }
        }
    }
}
