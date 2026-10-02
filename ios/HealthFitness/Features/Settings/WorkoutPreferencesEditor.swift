import SwiftUI

/// Settings › Workout preferences editor (IMPL-IOS-01). Extracted from the old
/// ProfileView stub when ProfileView was wired to the shared ViewModel (1C).
/// Still a placeholder — wires to WorkoutSettingsViewModel when that repo lands.
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
