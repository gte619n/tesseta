import SwiftUI
// import SharedCore  // BloodOverviewViewModel, its UiState, LatestMarker, BloodTestReport — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E1 — Blood / Labs overview (replaces the Phase 2A
/// stub). Parity target (Android): feature-blood `BloodOverviewScreen` +
/// `BloodOverviewViewModel` — a "Tracked markers" grid + "Recent reports" list,
/// with "Add reading" and "Upload lab PDF" actions.
///
/// Observes the SHARED `BloodOverviewViewModel`
/// (shared/.../presentation/blood/BloodOverviewViewModel.kt) through the
/// `ObservableViewModel` bridge — the view is a pure function of the shared UI
/// state, no derivation duplicated on iOS (LatestMarkers.derive runs in shared).
struct BloodOverviewView: View {

    /// Local mirror of the shared `BloodOverviewViewModel.UiState`. Post-0D this is
    /// deleted and the view switches directly on the SKIE-bridged sealed enum.
    enum ScreenState {
        case loading
        case ready(markers: [LatestMarker], reports: [BloodTestReport])
        case error(String)
    }

    @State private var state: ScreenState = .loading

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Blood")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: BloodRoute.addReading) { Image(systemName: "plus") }
                }
            }
            .navigationDestination(for: BloodRoute.self) { route in
                switch route {
                case .addReading: AddReadingView()
                case .uploadReport: UploadLabReportView()
                case .marker(let m): MarkerDetailView(marker: m)
                case .report(let id): ReportDetailView(reportId: id)
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(BloodOverviewViewModel(readings: DI.bloodReadingRepo,
        //                                                         reports: DI.bloodReportRepo))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load blood data", systemImage: "drop",
                                   description: Text(message))
        case .ready(let markers, let reports):
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    actions

                    SettingsCard(title: "Tracked markers",
                                 description: "Your latest lab values and 12-month trend.") {
                        VStack(spacing: 0) {
                            ForEach(markers) { marker in
                                NavigationLink(value: BloodRoute.marker(marker.marker)) {
                                    MarkerRow(marker: marker)
                                }
                                .buttonStyle(.plain)
                                if marker.id != markers.last?.id { Divider().overlay(Theme.borderSubtle) }
                            }
                        }
                    }

                    if !reports.isEmpty {
                        SettingsCard(title: "Recent reports") {
                            VStack(spacing: 0) {
                                ForEach(reports) { report in
                                    NavigationLink(value: BloodRoute.report(report.reportId)) {
                                        ReportRow(report: report)
                                    }
                                    .buttonStyle(.plain)
                                    if report.id != reports.last?.id { Divider().overlay(Theme.borderSubtle) }
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

    private var actions: some View {
        HStack(spacing: 12) {
            NavigationLink(value: BloodRoute.addReading) {
                Label("Add reading", systemImage: "plus.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.accent)

            NavigationLink(value: BloodRoute.uploadReport) {
                Label("Upload lab PDF", systemImage: "doc.badge.arrow.up")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(Theme.accent)
        }
    }

    // static func map(_ s: BloodOverviewViewModel.UiState) -> ScreenState { ... }  // Phase 0D
}

// MARK: - Rows

private struct MarkerRow: View {
    let marker: LatestMarker
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(marker.marker.displayName).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Text(BloodFormat.shortDate(marker.sampleDate))
                    .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            }
            Spacer()
            Text(BloodFormat.markerValue(marker.value, unit: marker.unit))
                .font(.hfMonoSm)
                .foregroundStyle(marker.value == nil ? Theme.textQuaternary : Theme.textPrimary)
            Image(systemName: "chevron.right").font(.hfCapsSm).foregroundStyle(Theme.textQuaternary)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

private struct ReportRow: View {
    let report: BloodTestReport
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(report.labSource).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Text("\(BloodFormat.shortDate(report.sampleDate)) · \(report.markers.count) markers")
                    .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
            }
            Spacer()
            Image(systemName: "chevron.right").font(.hfCapsSm).foregroundStyle(Theme.textQuaternary)
        }
        .padding(.vertical, 8)
        .contentShape(Rectangle())
    }
}

/// Nav routes for the Blood graph — parity with Android's `BloodRoutes`.
enum BloodRoute: Hashable {
    case addReading
    case uploadReport
    case marker(BloodMarker)
    case report(String)
}
