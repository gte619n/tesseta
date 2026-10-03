import PhotosUI
import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D(iii) — edit a gym.
/// Parity target (Android): `feature-workouts/.../EditGymScreen.kt` +
/// `EditGymViewModel`. Binds the shared `EditGymViewModel.form` /
/// `coverPhotoUrl` / `uploading`; the cover-photo slot uses `PhotosPicker`, and
/// the picked item is streamed to `uploadCoverPhoto(PendingUpload)`.
struct EditGymView: View {
    let locationId: String

    private let vm: EditGymViewModel
    @State private var form = GymFormView.FormModel(submitting: true)
    @State private var coverPhotoUrl: String?
    @State private var uploading = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false
    @State private var seeded = false
    @State private var formSub: FlowSubscription?
    @State private var coverSub: FlowSubscription?
    @State private var uploadingSub: FlowSubscription?
    @Environment(\.dismiss) private var dismiss

    init(locationId: String) {
        self.locationId = locationId
        self.vm = IosComposition.shared.editGymViewModel(locationId: locationId)
    }

    var body: some View {
        GymFormView(
            form: $form,
            coverPhotoUrl: coverPhotoUrl,
            uploading: uploading,
            onPickCover: { showPhotoPicker = true },
            submitTitle: "Save changes",
            onSubmit: {
                vm.update(state: GymForm.toShared(form))
                vm.submit { dismiss() }
            }
        )
        .navigationTitle("Edit gym")
        .navigationBarTitleDisplayMode(.inline)
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    let bytes = data.toKotlinByteArray()
                    vm.uploadCoverPhoto(
                        file: PendingUpload(bytes: bytes, contentType: "image/jpeg")
                    )
                }
            }
        }
        .onAppear {
            formSub = IosComposition.shared.collectFlow(flow: vm.form) { value in
                guard let f = value as? LocationFormState else { return }
                // Seed the editable fields once the server form lands (submitting
                // flips false); after that only mirror the submit/error flags so an
                // in-progress edit isn't stomped.
                if !seeded && !f.submitting {
                    form = GymForm.fromShared(f)
                    seeded = true
                } else {
                    form.submitting = f.submitting
                    form.error = f.error
                }
            }
            coverSub = IosComposition.shared.collectFlow(flow: vm.coverPhotoUrl) { value in
                coverPhotoUrl = value as? String
            }
            uploadingSub = IosComposition.shared.collectFlow(flow: vm.uploading) { value in
                uploading = (value as? KotlinBoolean)?.boolValue ?? false
            }
        }
        .onDisappear {
            formSub?.cancel(); coverSub?.cancel(); uploadingSub?.cancel()
        }
    }
}

#Preview {
    NavigationStack { EditGymView(locationId: "gym-1") }
}

extension Data {
    /// Bridge Swift `Data` to a Kotlin `ByteArray` (for `PendingUpload` + the gym
    /// scan video). Builds the array in one pass via the indexed initializer.
    func toKotlinByteArray() -> KotlinByteArray {
        let bytes = [UInt8](self)
        let array = KotlinByteArray(size: Int32(bytes.count))
        for (i, b) in bytes.enumerated() {
            array.set(index: Int32(i), value: Int8(bitPattern: b))
        }
        return array
    }
}
