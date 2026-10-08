import SwiftUI
import Charts
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E2 — body-composition overview, now bound to the SHARED
/// `BodyCompositionViewModel` over the real backend
/// (`HttpBodyCompositionRepository` → GET api/me/body-composition, snapshot DERIVED
/// in shared; `HttpDexaScanRepository` → api/me/dexa/scans). Parity target
/// (Android): feature-body-composition `BodyCompositionScreen` + hero + trend chart
/// + DEXA grid.
///
/// Follows the SKIE-free pattern: subscribe to the Kotlin `StateFlow`s via
/// `IosComposition.collectFlow` and fold each emission through [map] into the local
/// `ScreenState`/`WeightUnit` mirrors, so the existing hero/chart/row subviews are
/// unchanged. The snapshot math + 90-day series are computed in shared; weight is
/// canonical kg and the view projects to the shared `weightUnit`. Body composition
/// is pull-only (D9) — read + refresh only; the DEXA PDF upload is a platform-
/// stubbed affordance (document picker + multipart-SSE, no shared SSE client yet).
struct BodyCompositionView: View {

    /// Local mirror of the shared VM's `UiState`.
    struct ScreenState {
        var snapshot: BodyCompositionSnapshot?
        var scans: [DexaScanSummary] = []
        var loading = true
        var error: String?
    }

    private let vm: BodyCompositionViewModel
    @State private var state = ScreenState()
    @State private var weightUnit: WeightUnit = .pounds
    @State private var stateSub: FlowSubscription?
    @State private var unitSub: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.bodyCompositionViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Body")
            .accessibilityIdentifier("body-composition")  // IMPL-E2E-01 shared id
            .onAppear {
                stateSub = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? BodyCompositionViewModel.UiState { state = Self.map(s) }
                }
                unitSub = IosComposition.shared.collectFlow(flow: vm.weightUnit) { value in
                    if let u = value as? SharedCore.WeightUnit { weightUnit = Self.map(u) }
                }
            }
            .onDisappear { stateSub?.cancel(); unitSub?.cancel() }
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
            .refreshable { vm.refresh() }
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

    // MARK: - Mapping (SKIE-free: Kotlin state → local mirrors)

    private static func map(_ s: BodyCompositionViewModel.UiState) -> ScreenState {
        ScreenState(
            snapshot: s.snapshot.map(mapSnapshot),
            scans: s.dexaScans.map(mapScanSummary),
            loading: s.loading,
            error: s.error,
        )
    }

    private static func map(_ u: SharedCore.WeightUnit) -> WeightUnit {
        u == SharedCore.WeightUnit.kilograms ? .kilograms : .pounds
    }

    private static func mapSnapshot(_ s: SharedCore.BodyCompositionSnapshot) -> BodyCompositionSnapshot {
        BodyCompositionSnapshot(
            latestWeightKg: s.latestWeightKg?.doubleValue,
            latestBodyFatPercent: s.latestBodyFatPercent?.doubleValue,
            latestLeanMassKg: s.latestLeanMassKg?.doubleValue,
            latestBmi: s.latestBmi?.doubleValue,
            sevenDayDeltaKg: s.sevenDayDeltaKg?.doubleValue,
            ninetyDayDeltaKg: s.ninetyDayDeltaKg?.doubleValue,
            series90d: s.series90d.map(mapWeightPoint),
        )
    }

    private static func mapWeightPoint(_ p: SharedCore.BodyCompositionPoint) -> WeightPoint {
        WeightPoint(date: instantToDate(p.sampleTime), valueKg: p.value)
    }

    private static func mapScanSummary(_ d: SharedCore.DexaScanSummary) -> DexaScanSummary {
        DexaScanSummary(
            scanId: d.scanId,
            measuredOn: d.measuredOn.map(localDateToDate),
            sourceFacility: d.sourceFacility,
            totalMassLb: d.totalMassLb?.doubleValue,
            totalBodyFatPercent: d.totalBodyFatPercent?.doubleValue,
        )
    }

    /// Kotlin `Instant` → Swift `Date`.
    private static func instantToDate(_ i: Kotlinx_datetimeInstant) -> Date {
        Date(timeIntervalSince1970: Double(i.toEpochMilliseconds()) / 1000.0)
    }

    /// Kotlin `LocalDate` → Swift `Date` (midnight UTC of that calendar day).
    private static func localDateToDate(_ d: Kotlinx_datetimeLocalDate) -> Date {
        Date(timeIntervalSince1970: Double(d.toEpochDays()) * 86_400)
    }
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
