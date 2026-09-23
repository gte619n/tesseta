import SwiftUI
// import SharedCore  // NewGymViewModel — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D(iii) — create a gym.
/// Parity target (Android): `feature-workouts/.../NewGymScreen.kt` +
/// `NewGymViewModel`. Binds the shared `NewGymViewModel.form` and calls
/// `submit { locationId in ... }` on success (the integrator pops to the new
/// gym's detail).
struct NewGymView: View {
    @State private var form = GymFormView.FormModel()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        GymFormView(
            form: $form,
            coverPhotoUrl: nil,
            submitTitle: "Create gym",
            onSubmit: {
                // vm.update(form); vm.submit { locationId in route to detail }
            }
        )
        .navigationTitle("New gym")
        .navigationBarTitleDisplayMode(.inline)
        // Post-0D: .task { observe NewGymViewModel.form -> form }
    }
}

#Preview {
    NavigationStack { NewGymView() }
}
