import SwiftUI
// import SharedCore  // TodaysDosesViewModel, TodaysDosesUiState, TodaysDose, TimeWindow — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave B — the dose checklist. Parity target: Android
/// `feature-medical/.../today/TodaysDosesScreen.kt` + `TodaysDosesViewModel`.
///
/// This is ALSO the D9 local-notification deep-link target: the "Take"/"Take all"
/// / body tap on a medication reminder routes `healthfitness://dose-checklist/{id}`
/// here via `NotificationDelegate` → `MedicationsRoute.todaysDoses` (see
/// ios/HealthFitness/Notifications/). Toggling a dose logs to the shared adherence
/// mirror, which re-emits through `observeTodaysDoses()` and both updates this
/// list AND lets `LocalReminderScheduler` recompute the outstanding set — so a dose
/// taken here silently clears/decrements the pending reminder (the Android
/// "Take all left the reminder on screen" bug, fixed by the single reactive source).
///
/// Follows the reference `MedicationsListView` pattern: local ScreenState mirror of
/// the shared sealed `TodaysDosesUiState`, replaced post-0D by switching directly on
/// the SKIE-bridged enum.
struct TodaysDosesView: View {

    enum ScreenState {
        case loading
        case ready([DoseRow])
        case error(String)
    }

    struct DoseRow: Identifiable {
        let id: String          // "medicationId:window"
        let medicationId: String
        let name: String
        let window: String      // TimeWindow label
        let doseSummary: String // "1 tab"
        let taken: Bool
    }

    @State private var state: ScreenState = .loading

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Today’s doses")
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(TodaysDosesViewModel(
        //         medications: DI.medicationCrudRepository,
        //         adherence: DI.adherenceRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load doses", systemImage: "pills",
                                   description: Text(message))
        case .ready(let doses):
            if doses.isEmpty {
                ContentUnavailableView("Nothing due today", systemImage: "checkmark.circle",
                                       description: Text("You’re all caught up."))
            } else {
                List {
                    Section("Due today") {
                        ForEach(doses) { dose in
                            Button {
                                toggle(dose)
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: dose.taken ? "checkmark.circle.fill" : "circle")
                                        .foregroundStyle(dose.taken ? Theme.good : Theme.textTertiary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(dose.name).font(.hfBodyMd)
                                            .foregroundStyle(Theme.textPrimary)
                                        Text("\(dose.window) · \(dose.doseSummary)")
                                            .font(.hfBodySm)
                                            .foregroundStyle(Theme.textSecondary)
                                    }
                                    Spacer()
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .formMaxWidth()
            }
        }
    }

    private func toggle(_ dose: DoseRow) {
        // Post-0D: vm.wrapped.toggle(dose.shared) — the shared VM writes to the
        // adherence mirror; the reactive source re-emits and updates `state`.
        // Optimistic local flip is intentionally NOT done here (the shared reactive
        // source is the single source of truth — parity with Android).
    }

    // static func map(_ s: TodaysDosesUiState) -> ScreenState { ... }  // Phase 0D
}
