import SwiftUI
import Charts
// import SharedCore  // BodyCompositionViewModel, its UiState, snapshot, DexaScanSummary, WeightUnit — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E2 — body-composition overview (replaces the Phase 2A
/// stub). Parity target (Android): feature-body-composition
/// `BodyCompositionScreen` + `BodyCompositionHero` + `WeightTrendChart` +
/// `DexaScanCard` + `BodyCompositionViewModel` — a weight/body-fat hero with 7d/90d
/// deltas, a 90-day weight trend (Swift Charts), and a DEXA-scan grid.
///
/// Observes the SHARED `BodyCompositionViewModel`; the snapshot math + weight-unit
/// projection run in shared. Weight values are canonical kg; the view projects to
/// the shared `weightUnit`.
struct BodyCompositionView: View {

    /// Local mirror of the shared VM's `UiState` (deleted post-0D).
    struct ScreenState {
        var snapshot: BodyCompositionSnapshot?
        var scans: [DexaScanSummary] = []
        var loading = true
        var error: String?
    }

    @State private var state = ScreenState()
    @State private var weightUnit: WeightUnit = .pounds

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Body")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: BodyCompositionRoute.upload) {
                        Image(systemName: "doc.badge.arrow.up")
                    }
                }
            }
            .navigationDestination(for: BodyCompositionRoute.self) { route in
                switch route {
                case .upload: UploadDexaView()
                case .scan(let id): DexaScanDetailView(scanId: id)
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(BodyCompositionViewModel(bodyRepo: DI.bodyCompRepo,
        //                                                           dexaRepo: DI.dexaRepo,
        //                                                           unitPrefsRepo: DI.unitPrefs))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        //     await vm.observe(vm.wrapped.weightUnit) { self.weightUnit = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading && state.snapshot == nil {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error, state.snapshot == nil {
            ContentUnavailableView("Couldn’t load body composition",
                                   systemImage: "figure", description: Text(error))
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let snapshot = state.snapshot {
                        hero(snapshot)
                        SettingsCard(title: "Weight trend", description: "Last 90 days") {
                            WeightTrendChart(points: snapshot.series90d, unit: weightUnit)
                                .frame(height: 200)
                        }
                    }
                    if !state.scans.isEmpty {
                        SettingsCard(title: "DEXA scans") {
                            VStack(spacing: 0) {
                                ForEach(state.scans) { scan in
                                    NavigationLink(value: BodyCompositionRoute.scan(scan.scanId)) {
                                        DexaScanRow(scan: scan)
                                    }
                                    .buttonStyle(.plain)
                                    if scan.id != state.scans.last?.id {
                                        Divider().overlay(Theme.borderSubtle)
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(16)
                .formMaxWidth()
            }
        }
    }

    // MARK: Hero (parity with BodyCompositionHero)

    private func hero(_ s: BodyCompositionSnapshot) -> some View {
        SettingsCard(title: "Body composition") {
            VStack(alignment: .leading, spacing: 14) {
                Text(BodyCompositionFormat.weight(s.latestWeightKg, unit: weightUnit))
                    .font(.hfDisplayXl).foregroundStyle(Theme.textPrimary)

                HStack(spacing: 20) {
                    deltaChip("7d", s.sevenDayDeltaKg)
                    deltaChip("90d", s.ninetyDayDeltaKg)
                }

                HStack(spacing: 20) {
                    metric("Body fat", BodyCompositionFormat.percent(s.latestBodyFatPercent))
                    metric("Lean mass", BodyCompositionFormat.weight(s.latestLeanMassKg, unit: weightUnit))
                    metric("BMI", s.latestBmi.map { String(format: "%.1f", $0) } ?? "—")
                }
            }
        }
    }

    private func deltaChip(_ label: String, _ kg: Double?) -> some View {
        let text = BodyCompositionFormat.delta(kg, unit: weightUnit)
        let color: Color = (kg ?? 0) < 0 ? Theme.good : ((kg ?? 0) > 0 ? Theme.warn : Theme.textSecondary)
        return HStack(spacing: 4) {
            Text(label).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
            Text(text).font(.hfMonoSm).foregroundStyle(color)
        }
    }

    private func metric(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
            Text(value).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // static func map(_ s: BodyCompositionViewModel.UiState) -> ScreenState { ... }  // Phase 0D
    // static func map(_ u: WeightUnit) -> WeightUnit { ... }
}

// MARK: - Weight trend chart

/// The 90-day weight trend — Swift Charts, parity with Android's
/// `WeightTrendChart`. Values are projected from kg to the user's unit.
struct WeightTrendChart: View {
    let points: [WeightPoint]
    let unit: WeightUnit

    var body: some View {
        if points.isEmpty {
            Text("No weigh-ins yet")
                .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 160)
        } else {
            Chart(points) { point in
                AreaMark(
                    x: .value("Date", point.date),
                    y: .value("Weight", projected(point.valueKg))
                )
                .foregroundStyle(Theme.accentBg.opacity(0.6))
                .interpolationMethod(.monotone)

                LineMark(
                    x: .value("Date", point.date),
                    y: .value("Weight", projected(point.valueKg))
                )
                .foregroundStyle(Theme.accent)
                .interpolationMethod(.monotone)
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) {
                    AxisGridLine().foregroundStyle(Theme.borderSubtle)
                    AxisValueLabel(format: .dateTime.month(.abbreviated)).font(.hfCapsSm)
                }
            }
            .chartYAxis {
                AxisMarks {
                    AxisGridLine().foregroundStyle(Theme.borderSubtle)
                    AxisValueLabel().font(.hfCapsSm)
                }
            }
        }
    }

    private func projected(_ kg: Double) -> Double {
        unit == .pounds ? kg * BodyCompositionFormat.kgToLb : kg
    }
}

// MARK: - DEXA scan row

private struct DexaScanRow: View {
    let scan: DexaScanSummary
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(scan.sourceFacility ?? "DEXA scan")
                    .font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Text(scan.measuredOn.map(BodyCompositionFormat.shortDate) ?? "—")
                    .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(BodyCompositionFormat.percent(scan.totalBodyFatPercent))
                    .font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
                Text(BodyCompositionFormat.pounds(scan.totalMassLb))
                    .font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
            }
            Image(systemName: "chevron.right").font(.hfCapsSm).foregroundStyle(Theme.textQuaternary)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

extension BodyCompositionFormat {
    /// Short medium date, e.g. "Sep 1, 2026".
    static func shortDate(_ date: Date) -> String {
        let fmt = DateFormatter(); fmt.dateFormat = "MMM d, yyyy"
        return fmt.string(from: date)
    }
}

/// Nav routes for the Body-composition graph — parity with Android's
/// `BodyCompositionRoutes`.
enum BodyCompositionRoute: Hashable {
    case upload
    case scan(String)
}
