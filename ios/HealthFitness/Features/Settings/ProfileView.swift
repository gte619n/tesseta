import SwiftUI
import SharedCore

/// Settings › Profile (IMPL-IOS-01 Phase 1C — first NETWORKED shared screen).
///
/// Backed by the shared `ProfileViewModel` over the existing backend endpoints
/// (GET /api/me, PATCH /api/me) through the one authenticated Ktor client — the
/// same session token login established. Demonstrates the networked pattern:
/// read the sealed `UiState` StateFlow via `collectFlow`, render per case, and
/// write a field (biological sex) straight back through the ViewModel.
struct ProfileView: View {
    private let vm: ProfileViewModel
    @State private var state: ProfileViewModelUiState
    @State private var subscription: FlowSubscription?

    init() {
        let model = IosComposition.shared.profileViewModel()
        self.vm = model
        _state = State(initialValue: model.state.value as! ProfileViewModelUiState)
    }

    var body: some View {
        content
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.canvas)
            .navigationTitle("Profile")
            .accessibilityIdentifier("settings-profile")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? ProfileViewModelUiState { state = s }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder private var content: some View {
        switch state {
        case let loaded as ProfileViewModelUiStateLoaded:
            form(loaded.profile, saving: loaded.saving)
        case let error as ProfileViewModelUiStateError:
            errorView(error.message)
        default:  // Loading
            ProgressView().controlSize(.large).tint(Theme.accent)
        }
    }

    private func form(_ profile: Profile, saving: Bool) -> some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Account") {
                    labeledRow("Name", profile.displayName ?? "—")
                    labeledRow("Email", profile.email ?? "—")
                }
                SettingsCard(title: "Height") {
                    labeledRow("Height", profile.heightCm.map { "\($0.intValue) cm" } ?? "Not set")
                }
                SettingsCard(title: "Biological sex", description: "Feeds your calorie estimate") {
                    SegmentedChoice(
                        options: [("MALE", "Male"), ("FEMALE", "Female")],
                        selection: Binding(
                            get: { profile.biologicalSex },
                            set: { vm.saveBiologicalSex(biologicalSex: $0) }))
                    .disabled(saving)
                }
                SettingsCard(title: "Date of birth") {
                    labeledRow("DOB", profile.dateOfBirth ?? "Not set")
                }
            }
            .formMaxWidth()
            .padding(.vertical, 16)
        }
    }

    private func labeledRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.hfBodyMd).foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(value).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
        }
        .padding(.vertical, 4)
    }

    private func errorView(_ message: String) -> some View {
        VStack(spacing: 16) {
            Text("Couldn't load your profile").font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            Text(message).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            Button("Retry") { vm.refresh() }
                .buttonStyle(.borderedProminent).tint(Theme.accent)
        }
        .padding(32)
    }
}
