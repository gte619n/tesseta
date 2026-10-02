import PhotosUI
import SwiftUI
// import SharedCore  // EditGymViewModel, PendingUpload — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D(iii) — edit a gym.
/// Parity target (Android): `feature-workouts/.../EditGymScreen.kt` +
/// `EditGymViewModel`. Binds the shared `EditGymViewModel.form` /
/// `coverPhotoUrl` / `uploading`; the cover-photo slot uses `PhotosPicker`, and
/// the picked item is streamed to `uploadCoverPhoto(PendingUpload)`.
struct EditGymView: View {
    let locationId: String

    @State private var form = GymFormView.FormModel(submitting: true)
    @State private var coverPhotoUrl: String?
    @State private var uploading = false
    @State private var photoItem: PhotosPickerItem?
    @State private var showPhotoPicker = false

    var body: some View {
        GymFormView(
            form: $form,
            coverPhotoUrl: coverPhotoUrl,
            uploading: uploading,
            onPickCover: { showPhotoPicker = true },
            submitTitle: "Save changes",
            onSubmit: {
                // vm.update(form); vm.submit { pop() }
            }
        )
        .navigationTitle("Edit gym")
        .navigationBarTitleDisplayMode(.inline)
        .photosPicker(isPresented: $showPhotoPicker, selection: $photoItem, matching: .images)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self) {
                    _ = data
                    // vm.uploadCoverPhoto(PendingUpload(bytes: data, contentType: "image/jpeg"))
                }
            }
        }
        // Post-0D: .task { observe EditGymViewModel(locationId) form/cover/uploading }
    }
}

#Preview {
    NavigationStack { EditGymView(locationId: "gym-1") }
}
