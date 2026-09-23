import SwiftUI
// import SharedCore  // UploadLabReportViewModel, its UiState — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E1 — lab-PDF upload (online-only AI flow, D17).
/// Parity target (Android): feature-blood `UploadLabReportScreen` +
/// `components/UploadPhaseStepper` + `UploadLabReportViewModel`. The user picks a
/// PDF; the shared VM streams the backend's upload → extract → save phases.
///
/// PLATFORM: the PDF is picked with a `UIDocumentPickerViewController`
/// (`.pdf` content type) — presented from SwiftUI via a `UIViewController
/// Representable` wrapper. That wrapper is STUBBED here (see `presentDocumentPicker`)
/// with the real UI otherwise; wiring the picker's delegate callback into
/// `vm.upload(fileName:bytes:)` is the remaining platform-glue step.
struct UploadLabReportView: View {

    @Environment(\.dismiss) private var dismiss

    /// Local mirror of `UploadLabReportViewModel.UiState` (deleted post-0D).
    enum Phase: Equatable {
        case idle, uploading, extracting, saving, complete, failed(String)
    }

    @State private var phase: Phase = .idle
    /// Mirror of the shared VM's `isOnline` — gates the picker (nothing queues offline).
    @State private var isOnline = true

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if !isOnline {
                    offlineBanner
                }

                SettingsCard(title: "Upload a lab report",
                             description: "We'll read the PDF and extract your marker values.") {
                    stepper
                }

                pickerButton
            }
            .padding(16)
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Upload lab PDF")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: phase) { _, newValue in
            if newValue == .complete { dismiss() }
        }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(UploadLabReportViewModel(reports: DI.bloodReportRepo,
        //                                                           connectivity: DI.connectivity))
        //     await vm.observe(vm.wrapped.state) { self.phase = Self.map($0) }
        // }
        // .task { await vm.observe(vm.wrapped.isOnline) { self.isOnline = $0 } }
    }

    // MARK: Phase stepper (parity with Android's UploadPhaseStepper)

    private var stepper: some View {
        VStack(alignment: .leading, spacing: 10) {
            stepRow("Uploading", active: phase == .uploading, done: isPast(.uploading))
            stepRow("Extracting markers", active: phase == .extracting, done: isPast(.extracting))
            stepRow("Saving", active: phase == .saving, done: isPast(.saving))
            if case .failed(let message) = phase {
                Text(message).font(.hfBodySm).foregroundStyle(Theme.alert)
            }
        }
    }

    private func stepRow(_ label: String, active: Bool, done: Bool) -> some View {
        HStack(spacing: 10) {
            Group {
                if done {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.good)
                } else if active {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "circle").foregroundStyle(Theme.textQuaternary)
                }
            }
            .frame(width: 20)
            Text(label)
                .font(.hfBodyMd)
                .foregroundStyle(active || done ? Theme.textPrimary : Theme.textTertiary)
        }
    }

    private var pickerButton: some View {
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

    private var offlineBanner: some View {
        Label("Uploading a lab PDF needs a connection.", systemImage: "wifi.slash")
            .font(.hfBodySm)
            .foregroundStyle(Theme.warn)
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.warnBg, in: RoundedRectangle(cornerRadius: 10))
    }

    private var isInProgress: Bool {
        switch phase { case .uploading, .extracting, .saving: return true; default: return false }
    }

    private func isPast(_ target: Phase) -> Bool {
        func rank(_ p: Phase) -> Int {
            switch p {
            case .idle: return 0
            case .uploading: return 1
            case .extracting: return 2
            case .saving: return 3
            case .complete: return 4
            case .failed: return -1
            }
        }
        return rank(phase) > rank(target)
    }

    /// STUB — presents a `UIDocumentPickerViewController` restricted to `.pdf`.
    /// The real wrapper reads the picked file's bytes off the security-scoped URL
    /// and calls `vm.upload(fileName:bytes:)`; here we only flip the local phase so
    /// the stepper animates. Replace with the representable wrapper in platform glue.
    private func presentDocumentPicker() {
        // let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.pdf])
        // picker.delegate = coordinator  // → reads bytes → vm.upload(fileName:bytes:)
        // UIApplication.topViewController?.present(picker, animated: true)
        phase = .uploading
    }

    // static func map(_ s: UploadLabReportViewModel.UiState) -> Phase { ... }  // Phase 0D
}
