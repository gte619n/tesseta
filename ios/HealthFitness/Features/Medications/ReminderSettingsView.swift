import SwiftUI
import SharedCore

/// IMPL-IOS-01 — reminder settings. Parity target: Android
/// `feature-medical/.../reminders/ReminderSettingsScreen.kt` + `ReminderSettingsViewModel`.
///
/// Backed by the SHARED `ReminderSettingsViewModel` over the online-first
/// `HttpReminderSettingsRepository` + `HttpMedicationCrudRepository` (the active-meds
/// list for the per-medication override rows). The master switch, per-window default
/// fire times, and per-medication mutes all push into the VM's intents; `save()` writes
/// the `ReminderSettings` doc. Observed via `collectFlow` + a static `map(...)`, the
/// same SKIE-free pattern as `MedicationsListView`.
struct ReminderSettingsView: View {

    /// Local mirror of a per-medication override row.
    struct MedOverride: Identifiable {
        let id: String
        let name: String
        var enabled: Bool
    }

    struct ScreenState {
        var loading = true
        var enabled = true
        var windowTimes: [String: String] = [:]   // TimeWindow.name → "HH:mm"
        var meds: [MedOverride] = []
        var saving = false
    }

    private let vm: ReminderSettingsViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?

    private static let windows: [TimeWindow] = [
        SharedCore.TimeWindow.morning,
        SharedCore.TimeWindow.afternoon,
        SharedCore.TimeWindow.evening,
        SharedCore.TimeWindow.bedtime,
    ]

    init() {
        self.vm = IosComposition.shared.reminderSettingsViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Reminders")
            .accessibilityIdentifier("reminder-settings")  // IMPL-E2E-01 shared id
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button("Save") { vm.save(onSaved: {}) }.disabled(state.saving)
                }
            }
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? ReminderSettingsUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            Form {
                Section {
                    Toggle("Medication reminders", isOn: Binding(
                        get: { state.enabled },
                        set: { vm.setEnabled(enabled: $0) },
                    ))
                }
                if state.enabled {
                    Section("Default times") {
                        ForEach(Self.windows, id: \.name) { window in
                            DatePicker(
                                Self.windowLabel(window),
                                selection: bindingFor(window),
                                displayedComponents: .hourAndMinute,
                            )
                        }
                    }
                    if !state.meds.isEmpty {
                        Section("Per medication") {
                            ForEach(state.meds) { med in
                                Toggle(med.name, isOn: Binding(
                                    get: { med.enabled },
                                    set: { vm.setMedEnabled(medicationId: med.id, enabled: $0) },
                                ))
                            }
                        }
                    }
                }
            }
            .formMaxWidth()
        }
    }

    // MARK: window-time binding (Date ⇄ "HH:mm" pushed into the VM)

    private func bindingFor(_ window: TimeWindow) -> Binding<Date> {
        Binding(
            get: { Self.parse(state.windowTimes[window.name]) ?? Self.defaultTime(window) },
            set: { vm.setWindowTime(window: window, time: Self.format($0)) },
        )
    }

    private static func parse(_ hhmm: String?) -> Date? {
        guard let hhmm, let colon = hhmm.firstIndex(of: ":"),
              let h = Int(hhmm[..<colon]),
              let m = Int(hhmm[hhmm.index(after: colon)...]) else { return nil }
        return Calendar.current.date(from: DateComponents(hour: h, minute: m))
    }

    private static func format(_ date: Date) -> String {
        let c = Calendar.current.dateComponents([.hour, .minute], from: date)
        return String(format: "%02d:%02d", c.hour ?? 0, c.minute ?? 0)
    }

    private static func defaultTime(_ window: TimeWindow) -> Date {
        let comps: DateComponents
        switch window {
        case SharedCore.TimeWindow.morning:   comps = DateComponents(hour: 6, minute: 0)
        case SharedCore.TimeWindow.afternoon: comps = DateComponents(hour: 12, minute: 0)
        case SharedCore.TimeWindow.evening:   comps = DateComponents(hour: 18, minute: 0)
        default:                              comps = DateComponents(hour: 21, minute: 30)  // bedtime
        }
        return Calendar.current.date(from: comps) ?? Date()
    }

    private static func windowLabel(_ window: TimeWindow) -> String {
        switch window {
        case SharedCore.TimeWindow.morning:   return "Morning"
        case SharedCore.TimeWindow.afternoon: return "Afternoon"
        case SharedCore.TimeWindow.evening:   return "Evening"
        default:                              return "Bedtime"
        }
    }

    // MARK: map

    private static func map(_ s: ReminderSettingsUiState) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.enabled = s.enabled
        out.saving = s.saving

        var times: [String: String] = [:]
        for window in windows {
            if let t = s.windowTimes[window] as? String { times[window.name] = t }
        }
        out.windowTimes = times

        out.meds = s.medications.map { med in
            let name = med.customName ?? med.drug?.name ?? "Medication"
            let override = s.perMedication[med.medicationId]
            let enabled = (override as? MedicationReminderOverride)?.enabled ?? true
            return MedOverride(id: med.medicationId, name: name, enabled: enabled)
        }
        return out
    }
}
