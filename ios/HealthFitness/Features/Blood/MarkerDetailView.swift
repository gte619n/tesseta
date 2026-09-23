import SwiftUI
import Charts
// import SharedCore  // MarkerDetailViewModel, its UiState, LatestMarker, HistoryRow — Phase 0D

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

    @State private var state: ScreenState = .loading

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(marker.displayName)
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(MarkerDetailViewModel(readings: DI.bloodReadingRepo,
        //                                                        reports: DI.bloodReportRepo,
        //                                                        marker: marker.shared))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
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

    // static func map(_ s: MarkerDetailViewModel.UiState) -> ScreenState { ... }  // Phase 0D
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
