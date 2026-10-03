import SwiftUI
import SharedCore

/// IMPL-IOS-01 — the add-medication flow. Parity target: Android
/// `feature-medical/.../add/AddMedicationScreen.kt` + `AddMedicationViewModel`.
///
/// Backed by the SHARED `AddMedicationViewModel` over the online-first
/// `HttpDrugRepository` (catalog search) + `HttpMedicationCrudRepository` (create) +
/// `HttpReminderSettingsRepository` (inline reminder override). Three steps mirror the
/// shared `AddMedicationUiState.Step` (SEARCH → FORM → CUSTOM). Observed via
/// `collectFlow`, the same SKIE-free pattern as `MedicationsListView`.
///
/// DEGRADED: the AI SSE drug lookup is not available on iOS yet (no shared SSE
/// client), so `HttpDrugRepository.lookupStream` emits a single NotFound — the
/// search step falls back to catalog match + "Enter manually". Catalog search,
/// manual entry, create, and the reminder toggle are all fully wired.
struct AddMedicationView: View {

    struct DrugRow: Identifiable {
        let id: String
        let drug: Drug          // the shared type, for selectDrug
        let name: String
        let form: String
    }

    @Environment(\.dismiss) private var dismiss

    private let vm: AddMedicationViewModel
    @State private var step: AddMedicationUiState.Step
    @State private var results: [DrugRow] = []
    @State private var isLooking = false
    @State private var isOnline = true
    @State private var subscription: FlowSubscription?

    // FORM / CUSTOM inputs (local drafts; committed via the shared VM's submit()).
    @State private var query = ""
    @State private var customName = ""
    @State private var selectedDrug: Drug?
    @State private var dose = ""
    @State private var unit = "mg"
    @State private var frequency: FrequencyChoice = .daily
    @State private var reminderEnabled = true

    enum FrequencyChoice: String, CaseIterable {
        case daily = "Daily", weekly = "Weekly", monthly = "Monthly", prn = "As needed"
        var config: FrequencyConfig {
            let type: FrequencyType
            switch self {
            case .daily:   type = SharedCore.FrequencyType.daily
            case .weekly:  type = SharedCore.FrequencyType.weekly
            case .monthly: type = SharedCore.FrequencyType.monthly
            case .prn:     type = SharedCore.FrequencyType.prn
            }
            return FrequencyConfig(type: type, timesPerPeriod: nil, specificDays: nil, cycle: nil)
        }
    }

    init() {
        let model = IosComposition.shared.addMedicationViewModel()
        self.vm = model
        let initial = model.state.value as! AddMedicationUiState
        _step = State(initialValue: initial.step)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(navTitle)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("med-add-screen")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? AddMedicationUiState { apply(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    private var navTitle: String {
        step == SharedCore.AddMedicationUiState.Step.search ? "Add medication" : "Details"
    }

    @ViewBuilder
    private var content: some View {
        if step == SharedCore.AddMedicationUiState.Step.search {
            searchStep
        } else {
            formStep
        }
    }

    // MARK: SEARCH

    private var searchStep: some View {
        List {
            Section {
                TextField("Search medications", text: $query)
                    .textInputAutocapitalization(.words)
                    .onChange(of: query) { _, newValue in vm.onQueryChange(query: newValue) }
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
                        Button { vm.selectDrug(drug: drug.drug) } label: {
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
                Button("Enter manually") { vm.startManualEntry() }
                    .font(.hfBodyMd)
            }
        }
        .formMaxWidth()
    }

    // MARK: FORM / CUSTOM

    private var formStep: some View {
        Form {
            if step == SharedCore.AddMedicationUiState.Step.custom {
                Section("Medication") {
                    TextField("Name", text: $customName)
                }
            } else if let name = selectedDrug?.name {
                Section("Medication") {
                    Text(name).font(.hfBodyMd)
                }
            }
            Section("Dose") {
                TextField("Amount", text: $dose)
                    .keyboardType(.decimalPad)
                TextField("Unit", text: $unit)
            }
            Section("Schedule") {
                Picker("Frequency", selection: $frequency) {
                    ForEach(FrequencyChoice.allCases, id: \.self) { Text($0.rawValue).tag($0) }
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
                Button("Back to search") { vm.backToSearch() }
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .formMaxWidth()
    }

    private var canSave: Bool {
        let isCustom = step == SharedCore.AddMedicationUiState.Step.custom
        let hasName = !isCustom || !customName.trimmingCharacters(in: .whitespaces).isEmpty
        return hasName && Double(dose) != nil
    }

    // MARK: intents

    private func submit() {
        guard let doseValue = Double(dose) else { return }
        let isCustom = step == SharedCore.AddMedicationUiState.Step.custom
        let request = CreateMedicationRequest(
            drugId: isCustom ? nil : selectedDrug?.drugId,
            customName: isCustom ? customName.trimmingCharacters(in: .whitespaces) : nil,
            customCategory: nil,
            customForm: nil,
            dose: doseValue,
            unit: unit.isEmpty ? "mg" : unit,
            frequency: frequency.config,
            timeSlots: [],
            notes: nil,
            prescribedBy: nil,
            correlatedMarkers: [],
        )
        let reminder = InlineReminderConfig(enabled: reminderEnabled, times: [:])
        vm.submit(request: request, reminder: reminder) { _ in dismiss() }
    }

    // MARK: apply observed state

    private func apply(_ s: AddMedicationUiState) {
        step = s.step
        isLooking = s.isLooking
        isOnline = (vm.online.value as? KotlinBoolean)?.boolValue ?? true
        selectedDrug = s.selectedDrug
        // Pre-fill the unit from a picked drug the first time we land on the form.
        if let picked = s.selectedDrug, unit == "mg" { unit = picked.defaultUnit }
        results = s.filteredCatalog.map { drug in
            DrugRow(id: drug.drugId, drug: drug, name: drug.name, form: formLabel(drug.form))
        }
    }

    private func formLabel(_ form: DrugForm) -> String {
        form.name.replacingOccurrences(of: "_", with: " ").capitalized
    }
}
