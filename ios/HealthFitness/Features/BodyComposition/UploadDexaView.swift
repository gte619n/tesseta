import SwiftUI
// import SharedCore  // UploadDexaViewModel, its UiState — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E2 — DEXA-PDF upload (online-only AI flow, D17/#41).
/// Parity target (Android): feature-body-composition `upload/UploadDexaScreen` +
/// `UploadDexaViewModel`. The user picks a DEXA PDF; the shared VM streams the
/// backend's phase events until a parsed scan is saved.
///
/// PLATFORM: the PDF is picked with a `UIDocumentPickerViewController` (`.pdf`).
/// That presentation is STUBBED here (see `presentDocumentPicker`) with the real
/// UI otherwise; wiring the delegate into `vm.upload(fileName:bytes:)` (with the
/// 25 MB guard the shared VM enforces) is the remaining platform-glue step.
struct UploadDexaView: View {

    @Environment(\.dismiss) private var dismiss

    /// Local mirror of `UploadDexaViewModel.UiState` (deleted post-0D).
    enum Phase: Equatable {
        case idle
        case inProgress(phase: String, message: String?)
        case complete(scanId: String)
        case failed(String)
    }

    @State private var phase: Phase = .idle
    @State private var isOnline = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !isOnline { offlineBanner }

                SettingsCard(title: "Upload a DEXA scan",
                             description: "We'll read the PDF and extract your body-composition breakdown.") {
                    statusView
                }

                Button {
                    presentDocumentPicker()
                } label: {
                    Label("Choose PDF", systemImage: "doc.badge.arrow.up")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(Theme.accent)
                .disabled(!isOnline || isInProgress)
            }
            .padding(16)
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Upload DEXA")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: phase) { _, newValue in
            if case .complete = newValue { dismiss() }
        }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(UploadDexaViewModel(repo: DI.dexaRepo,
        //                                                      connectivity: DI.connectivity))
        //     await vm.observe(vm.wrapped.state) { self.phase = Self.map($0) }
        //     await vm.observe(vm.wrapped.isOnline) { self.isOnline = $0 }
        // }
    }

    @ViewBuilder
    private var statusView: some View {
        switch phase {
        case .idle:
            Text("No file selected yet.")
                .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
        case .inProgress(let p, let message):
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.capitalized).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                    if let message {
                        Text(message).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    }
                }
            }
        case .complete:
            Label("Scan saved", systemImage: "checkmark.circle.fill")
                .font(.hfBodyMd).foregroundStyle(Theme.good)
        case .failed(let message):
            Label(message, systemImage: "exclamationmark.triangle")
                .font(.hfBodySm).foregroundStyle(Theme.alert)
        }
    }

    private var offlineBanner: some View {
        Label("Uploading a DEXA PDF needs a connection.", systemImage: "wifi.slash")
            .font(.hfBodySm).foregroundStyle(Theme.warn)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.warnBg, in: RoundedRectangle(cornerRadius: 10))
    }

    private var isInProgress: Bool {
        if case .inProgress = phase { return true }
        return false
    }

    /// STUB — presents a `.pdf`-restricted `UIDocumentPickerViewController`; the
    /// real wrapper reads bytes off the security-scoped URL and calls
    /// `vm.upload(fileName:bytes:)`. Here we only flip the local phase.
    private func presentDocumentPicker() {
        // let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf])
        // picker.delegate = coordinator  // → reads bytes → vm.upload(fileName:bytes:)
        phase = .inProgress(phase: "uploading", message: "Saving your PDF")
    }

    // static func map(_ s: UploadDexaViewModel.UiState) -> Phase { ... }  // Phase 0D
}
