import SwiftUI
// import SharedCore  // AddMedicationViewModel, AddMedicationUiState, Drug, DrugLookupEvent — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave B — the add-medication flow. Parity target: Android
/// `feature-medical/.../add/AddMedicationScreen.kt` + `AddMedicationViewModel`.
///
/// Three steps mirror the shared `AddMedicationUiState.Step`:
///   SEARCH  — catalog search + debounced online-only AI drug lookup (SSE)
///   FORM    — dose / unit / frequency / inline reminder for a picked drug
///   CUSTOM  — manual entry when there is no catalog/AI match
///
/// On submit the shared VM creates the medication AND merges the inline reminder
/// override onto the shared `ReminderSettings` doc, then re-plans — which is what
/// the D9 `LocalReminderScheduler` re-reads (via the shared `ReminderPlanner`) to
/// schedule the new med's notifications. The view holds no scheduling logic.
///
/// Follows the reference `MedicationsListView` pattern: a local mirror of the
/// shared step/UiState, replaced post-0D by the SKIE-bridged types.
struct AddMedicationView: View {

    enum Step { case search, form, custom }

    struct DrugRow: Identifiable {
        let id: String
        let name: String
        let form: String
    }

    @Environment(\.dismiss) private var dismiss

    @State private var step: Step = .search
    @State private var query: String = ""
    @State private var results: [DrugRow] = []
    @State private var isLooking = false
    @State private var isOnline = true

    // FORM / CUSTOM inputs (local drafts; committed via the shared VM's submit()).
    @State private var customName = ""
    @State private var dose = ""
    @State private var unit = "mg"
    @State private var frequency: Frequency = .daily
    @State private var reminderEnabled = true

    enum Frequency: String, CaseIterable { case daily = "Daily", weekly = "Weekly", monthly = "Monthly", prn = "As needed" }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(navTitle)
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(AddMedicationViewModel(...))
        //     await vm.observe(vm.wrapped.state) { self.apply($0) }
        // }
    }

    private var navTitle: String {
        switch step {
        case .search: return "Add medication"
        case .form, .custom: return "Details"
        }
    }

    @ViewBuilder
    private var content: some View {
        switch step {
        case .search: searchStep
        case .form, .custom: formStep
        }
    }

    // MARK: SEARCH

    private var searchStep: some View {
        List {
            Section {
                TextField("Search medications", text: $query)
                    .textInputAutocapitalization(.words)
                    .onChange(of: query) { _, _ in onQueryChange() }
            }

            if isLooking {
                Section {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text(isOnline ? "Looking it up…" : "")
                            .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                    }
                }
            }

            if !results.isEmpty {
                Section("Matches") {
                    ForEach(results) { drug in
                        Button { select(drug) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(drug.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                                Text(drug.form).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            Section {
                Button("Enter manually") { step = .custom }
                    .font(.hfBodyMd)
            }
        }
        .formMaxWidth()
    }

    // MARK: FORM / CUSTOM

    private var formStep: some View {
        Form {
            if step == .custom {
                Section("Medication") {
                    TextField("Name", text: $customName)
                }
            }
            Section("Dose") {
                TextField("Amount", text: $dose)
                    .keyboardType(.decimalPad)
                TextField("Unit", text: $unit)
            }
            Section("Schedule") {
                Picker("Frequency", selection: $frequency) {
                    ForEach(Frequency.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
            }
            Section("Reminders") {
                Toggle("Remind me", isOn: $reminderEnabled)
                Text("Fires at your window default time; edit per-slot times in Reminder settings.")
                    .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            }
            Section {
                Button("Save medication") { submit() }
                    .disabled(!canSave)
                Button("Back to search") { step = .search }
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .formMaxWidth()
    }

    private var canSave: Bool {
        let hasName = step != .custom || !customName.trimmingCharacters(in: .whitespaces).isEmpty
        return hasName && Double(dose) != nil
    }

    // MARK: intents (delegate to the shared VM post-0D)

    private func onQueryChange() {
        // Post-0D: vm.wrapped.onQueryChange(query) — the shared VM filters the
        // cached catalog and, if no match + online + >=3 chars, debounces the SSE
        // lookup. `results`/`isLooking`/`step` come back through the observed state.
    }

    private func select(_ drug: DrugRow) {
        // Post-0D: vm.wrapped.selectDrug(drug.shared) → advances to FORM.
        step = .form
    }

    private func submit() {
        // Post-0D: build CreateMedicationRequest + InlineReminderConfig and call
        // vm.wrapped.submit(request, reminder) { _ in dismiss() }. The shared VM
        // creates the med, persists the reminder override, and re-plans (D9).
        dismiss()
    }

    // private func apply(_ s: AddMedicationUiState) { ... }  // Phase 0D
}
