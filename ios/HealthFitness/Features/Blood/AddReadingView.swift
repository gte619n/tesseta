import SwiftUI
// import SharedCore  // AddReadingViewModel, its FormState, BloodMarker — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E1 — manual reading entry. Parity target (Android):
/// feature-blood `AddReadingScreen` + `AddReadingViewModel` — marker picker,
/// numeric value, optional unit/date/lab-source/notes, and a submit that goes
/// through the shared VM's optimistic outbox create.
///
/// Binds to the SHARED `AddReadingViewModel.FormState`; the `canSubmit` gate and
/// the create live in shared. This local `Form` struct is the pre-0D mirror.
struct AddReadingView: View {

    @Environment(\.dismiss) private var dismiss

    /// Local mirror of `AddReadingViewModel.FormState` (deleted post-0D).
    struct Form {
        var marker: BloodMarker?
        var value: String = ""
        var unit: String = ""
        var sampleDate: Date = .now
        var labSource: String = ""
        var notes: String = ""
        var submitting: Bool = false
        var error: String?

        var canSubmit: Bool { marker != nil && Double(value) != nil && !submitting }
    }

    @State private var form = Form()

    var body: some View {
        SwiftUI.Form {
            Section("Marker") {
                Picker("Marker", selection: $form.marker) {
                    Text("Select…").tag(BloodMarker?.none)
                    ForEach(BloodMarker.allCases) { m in
                        Text(m.displayName).tag(BloodMarker?.some(m))
                    }
                }
            }

            Section("Value") {
                HStack {
                    TextField("Value", text: $form.value)
                        .keyboardType(.decimalPad)
                    TextField("Unit (optional)", text: $form.unit)
                        .frame(width: 120)
                        .foregroundStyle(Theme.textSecondary)
                }
            }

            Section("Sample details") {
                DatePicker("Sample date", selection: $form.sampleDate, displayedComponents: .date)
                TextField("Lab source (optional)", text: $form.labSource)
                TextField("Notes (optional)", text: $form.notes, axis: .vertical)
                    .lineLimit(1...4)
            }

            if let error = form.error {
                Section { Text(error).font(.hfBodySm).foregroundStyle(Theme.alert) }
            }
        }
        .navigationTitle("Add reading")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Save") { submit() }
                    .disabled(!form.canSubmit)
            }
            ToolbarItem(placement: .cancellationAction) {
                Button("Cancel") { dismiss() }
            }
        }
    }

    private func submit() {
        // Post-0D: vm.wrapped.onMarker/onValue/... then vm.wrapped.submit { dismiss() }
        // The shared VM validates + fires the optimistic outbox create; on success
        // it invokes the completion, which pops back to the overview.
        form.submitting = true
        dismiss()
    }
}
