import SwiftUI
// import SharedCore  // ReminderSettingsViewModel, ReminderSettingsUiState, TimeWindow — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave B — reminder settings. Parity target: Android
/// `feature-medical/.../reminders/ReminderSettingsScreen.kt` +
/// `ReminderSettingsViewModel`.
///
/// The master enable switch, the per-window default fire times (MORNING/AFTERNOON/
/// EVENING/BEDTIME), and per-medication overrides (mute + custom slot times) all
/// live on the shared `ReminderSettings` doc. On save the shared VM writes the doc
/// AND re-plans — which is exactly the input the D9 `LocalReminderScheduler`
/// re-reads through the shared `ReminderPlanner` to (re)schedule the next ~48h of
/// `UNCalendarNotificationTrigger`s. This screen never computes fire times itself.
///
/// Follows the reference `MedicationsListView` pattern: local mirror of the shared
/// UiState, replaced post-0D by the SKIE-bridged type.
struct ReminderSettingsView: View {

    /// Mirror of the shared `TimeWindow` (kept local pre-0D; SKIE-bridged after).
    enum Window: String, CaseIterable, Identifiable {
        case morning = "Morning", afternoon = "Afternoon", evening = "Evening", bedtime = "Bedtime"
        var id: String { rawValue }
    }

    struct MedOverride: Identifiable {
        let id: String
        let name: String
        var enabled: Bool
    }

    @State private var loading = true
    @State private var enabled = true
    @State private var windowTimes: [Window: Date] = [:]
    @State private var meds: [MedOverride] = []
    @State private var saving = false

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Reminders")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Save") { save() }.disabled(saving)
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(ReminderSettingsViewModel(
        //         settingsRepo: DI.reminderSettingsRepository,
        //         medications: DI.medicationCrudRepository, onReplan: DI.replan))
        //     await vm.observe(vm.wrapped.state) { self.apply($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Form {
                Section {
                    Toggle("Medication reminders", isOn: $enabled)
                }
                if enabled {
                    Section("Default times") {
                        ForEach(Window.allCases) { window in
                            DatePicker(
                                window.rawValue,
                                selection: bindingFor(window),
                                displayedComponents: .hourAndMinute,
                            )
                        }
                    }
                    if !meds.isEmpty {
                        Section("Per medication") {
                            ForEach($meds) { $med in
                                Toggle(med.name, isOn: $med.enabled)
                            }
                        }
                    }
                }
            }
            .formMaxWidth()
        }
    }

    private func bindingFor(_ window: Window) -> Binding<Date> {
        Binding(
            get: { windowTimes[window] ?? Self.defaultTime(window) },
            set: { windowTimes[window] = $0 },
        )
    }

    /// Built-in defaults (mirror shared `ReminderSettings.DEFAULT_WINDOW_TIMES`):
    /// MORNING 06:00, AFTERNOON 12:00, EVENING 18:00, BEDTIME 21:30.
    private static func defaultTime(_ window: Window) -> Date {
        let comps: DateComponents
        switch window {
        case .morning:   comps = DateComponents(hour: 6, minute: 0)
        case .afternoon: comps = DateComponents(hour: 12, minute: 0)
        case .evening:   comps = DateComponents(hour: 18, minute: 0)
        case .bedtime:   comps = DateComponents(hour: 21, minute: 30)
        }
        return Calendar.current.date(from: comps) ?? Date()
    }

    private func save() {
        // Post-0D: push the window times / mutes into the shared VM
        // (setEnabled/setWindowTime/setMedEnabled) then vm.wrapped.save { }.
        // The shared VM writes ReminderSettings and re-plans (D9 reschedule).
    }

    // private func apply(_ s: ReminderSettingsUiState) { ... }  // Phase 0D
}
