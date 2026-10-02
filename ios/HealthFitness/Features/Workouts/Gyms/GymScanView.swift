import PhotosUI
import SwiftUI
// import SharedCore  // GymScanViewModel, its UiState/Stage/Row, ScanPreview — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D(iii) — the gym equipment scan (IMPL-GYM-003).
/// Parity target (Android): `feature-workouts/.../GymScanScreen.kt` +
/// `GymScanViewModel`. Record/pick a walkthrough video → the shared VM uploads,
/// polls, and returns the detected equipment → the user reviews each row
/// (use-match / create-new / skip, with a rename) → confirm.
///
/// The scan uses the device camera (a video capture via `PhotosPicker` with
/// `.videos`, or a `UIImagePickerController` camera source) as the equipment
/// source; a lighter fallback lets the user skip the scan and add equipment
/// manually. The heavy CV runs server-side (the VM's poll loop); on-device
/// Vision is reserved for a future quick local pre-pass.
struct GymScanView: View {
    let locationId: String

    enum Stage { case idle, uploading, analyzing, review, confirming, done }

    struct PreviewItem: Identifiable {
        let id: Int              // index
        let parsedName: String
        let matchName: String?
        var action: String       // USE_MATCH | CREATE_NEW | SKIP
        var nameOverride: String
    }

    @State private var stage: Stage = .idle
    @State private var items: [PreviewItem] = []
    @State private var addedCount = 0
    @State private var error: String?
    @State private var videoItem: PhotosPickerItem?
    @State private var showPicker = false

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Scan equipment")
            .navigationBarTitleDisplayMode(.inline)
            .photosPicker(isPresented: $showPicker, selection: $videoItem, matching: .videos)
            .onChange(of: videoItem) { _, item in
                guard let item else { return }
                Task {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        _ = data
                        // vm.analyzeVideo(mimeType: "video/mp4", sizeBytes: Int64(data.count)) { data }
                    }
                }
            }
        // Post-0D: .task { observe GymScanViewModel(locationId).state -> apply }
    }

    @ViewBuilder
    private var content: some View {
        switch stage {
        case .idle:
            idlePrompt
        case .uploading, .analyzing:
            VStack(spacing: 12) {
                ProgressView()
                Text(stage == .uploading ? "Uploading video…" : "Reading equipment…")
                    .font(.hfBodyMd).foregroundStyle(Theme.textSecondary)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        case .review, .confirming:
            reviewList
        case .done:
            ContentUnavailableView {
                Label("Added \(addedCount) piece\(addedCount == 1 ? "" : "s")", systemImage: "checkmark.circle")
            } description: {
                Text("Your gym's equipment is updated.")
            }
        }
    }

    private var idlePrompt: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Scan a walkthrough",
                             description: "Record a slow pan around your gym (under 200 MB). We'll read the equipment and let you confirm it.") {
                    Button {
                        showPicker = true
                    } label: {
                        Label("Record or choose a video", systemImage: "video.badge.plus")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    if let error { Text(error).font(.hfBodySm).foregroundStyle(Theme.alert) }
                }
                Text("Prefer to add equipment by hand? Go back and pick from the catalog.")
                    .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding()
            .formMaxWidth()
        }
    }

    private var reviewList: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: 12) {
                    if let error { Text(error).font(.hfBodySm).foregroundStyle(Theme.alert) }
                    ForEach($items) { $item in
                        SettingsCard(title: item.matchName ?? item.parsedName) {
                            SegmentedChoice(
                                options: [
                                    ("USE_MATCH", "Match"),
                                    ("CREATE_NEW", "New"),
                                    ("SKIP", "Skip"),
                                ],
                                selection: Binding(get: { Optional(item.action) },
                                                   set: { item.action = $0 ?? item.action }),
                                isEnabled: item.matchName != nil || item.action != "USE_MATCH"
                            )
                            if item.action == "CREATE_NEW" {
                                TextField("Name", text: $item.nameOverride)
                                    .textFieldStyle(.plain).padding(10)
                                    .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 10))
                            }
                        }
                    }
                }
                .padding()
                .formMaxWidth()
            }
            Divider()
            Button {
                // vm.confirm()
            } label: {
                if stage == .confirming { ProgressView() } else { Text("Add equipment").frame(maxWidth: .infinity) }
            }
            .buttonStyle(.borderedProminent).tint(Theme.accent)
            .disabled(stage == .confirming)
            .padding()
            .formMaxWidth()
        }
    }
}

#Preview {
    NavigationStack { GymScanView(locationId: "gym-1") }
}
