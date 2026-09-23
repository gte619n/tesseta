import SwiftUI
// import SharedCore  // MedicationDetailViewModel, MedicationDetailUiState, MedicationDetail — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave B — medication detail / edit. Parity target: Android
/// `feature-medical/.../detail/MedicationDetailScreen.kt` + `MedicationDetailViewModel`.
///
/// Offline-first (the shared VM seeds from the Room mirror instantly, then
/// revalidates to graft on the pull-only change history, D9). Edit actions —
/// dose/schedule change, start-date edit, discontinue/reactivate, delete, and the
/// inline reminder override — all live on the shared VM; saving a reminder change
/// re-plans so the D9 `LocalReminderScheduler` updates this med's notifications.
///
/// Follows the reference `MedicationsListView` pattern: local ScreenState mirror
/// of the shared sealed `MedicationDetailUiState`, replaced post-0D by the
/// SKIE-bridged enum. `deleted` pops the view back.
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
    @State private var state: ScreenState = .loading
    @State private var showDiscontinue = false

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Medication")
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(MedicationDetailViewModel(
        //         medicationId: medicationId, medications: DI.medicationCrudRepository,
        //         reminderSettings: DI.reminderSettingsRepository, onReplan: DI.replan))
        //     await vm.observe(vm.wrapped.state)   { self.state = Self.map($0) }
        //     // observe vm.wrapped.deleted → dismiss()
        // }
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
                    HStack {
                        Text("Reminders")
                        Spacer()
                        Text(detail.reminderEnabled ? "On" : "Off")
                            .foregroundStyle(Theme.textSecondary)
                    }
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
                    Button("Delete", role: .destructive) { delete() }
                }
                .disabled(detail.actionInFlight)
            }
            .formMaxWidth()
        }
    }

    // MARK: intents (delegate to the shared VM post-0D)

    private func discontinue() {
        // Post-0D: vm.wrapped.discontinue(reason, notes, endDate)
    }
    private func reactivate() {
        // Post-0D: vm.wrapped.reactivate(resumeDate)
    }
    private func delete() {
        // Post-0D: vm.wrapped.delete()  (observed `deleted` → dismiss())
    }

    // static func map(_ s: MedicationDetailUiState) -> ScreenState { ... }  // Phase 0D
}
