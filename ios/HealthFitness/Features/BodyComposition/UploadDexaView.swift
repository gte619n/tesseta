import SwiftUI
import SharedCore

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

    private let vm = IosComposition.shared.uploadDexaViewModel()
    @State private var phase: Phase = .idle
    @State private var isOnline = true
    @State private var showPicker = false
    @State private var stateSub: FlowSubscription?
    @State private var onlineSub: FlowSubscription?

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
        .onAppear {
            stateSub = IosComposition.shared.collectFlow(flow: vm.state) { value in
                if let s = value as? UploadDexaViewModelUiState { phase = Self.map(s) }
            }
            onlineSub = IosComposition.shared.collectFlow(flow: vm.isOnline) { value in
                if let b = value as? KotlinBoolean { isOnline = b.boolValue }
            }
        }
        .onDisappear { stateSub?.cancel(); onlineSub?.cancel() }
        .sheet(isPresented: $showPicker) {
            DocumentPicker { fileName, data in
                vm.upload(fileName: fileName, bytes: data.toKotlinByteArray())
            }
        }
    }

    static func map(_ s: UploadDexaViewModelUiState) -> Phase {
        switch s {
        case let p as UploadDexaViewModelUiStateInProgress:
            return .inProgress(phase: p.phase, message: p.message)
        case let c as UploadDexaViewModelUiStateComplete:
            return .complete(scanId: c.scanId)
        case let f as UploadDexaViewModelUiStateFailed:
            return .failed(f.error)
        default:
            return .idle
        }
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

    /// Present the `.pdf` picker; the pick callback drives the shared VM's upload
    /// (which enforces the 25 MB guard).
    private func presentDocumentPicker() {
        showPicker = true
    }
}
