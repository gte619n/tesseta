import SwiftUI
import Charts
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E1 — single-marker detail. Parity target (Android):
/// feature-blood `MarkerDetailScreen` + `components/MarkerHistoryChart` — a
/// 12-month trend line (Swift Charts here, mirroring the Android marker chart) +
/// a target/description card + a readings table.
///
/// Observes the SHARED `MarkerDetailViewModel(readings, reports, marker)`; the
/// `LatestMarkers.derive` fold that produces the trend runs in shared, so both
/// clients chart identical points.
struct MarkerDetailView: View {

    let marker: BloodMarker

    enum ScreenState {
        case loading
        case ready(marker: LatestMarker, rows: [MarkerHistoryRow])
        case error(String)
    }

    private let vm: MarkerDetailViewModel
    @State private var state: ScreenState = .loading
    @State private var subscription: FlowSubscription?

    init(marker: BloodMarker) {
        self.marker = marker
        // rawValue == the shared BloodMarker enum name (valueOf on the Kotlin side).
        self.vm = IosComposition.shared.markerDetailViewModel(markerName: marker.rawValue)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(marker.displayName)
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? MarkerDetailViewModelUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load marker", systemImage: "drop",
                                   description: Text(message))
        case .ready(let latest, let rows):
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    latestValueHeader(latest)

                    SettingsCard(title: "Trend", description: "Last 12 months") {
                        MarkerTrendChart(points: latest.history)
                            .frame(height: 200)
                    }

                    SettingsCard(title: "About this marker", description: marker.info) {
                        HStack {
                            Text("Target").font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                            Spacer()
                            Text(marker.target).font(.hfBodySm).foregroundStyle(Theme.textPrimary)
                        }
                    }

                    if !rows.isEmpty {
                        SettingsCard(title: "Readings") {
                            VStack(spacing: 0) {
                                ForEach(rows) { row in
                                    HStack {
                                        Text(BloodFormat.shortDate(row.date))
                                            .font(.hfBodySm).foregroundStyle(Theme.textPrimary)
                                        Spacer()
                                        Text(BloodFormat.markerValue(row.value, unit: row.unit))
                                            .font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
                                        Text(row.sourceLabel)
                                            .font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                                            .frame(width: 120, alignment: .trailing)
                                    }
                                    .padding(.vertical, 8)
                                    if row.id != rows.last?.id { Divider().overlay(Theme.borderSubtle) }
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

    private func latestValueHeader(_ latest: LatestMarker) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(BloodFormat.markerValue(latest.value, unit: latest.unit))
                .font(.hfDisplayLg)
                .foregroundStyle(Theme.textPrimary)
            Text("Latest · \(BloodFormat.shortDate(latest.sampleDate))")
                .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: shared UiState → local ScreenState

    static func map(_ s: MarkerDetailViewModelUiState) -> ScreenState {
        switch s {
        case let ready as MarkerDetailViewModelUiStateReady:
            return .ready(marker: mapMarker(ready.latest), rows: ready.rows.map(mapHistoryRow))
        case let error as MarkerDetailViewModelUiStateError:
            return .error(error.message)
        default:  // Loading
            return .loading
        }
    }

    private static func mapMarker(_ m: SharedCore.LatestMarker) -> LatestMarker {
        LatestMarker(
            marker: BloodMarker(rawValue: m.marker.name) ?? .ldl,
            value: m.value?.doubleValue,
            unit: m.unit,
            sampleDate: m.sampleDate.map(localDateToDate),
            source: mapSource(m.source),
            history: m.history.map(mapHistoryPoint),
        )
    }

    private static func mapHistoryPoint(_ p: SharedCore.MarkerHistoryPoint) -> MarkerHistoryPoint {
        MarkerHistoryPoint(
            date: localDateToDate(p.date),
            value: p.value,
            isLab: !(p.source is MarkerHistoryPointSourceManual),
        )
    }

    private static func mapHistoryRow(_ r: MarkerDetailViewModel.HistoryRow) -> MarkerHistoryRow {
        MarkerHistoryRow(
            date: localDateToDate(r.date),
            value: r.value,
            unit: r.unit,
            sourceLabel: r.sourceLabel,
        )
    }

    private static func mapSource(_ s: SharedCore.LatestMarker.Source) -> LatestMarker.Source {
        if s == SharedCore.LatestMarker.Source.manual { return .manual }
        if s == SharedCore.LatestMarker.Source.lab { return .lab }
        return .none
    }

    private static func localDateToDate(_ d: Kotlinx_datetimeLocalDate) -> Date {
        Date(timeIntervalSince1970: Double(d.toEpochDays()) * 86_400)
    }
}

// MARK: - Trend chart

/// The 12-month marker trend — Swift Charts, parity with Android's
/// `MarkerHistoryChart`. Lab points are emphasized with a filled symbol; manual
/// points use a hollow one. Renders an empty-state when there is nothing to plot.
struct MarkerTrendChart: View {
    let points: [MarkerHistoryPoint]

    var body: some View {
        if points.isEmpty {
            Text("No readings yet")
                .font(.hfBodySm)
                .foregroundStyle(Theme.textTertiary)
                .frame(maxWidth: .infinity, minHeight: 160)
        } else {
            Chart(points) { point in
                LineMark(
                    x: .value("Date", point.date),
                    y: .value("Value", point.value)
                )
                .foregroundStyle(Theme.accent)
                .interpolationMethod(.monotone)

                PointMark(
                    x: .value("Date", point.date),
                    y: .value("Value", point.value)
                )
                .foregroundStyle(point.isLab ? Theme.accent : Theme.surface)
                .symbol {
                    Circle()
                        .strokeBorder(Theme.accent, lineWidth: 1.5)
                        .background(Circle().fill(point.isLab ? Theme.accent : Theme.surface))
                        .frame(width: 7, height: 7)
                }
            }
            .chartXAxis {
                AxisMarks(values: .automatic(desiredCount: 4)) {
                    AxisGridLine().foregroundStyle(Theme.borderSubtle)
                    AxisValueLabel(format: .dateTime.month(.abbreviated))
                        .font(.hfCapsSm)
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
}
