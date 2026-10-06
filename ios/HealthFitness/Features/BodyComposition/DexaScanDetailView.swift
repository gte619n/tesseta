import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E2 — DEXA scan detail. Parity target (Android):
/// feature-body-composition `detail/DexaScanDetailScreen` + `DexaRegionGrid` +
/// `EditableNumberCell` + `DexaScanDetailViewModel` — top-line totals, a
/// per-region breakdown grid with inline editable numeric cells (optimistic
/// PATCH), a "View PDF" action, and a delete.
///
/// Binds to the SHARED `DexaScanDetailViewModel(repo, unitPrefsRepo, scanId)`.
/// Field edits call `vm.patchField(path:value:)` (optimistic in shared); the
/// transient-message `SharedFlow` surfaces save/delete failures.
///
/// PLATFORM: PDF preview via `QLPreviewController` — STUBBED in `viewPdf`.
struct DexaScanDetailView: View {

    let scanId: String

    struct ScreenState {
        var scan: DexaScan?
        var loading = true
        var deleting = false
        var error: String?
    }

    private let vm: DexaScanDetailViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?
    @State private var showDeleteConfirm = false
    @Environment(\.dismiss) private var dismiss

    init(scanId: String) {
        self.scanId = scanId
        self.vm = IosComposition.shared.dexaScanDetailViewModel(scanId: scanId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("DEXA scan")
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? DexaScanDetailViewModel.UiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
            .toolbar {
                if state.scan != nil {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button("View PDF", systemImage: "doc") { viewPdf() }
                            Button("Delete scan", systemImage: "trash", role: .destructive) {
                                showDeleteConfirm = true
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                }
            }
            .confirmationDialog("Delete this scan?", isPresented: $showDeleteConfirm,
                                titleVisibility: .visible) {
                Button("Delete", role: .destructive) { delete() }
                Button("Cancel", role: .cancel) {}
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(DexaScanDetailViewModel(repo: DI.dexaRepo,
        //                                                          unitPrefsRepo: DI.unitPrefs,
        //                                                          scanId: scanId))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        //     for await msg in vm.wrapped.messages { /* toast(msg) */ }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading && state.scan == nil {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error, state.scan == nil {
            ContentUnavailableView("Couldn’t load scan", systemImage: "figure",
                                   description: Text(error))
        } else if let scan = state.scan {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    SettingsCard(title: scan.sourceFacility ?? "DEXA scan",
                                 description: scan.measuredOn.map(BodyCompositionFormat.shortDate) ?? "—") {
                        totals(scan)
                    }

                    SettingsCard(title: "Regions",
                                 description: "Body composition breakdown") {
                        regionGrid(scan)
                    }
                }
                .padding(16)
                .formMaxWidth()
            }
        }
    }

    private func totals(_ scan: DexaScan) -> some View {
        VStack(spacing: 0) {
            statRow("Total mass", BodyCompositionFormat.pounds(scan.totalMassLb))
            statRow("Body fat %", BodyCompositionFormat.percent(scan.totalBodyFatPercent))
            statRow("Lean tissue", BodyCompositionFormat.pounds(scan.leanTissueLb))
            statRow("Fat tissue", BodyCompositionFormat.pounds(scan.fatTissueLb))
            statRow("Visceral fat", BodyCompositionFormat.pounds(scan.visceralFatLb))
            statRow("A/G ratio", scan.androidGynoidRatio.map { String(format: "%.2f", $0) } ?? "—")
            statRow("BMD T-score", scan.bmdTScore.map { String(format: "%.1f", $0) } ?? "—")
            statRow("BMD Z-score", scan.bmdZScore.map { String(format: "%.1f", $0) } ?? "—")
        }
    }

    private func statRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(value).font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
        }
        .padding(.vertical, 7)
    }

    /// Per-region grid — parity with `DexaRegionGrid`. Each editable cell fires an
    /// optimistic `vm.patchField(path:value:)`; the path convention is
    /// "<region>.<field>" (e.g. "trunk.leanTissueLb").
    private func regionGrid(_ scan: DexaScan) -> some View {
        VStack(spacing: 0) {
            ForEach(DexaRegionKey.allCases) { key in
                let region = scan.regions[key]
                HStack {
                    Text(key.label).font(.hfBodySm).foregroundStyle(Theme.textPrimary)
                    Spacer()
                    EditableNumberCell(
                        value: region?.leanTissueLb,
                        suffix: "lb lean"
                    ) { newValue in
                        patchField(path: "\(key.rawValue).leanTissueLb", value: newValue)
                    }
                    EditableNumberCell(
                        value: region?.fatTissueLb,
                        suffix: "lb fat"
                    ) { newValue in
                        patchField(path: "\(key.rawValue).fatTissueLb", value: newValue)
                    }
                }
                .padding(.vertical, 7)
                if key != DexaRegionKey.allCases.last { Divider().overlay(Theme.borderSubtle) }
            }
        }
    }

    private func patchField(path: String, value: Double?) {
        // Optimistic mutation + PATCH happen in the shared VM; failures revert and
        // emit on the message flow.
        vm.patchField(path: path, value: value.map { KotlinDouble(value: $0) })
    }

    /// STUB — presents `QLPreviewController` over `vm.downloadPdf()` bytes
    /// (QuickLook glue is the remaining platform piece of #6).
    private func viewPdf() {
        // let bytes = try await vm.downloadPdf()
        // write to temp URL → QLPreviewController
    }

    private func delete() {
        vm.delete { dismiss() }
    }

    // MARK: shared UiState → local ScreenState

    static func map(_ s: DexaScanDetailViewModel.UiState) -> ScreenState {
        ScreenState(
            scan: s.scan.map(mapScan),
            loading: s.loading,
            deleting: s.deleting,
            error: s.error,
        )
    }

    private static func mapScan(_ s: SharedCore.DexaScan) -> DexaScan {
        var regions: [DexaRegionKey: DexaRegion] = [:]
        func put(_ key: DexaRegionKey, _ r: SharedCore.DexaRegion?) {
            if let r { regions[key] = mapRegion(r) }
        }
        put(.trunk, s.trunk); put(.android, s.android); put(.gynoid, s.gynoid)
        put(.armsTotal, s.armsTotal); put(.armsRight, s.armsRight); put(.armsLeft, s.armsLeft)
        put(.legsTotal, s.legsTotal); put(.legsRight, s.legsRight); put(.legsLeft, s.legsLeft)
        return DexaScan(
            scanId: s.scanId,
            measuredOn: s.measuredOn.map { Date(timeIntervalSince1970: Double($0.toEpochDays()) * 86_400) },
            sourceFacility: s.sourceFacility,
            totalMassLb: s.totalMassLb?.doubleValue,
            leanTissueLb: s.leanTissueLb?.doubleValue,
            fatTissueLb: s.fatTissueLb?.doubleValue,
            totalBodyFatPercent: s.totalBodyFatPercent?.doubleValue,
            visceralFatLb: s.visceralFatLb?.doubleValue,
            androidGynoidRatio: s.androidGynoidRatio?.doubleValue,
            bmdTScore: s.bmdTScore?.doubleValue,
            bmdZScore: s.bmdZScore?.doubleValue,
            regions: regions,
        )
    }

    private static func mapRegion(_ r: SharedCore.DexaRegion) -> DexaRegion {
        DexaRegion(
            totalMassLb: r.totalMassLb?.doubleValue,
            leanTissueLb: r.leanTissueLb?.doubleValue,
            fatTissueLb: r.fatTissueLb?.doubleValue,
            regionFatPercent: r.regionFatPercent?.doubleValue,
        )
    }
}

/// Inline-editable numeric cell — parity with Android's `EditableNumberCell`.
/// Tapping reveals a decimal-pad text field; on commit the (optional) parsed
/// value is handed back for the optimistic PATCH. A blank commit clears the field.
struct EditableNumberCell: View {
    let value: Double?
    let suffix: String
    let onCommit: (Double?) -> Void

    @State private var editing = false
    @State private var text = ""

    var body: some View {
        Group {
            if editing {
                TextField("", text: $text)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .frame(width: 64)
                    .onSubmit { commit() }
                    .submitLabel(.done)
            } else {
                Button {
                    text = value.map { String(format: "%.1f", $0) } ?? ""
                    editing = true
                } label: {
                    Text(value.map { String(format: "%.1f", $0) } ?? "—")
                        .font(.hfMonoSm).foregroundStyle(Theme.textPrimary)
                        .frame(width: 64, alignment: .trailing)
                }
                .buttonStyle(.plain)
            }
        }
        .overlay(alignment: .bottom) {
            Text(suffix).font(.system(size: 8)).foregroundStyle(Theme.textQuaternary)
                .offset(y: 8)
        }
    }

    private func commit() {
        editing = false
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        onCommit(trimmed.isEmpty ? nil : Double(trimmed))
    }
}
