import SwiftUI
import SharedCore

/// Settings › Coach audio (IMPL-IOS-01 Phase 1C). Backed by the shared
/// `CoachAudioSettingsViewModel` (on-device NSUserDefaults), same pattern as
/// Units: seed `@State` from the StateFlow value, `collectFlow` for updates,
/// write the toggles straight through the ViewModel.
struct CoachAudioView: View {
    private let vm: CoachAudioSettingsViewModel
    @State private var settings: CoachAudioSettings
    @State private var subscription: FlowSubscription?

    init() {
        let model = IosComposition.shared.coachAudioViewModel()
        self.vm = model
        _settings = State(initialValue: model.settings.value as! CoachAudioSettings)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Coach audio", description: "Hands-free cues during a workout") {
                    ToggleRow(
                        label: "Rest beep",
                        description: "Beep when a rest period ends",
                        isOn: Binding(
                            get: { settings.restBeep },
                            set: { vm.setRestBeep(enabled: $0) }))
                    ToggleRow(
                        label: "Voice announcements",
                        description: "Speak the exercise, weight, and reps at each set",
                        isOn: Binding(
                            get: { settings.voiceAnnouncements },
                            set: { vm.setVoiceAnnouncements(enabled: $0) }))
                }
            }
            .formMaxWidth()
            .padding(.vertical, 16)
        }
        .background(Theme.canvas)
        .navigationTitle("Coach audio")
        .accessibilityIdentifier("settings-coach-audio")  // IMPL-E2E-01 shared id
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.settings) { value in
                if let s = value as? CoachAudioSettings { settings = s }
            }
        }
        .onDisappear { subscription?.cancel() }
    }
}
