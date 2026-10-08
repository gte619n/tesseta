import SwiftUI
import SharedCore

/// Settings › Workout preferences editor (IMPL-IOS-01). Free-text standing
/// instructions the program designer honors (exercises to avoid, injuries, style).
/// Wired to the shared `WorkoutPreferencesViewModel` over
/// `GET/PUT api/me/workout-programs/settings` — the same SKIE-free `collectFlow`
/// pattern as the other settings screens. The source of truth is the backend
/// (synced); this seeds the editor from the stored value and saves through.
struct WorkoutPreferencesEditor: View {

    private let vm: WorkoutPreferencesViewModel
    private let maxLength: Int

    @State private var text = ""
    /// True once the user has edited, so a later server echo doesn't stomp the draft.
    @State private var dirty = false
    @State private var saveState: WorkoutPreferencesViewModel.SaveState = .idle
    @State private var storedSub: FlowSubscription?
    @State private var saveSub: FlowSubscription?

    init() {
        let model = IosComposition.shared.workoutPreferencesViewModel()
        self.vm = model
        self.maxLength = Int(model.maxLength)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextEditor(text: $text)
                .font(.hfBodyMd)
                .frame(minHeight: 96)
                .padding(8)
                .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 8))
                .onChange(of: text) { _, new in
                    if new.count > maxLength { text = String(new.prefix(maxLength)) }
                    dirty = true
                    vm.onEdited()
                }
            HStack {
                Text("\(text.count)/\(maxLength)")
                    .font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Spacer()
                if saveState == .saved {
                    Text("Saved ✓").font(.hfCapsSm).foregroundStyle(Theme.good)
                } else if saveState == .error {
                    Text("Couldn't save").font(.hfCapsSm).foregroundStyle(Theme.alert)
                }
                Button(saveState == .saving ? "Saving…" : "Save") {
                    vm.save(text: text)
                    dirty = false
                }
                .font(.hfBodyMd).tint(Theme.accent)
                .disabled(saveState == .saving)
            }
        }
        .accessibilityIdentifier("workout-preferences")  // IMPL-E2E-01 shared id
        .onAppear {
            storedSub = IosComposition.shared.collectFlow(flow: vm.stored) { value in
                guard let stored = value as? String else { return }
                // Seed from the backend only until the user starts editing.
                if !dirty { text = stored }
            }
            saveSub = IosComposition.shared.collectFlow(flow: vm.saveState) { value in
                if let s = value as? WorkoutPreferencesViewModel.SaveState { saveState = s }
            }
        }
        .onDisappear { storedSub?.cancel(); saveSub?.cancel() }
    }
}
