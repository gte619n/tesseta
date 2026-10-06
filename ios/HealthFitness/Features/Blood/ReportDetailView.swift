import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E1 — lab-report detail. Parity target (Android):
/// feature-blood `ReportDetailScreen` + `components/ExtractedMarkerRow` +
/// `ReportDetailViewModel` — the extracted marker table, a "View PDF" action, and
/// a delete.
///
/// Binds to the SHARED `ReportDetailViewModel(reports, reportId)`. The PDF is
/// fetched via the shared VM's `downloadPdf(report:)` (platform-neutral bytes) and
/// previewed with QuickLook.
///
/// PLATFORM: the PDF preview uses `QLPreviewController` wrapped in a
/// `UIViewControllerRepresentable`. That presentation is STUBBED here (see
/// `viewPdf`) — the real wrapper writes the downloaded bytes to a temp URL and
/// hands the URL to QuickLook.
struct ReportDetailView: View {

    let reportId: String

    enum ScreenState {
        case loading
        case ready(BloodTestReport)
        case error(String)
    }

    private let vm: ReportDetailViewModel
    @State private var state: ScreenState = .loading
    @State private var subscription: FlowSubscription?
    @State private var showDeleteConfirm = false
    @Environment(\.dismiss) private var dismiss

    init(reportId: String) {
        self.reportId = reportId
        self.vm = IosComposition.shared.reportDetailViewModel(reportId: reportId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Lab report")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? ReportDetailViewModelUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
            .toolbar {
                if case .ready = state {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button("View PDF", systemImage: "doc") { viewPdf() }
                            Button("Delete", systemImage: "trash", role: .destructive) {
                                showDeleteConfirm = true
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
            .confirmationDialog("Delete this report?", isPresented: $showDeleteConfirm,
                                titleVisibility: .visible) {
                Button("Delete", role: .destructive) { delete() }
                Button("Cancel", role: .cancel) {}
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(ReportDetailViewModel(reports: DI.bloodReportRepo,
        //                                                        reportId: reportId))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load report", systemImage: "doc",
                                   description: Text(message))
        case .ready(let report):
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SettingsCard(title: report.labSource,
                                 description: BloodFormat.shortDate(report.sampleDate)) {
                        VStack(spacing: 0) {
                            ForEach(report.markers) { m in
                                ExtractedMarkerRowView(marker: m)
                                if m.id != report.markers.last?.id {
                                    Divider().overlay(Theme.borderSubtle)
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

    /// STUB — writes `vm.downloadPdf(report:)` bytes to a temp URL and presents a
    /// `QLPreviewController`. Real presentation is the remaining platform glue.
    private func viewPdf() {
        // let bytes = try await vm.wrapped.downloadPdf(report: report)
        // let url = FileManager.default.temporaryDirectory.appending(path: "\(reportId).pdf")
        // try bytes.write(to: url); present QLPreviewController(url)
    }

    private func delete() {
        // Post-0D: vm.wrapped.delete { dismiss() }
        dismiss()
    }

    // MARK: shared UiState → local ScreenState

    static func map(_ s: ReportDetailViewModelUiState) -> ScreenState {
        switch s {
        case let ready as ReportDetailViewModelUiStateReady:
            return .ready(mapReport(ready.report))
        case let error as ReportDetailViewModelUiStateError:
            return .error(error.message)
        default:
            return .loading
        }
    }

    private static func mapReport(_ r: SharedCore.BloodTestReport) -> BloodTestReport {
        BloodTestReport(
            reportId: r.reportId,
            sampleDate: r.sampleDate.map { Date(timeIntervalSince1970: Double($0.toEpochDays()) * 86_400) },
            labSource: r.labSource,
            markers: r.markers.map { e in
                let flag: ExtractedMarker.Flag?
                if let f = e.flag { flag = (f == SharedCore.ExtractedMarker.Flag.h) ? .high : .low } else { flag = nil }
                return ExtractedMarker(name: e.name, value: e.value?.doubleValue, unit: e.unit, flag: flag)
            },
            pdfDownloadPath: r.pdfDownloadPath,
        )
    }
}

/// One extracted marker row — parity with Android's `ExtractedMarkerRow` (name,
/// value+unit, and an H/L flag chip).
private struct ExtractedMarkerRowView: View {
    let marker: ExtractedMarker
    var body: some View {
        HStack {
            Text(marker.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            Spacer()
            if let flag = marker.flag {
                Text(flag.rawValue)
                    .font(.hfCapsSm)
                    .padding(.horizontal, 6).padding(.vertical, 2)
                    .background(flag == .high ? Theme.alertBg : Theme.warnBg,
                                in: Capsule())
                    .foregroundStyle(flag == .high ? Theme.alert : Theme.warn)
            }
            Text(BloodFormat.markerValue(marker.value, unit: marker.unit ?? ""))
                .font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
        }
        .padding(.vertical, 8)
    }
}
