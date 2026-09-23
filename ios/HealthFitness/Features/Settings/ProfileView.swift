import SwiftUI
// import SharedCore  // ProfileViewModel, ProfileViewModel.UiState, Profile,
//                       HeightMetric, HeightUnit — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A2 — Profile editor.
/// Parity target (Android): `feature-settings/.../profile/ProfileScreen.kt` +
/// the shared `ProfileViewModel`. Observes the SHARED VM through the
/// `ObservableViewModel` bridge; the view is a pure function of its UiState.
/// Height edits honor the user's `heightUnit` preference (ft/in vs cm), exactly
/// like Android.
struct ProfileView: View {

    /// Local mirror of the shared `ProfileViewModel.UiState`. Post-0D this is
    /// deleted and the view switches on the SKIE-bridged enum directly.
    enum ScreenState {
        case loading
        case loaded(ProfileForm, saving: Bool)
        case error(String)
    }

    /// The rendered profile fields (mirror of the shared `Profile`).
    struct ProfileForm {
        var displayName: String?
        var email: String?
        var heightCm: Int?
        var biologicalSex: String?   // "MALE" | "FEMALE" | nil
        var dateOfBirth: String?     // ISO "YYYY-MM-DD" | nil
    }

    @State private var state: ScreenState = .loading
    @State private var useCentimeters = false   // mirrors heightUnit == CENTIMETERS

    // Height editor working values.
    @State private var feet = 5
    @State private var inches = 10
    @State private var cm = 178
    @State private var biologicalSex: String? = nil

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Profile")
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(ProfileViewModel(repo: DI.profileRepository,
        //                                                    unitPrefs: DI.unitPrefs))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load profile", systemImage: "person.crop.circle",
                                   description: Text(message))
        case .loaded(let form, let saving):
            ScrollView {
                VStack(spacing: 16) {
                    identityCard(form)
                    heightCard(saving: saving)
                    calorieInputsCard(form, saving: saving)
                }
                .padding()
                .formMaxWidth()
            }
            .disabled(saving)
        }
    }

    private func identityCard(_ form: ProfileForm) -> some View {
        SettingsCard(title: "You") {
            row("Name", form.displayName ?? "—")
            row("Email", form.email ?? "—")
        }
    }

    private func heightCard(saving: Bool) -> some View {
        SettingsCard(title: "Height", description: "Used across the app for your stats") {
            if useCentimeters {
                Stepper(value: $cm, in: 120...230) {
                    Text("\(cm) cm").font(.hfBodyMd)
                }
                Button("Save") { /* vm.saveHeightCm(cm) */ }
                    .font(.hfBodyMd).tint(Theme.accent)
            } else {
                HStack(spacing: 12) {
                    Stepper(value: $feet, in: 3...8) { Text("\(feet) ft").font(.hfBodyMd) }
                    Stepper(value: $inches, in: 0...11) { Text("\(inches) in").font(.hfBodyMd) }
                }
                Button("Save") { /* vm.saveHeight(feet, inches) — HeightMetric.ftInToCm */ }
                    .font(.hfBodyMd).tint(Theme.accent)
            }
        }
    }

    private func calorieInputsCard(_ form: ProfileForm, saving: Bool) -> some View {
        SettingsCard(title: "Calorie estimate",
                     description: "Biological sex and date of birth feed the Mifflin-St Jeor estimate") {
            VStack(alignment: .leading, spacing: 6) {
                Text("Biological sex").font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                SegmentedChoice(
                    options: [("MALE", "Male"), ("FEMALE", "Female")],
                    selection: $biologicalSex
                )
                // .onChange → vm.saveBiologicalSex($0)
            }
            NavRow(label: "Date of birth", subtitle: form.dateOfBirth ?? "Not set") {
                // present a date picker → vm.saveDateOfBirth(iso)
            }
        }
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            Spacer()
            Text(value).font(.hfBodyMd).foregroundStyle(Theme.textTertiary)
        }
    }

    // static func map(_ s: ProfileViewModel.UiState) -> ScreenState { ... }  // Phase 0D
}

/// Free-text standing-instructions editor (WorkoutPreferencesViewModel). Lives
/// here so the Settings hub can embed it; post-0D it binds to the shared VM's
/// `stored` + `saveState` flows (2000-char cap = vm.wrapped.maxLength).
struct WorkoutPreferencesEditor: View {
    @State private var text = ""
    private let maxLength = 2000   // vm.wrapped.maxLength post-0D

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextEditor(text: $text)
                .font(.hfBodyMd)
                .frame(minHeight: 96)
                .padding(8)
                .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 8))
                .onChange(of: text) { _, new in
                    if new.count > maxLength { text = String(new.prefix(maxLength)) }
                    // vm.wrapped.onEdited()
                }
            HStack {
                Text("\(text.count)/\(maxLength)")
                    .font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Spacer()
                Button("Save") { /* vm.wrapped.save(text) */ }
                    .font(.hfBodyMd).tint(Theme.accent)
            }
        }
    }
}
