import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E1 — Blood / Labs overview, now bound to the SHARED
/// `BloodOverviewViewModel` over the real backend
/// (`HttpBloodReadingRepository` → GET/POST/DELETE api/me/blood;
/// `HttpBloodTestReportRepository` → …/reports). Parity target (Android):
/// feature-blood `BloodOverviewScreen` + `BloodOverviewViewModel`.
///
/// Follows the proven SKIE-free pattern (see `NutritionTodayView`/
/// `MedicationsListView`): subscribe to the Kotlin `StateFlow` via
/// `IosComposition.collectFlow` and fold each emission through [map] into the local
/// `ScreenState` mirror, so the existing rows/subviews (which read the app-local
/// mirror structs) are unchanged. The tracked-marker derivation
/// (`LatestMarkers.derive`) runs in shared; the view is a pure function of the
/// shared state. Bare type names (`LatestMarker`, `BloodTestReport`, `BloodMarker`)
/// resolve to the app-local structs; the shared inputs are read as `SharedCore.*`
/// inside [map].
///
/// Wired this pass: networked read + retry + pull-to-refresh. "Add reading" and
/// "Upload lab PDF" navigate to their sheets as before; the lab-PDF upload itself
/// is a platform-stubbed affordance (document picker + multipart-SSE, no shared SSE
/// client yet — the repo emits a graceful failure).
struct BloodOverviewView: View {

    /// Local mirror of the shared `BloodOverviewViewModel.UiState`.
    enum ScreenState {
        case loading
        case ready(markers: [LatestMarker], reports: [BloodTestReport])
        case error(String)
    }

    private let vm: BloodOverviewViewModel
    @State private var state: ScreenState = .loading
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.bloodOverviewViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Blood")
            .accessibilityIdentifier("blood-overview")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? BloodOverviewViewModelUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
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
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            VStack(spacing: 16) {
                ContentUnavailableView("Couldn’t load blood data", systemImage: "drop",
                                       description: Text(message))
                Button("Retry") { vm.retry() }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
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
            .refreshable { vm.refresh() }
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

    // MARK: - Mapping (SKIE-free: Kotlin state → local ScreenState mirror)

    private static func map(_ s: BloodOverviewViewModelUiState) -> ScreenState {
        switch s {
        case let ready as BloodOverviewViewModelUiStateReady:
            return .ready(
                markers: ready.trackedMarkers.map(mapMarker),
                reports: ready.recentReports.map(mapReport),
            )
        case let error as BloodOverviewViewModelUiStateError:
            return .error(error.message)
        default:  // Loading
            return .loading
        }
    }

    private static func mapMarker(_ m: SharedCore.LatestMarker) -> LatestMarker {
        LatestMarker(
            marker: mapBloodMarker(m.marker),
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
            // Source is a sealed class (Manual | Lab); a Lab source means it came
            // from an extracted report.
            isLab: !(p.source is MarkerHistoryPointSourceManual),
        )
    }

    private static func mapReport(_ r: SharedCore.BloodTestReport) -> BloodTestReport {
        BloodTestReport(
            reportId: r.reportId,
            sampleDate: r.sampleDate.map(localDateToDate),
            labSource: r.labSource,
            markers: r.markers.map(mapExtractedMarker),
            pdfDownloadPath: r.pdfDownloadPath,
        )
    }

    private static func mapExtractedMarker(_ e: SharedCore.ExtractedMarker) -> ExtractedMarker {
        let flag: ExtractedMarker.Flag?
        if let f = e.flag {
            flag = (f == SharedCore.ExtractedMarker.Flag.h) ? .high : .low
        } else {
            flag = nil
        }
        return ExtractedMarker(name: e.name, value: e.value?.doubleValue, unit: e.unit, flag: flag)
    }

    private static func mapSource(_ s: SharedCore.LatestMarker.Source) -> LatestMarker.Source {
        if s == SharedCore.LatestMarker.Source.manual { return .manual }
        if s == SharedCore.LatestMarker.Source.lab { return .lab }
        return .none
    }

    /// The shared `BloodMarker` enum name is the backend wire name (e.g. "LDL"),
    /// which is exactly the local mirror's rawValue.
    private static func mapBloodMarker(_ m: SharedCore.BloodMarker) -> BloodMarker {
        BloodMarker(rawValue: m.name) ?? .ldl
    }

    /// Kotlin `LocalDate` → Swift `Date` (midnight UTC of that calendar day).
    private static func localDateToDate(_ d: Kotlinx_datetimeLocalDate) -> Date {
        Date(timeIntervalSince1970: Double(d.toEpochDays()) * 86_400)
    }
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
