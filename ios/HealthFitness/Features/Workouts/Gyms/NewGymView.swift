import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D(iii) — create a gym.
/// Parity target (Android): `feature-workouts/.../NewGymScreen.kt` +
/// `NewGymViewModel`. Binds the shared `NewGymViewModel.form` and calls
/// `submit { locationId in ... }` on success (pops back to the list, which
/// re-fetches the new gym).
struct NewGymView: View {
    private let vm: NewGymViewModel
    @State private var form = GymFormView.FormModel()
    @State private var subscription: FlowSubscription?
    @Environment(\.dismiss) private var dismiss

    init() {
        self.vm = IosComposition.shared.makeNewGymViewModel()
    }

    var body: some View {
        GymFormView(
            form: $form,
            coverPhotoUrl: nil,
            submitTitle: "Create gym",
            onSubmit: {
                vm.update(state: GymForm.toShared(form))
                vm.submit { _ in dismiss() }
            }
        )
        .navigationTitle("New gym")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.form) { value in
                // Adopt only the VM-owned submit/error flags; the text fields stay
                // locally edited (the VM mirrors them on submit).
                if let f = value as? LocationFormState {
                    form.submitting = f.submitting
                    form.error = f.error
                }
            }
        }
        .onDisappear { subscription?.cancel() }
    }
}

#Preview {
    NavigationStack { NewGymView() }
}
