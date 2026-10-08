import SwiftUI
import SharedCore

/// IMPL-IOS-01 — medication detail / edit. Parity target: Android
/// `feature-medical/.../detail/MedicationDetailScreen.kt` + `MedicationDetailViewModel`.
///
/// Backed by the SHARED `MedicationDetailViewModel` over the online-first
/// `HttpMedicationCrudRepository` + `HttpReminderSettingsRepository` (the existing
/// `GET/PUT/POST /api/me/medications/{id}` + reminder-settings endpoints). Observed
/// via `collectFlow` + a static `map(...)` to the local mirror — the same SKIE-free
/// pattern as `MedicationsListView`. Discontinue/reactivate/delete + the reminder
/// toggle route to the VM; `deleted` pops the view back.
struct MedicationDetailView: View {

    let medicationId: String

    enum ScreenState {
        case loading
        case ready(Detail)
        case error(String)
    }

    struct Detail {
        let name: String
        let doseSummary: String
        let scheduleSummary: String
        let status: String
        let notes: String?
        let history: [HistoryRow]
        let reminderEnabled: Bool
        let actionInFlight: Bool
    }

    struct HistoryRow: Identifiable {
        let id: String
        let change: String
        let when: String
    }

    @Environment(\.dismiss) private var dismiss
    private let vm: MedicationDetailViewModel
    @State private var state: ScreenState = .loading
    @State private var reminderEnabled = true
    @State private var subscription: FlowSubscription?
    @State private var deletedSub: FlowSubscription?
    @State private var reminderSub: FlowSubscription?

    init(medicationId: String) {
        self.medicationId = medicationId
        self.vm = IosComposition.shared.medicationDetailViewModel(medicationId: medicationId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Medication")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("medication-detail")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? MedicationDetailUiState { state = Self.map(s) }
                }
                reminderSub = IosComposition.shared.collectFlow(flow: vm.reminder) { value in
                    if let r = value as? MedicationReminderUiState { reminderEnabled = r.config.enabled }
                }
                deletedSub = IosComposition.shared.collectFlow(flow: vm.deleted) { value in
                    if let gone = value as? KotlinBoolean, gone.boolValue { dismiss() }
                }
            }
            .onDisappear {
                subscription?.cancel()
                reminderSub?.cancel()
                deletedSub?.cancel()
            }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load medication", systemImage: "pills",
                                   description: Text(message))
        case .ready(let detail):
            List {
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(detail.name).font(.hfHeadingLg)
                        Text(detail.doseSummary).font(.hfBodyMd)
                            .foregroundStyle(Theme.textSecondary)
                        Text(detail.scheduleSummary).font(.hfBodySm)
                            .foregroundStyle(Theme.textSecondary)
                    }
                }
                if let notes = detail.notes, !notes.isEmpty {
                    Section("Notes") { Text(notes).font(.hfBodyMd) }
                }
                Section("Reminders") {
                    Toggle("Remind me", isOn: Binding(
                        get: { reminderEnabled },
                        set: { on in
                            vm.onReminderChange(config: InlineReminderConfig(enabled: on, times: [:]))
                            vm.saveReminder()
                        },
                    ))
                    NavigationLink("Edit reminder settings", value: MedicationsRoute.reminderSettings)
                }
                if !detail.history.isEmpty {
                    Section("History") {
                        ForEach(detail.history) { row in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.change).font(.hfBodyMd)
                                Text(row.when).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                            }
                        }
                    }
                }
                Section {
                    if detail.status == "ACTIVE" {
                        Button("Discontinue") { discontinue() }
                            .foregroundStyle(Theme.warn)
                    } else {
                        Button("Resume") { reactivate() }
                            .foregroundStyle(Theme.accent)
                    }
                    Button("Delete", role: .destructive) { vm.delete() }
                }
                .disabled(detail.actionInFlight)
            }
            .formMaxWidth()
        }
    }

    // MARK: intents

    private func discontinue() {
        // Discontinue "today" with the default (OTHER) reason; the detailed reason
        // sheet is a follow-up — the shared VM accepts the full signature.
        vm.discontinue(
            reason: SharedCore.DiscontinueReason.other,
            notes: nil,
            endDate: Self.today(),
        )
    }

    private func reactivate() {
        vm.reactivate(resumeDate: nil)
    }

    // MARK: map + helpers

    private static func map(_ s: MedicationDetailUiState) -> ScreenState {
        switch s {
        case let ready as MedicationDetailUiStateReady:
            let med = ready.detail.medication
            let name = med.customName ?? med.drug?.name ?? "Medication"
            let history = ready.detail.history.map { h in
                HistoryRow(
                    id: h.historyId,
                    change: "\(h.previousValue) → \(h.newValue)",
                    when: instantLabel(h.changedAt),
                )
            }
            return .ready(Detail(
                name: name,
                doseSummary: "\(doseText(med.dose)) \(med.unit)",
                scheduleSummary: frequencyLabel(med.frequency),
                status: med.status.name,
                notes: med.notes,
                history: history,
                reminderEnabled: true,
                actionInFlight: ready.actionInFlight,
            ))
        case let error as MedicationDetailUiStateError:
            return .error(error.message)
        default:
            return .loading
        }
    }

    private static func doseText(_ dose: Double) -> String {
        dose == dose.rounded() ? String(Int(dose)) : String(format: "%g", dose)
    }

    private static func frequencyLabel(_ f: FrequencyConfig) -> String {
        switch f.type {
        case SharedCore.FrequencyType.daily:   return "Daily"
        case SharedCore.FrequencyType.weekly:  return "Weekly"
        case SharedCore.FrequencyType.monthly: return "Monthly"
        case SharedCore.FrequencyType.prn:     return "As needed"
        case SharedCore.FrequencyType.cycle:   return "Cycle"
        default: return "Schedule"
        }
    }

    private static func instantLabel(_ instant: Kotlinx_datetimeInstant) -> String {
        let date = Date(timeIntervalSince1970: Double(instant.toEpochMilliseconds()) / 1000.0)
        let fmt = DateFormatter()
        fmt.dateStyle = .medium
        return fmt.string(from: date)
    }

    private static func today() -> Kotlinx_datetimeLocalDate {
        let comps = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        return Kotlinx_datetimeLocalDate(
            year: Int32(comps.year ?? 2026),
            monthNumber: Int32(comps.month ?? 1),
            dayOfMonth: Int32(comps.day ?? 1),
        )
    }
}
