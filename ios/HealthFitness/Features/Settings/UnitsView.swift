import SwiftUI
import SharedCore

/// Settings › Units (IMPL-IOS-01 Phase 1C — first shared-ViewModel-backed screen).
///
/// The proven end-to-end pattern every other feature screen follows, SKIE-free:
///   shared `UnitsViewModel` (KMP) ──ObjC bridge──▶ SwiftUI.
/// `preferences` is a Kotlin `StateFlow`; we read its current `.value` to seed
/// state and subscribe via `IosComposition.collectFlow` for live updates (the
/// hand-rolled replacement for SKIE's AsyncSequence). The three `set*` calls
/// write through to the NSUserDefaults-backed repository, so a change persists
/// and the row re-renders immediately.
///
/// `WeightUnit` is referenced as `SharedCore.WeightUnit` because an app-local
/// `enum WeightUnit` (BodyCompositionModels) already owns that bare name.
struct UnitsView: View {
    private let vm: UnitsViewModel
    @State private var prefs: UnitPreferences
    @State private var subscription: FlowSubscription?

    init() {
        // Seed from the StateFlow's current value (ObjC-bridged, cast from Any).
        let model = IosComposition.shared.unitsViewModel()
        self.vm = model
        _prefs = State(initialValue: model.preferences.value as! UnitPreferences)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Height") {
                    SegmentedChoice(
                        options: [(HeightUnit.feetInches, "ft / in"), (HeightUnit.centimeters, "cm")],
                        selection: Binding(
                            get: { prefs.height },
                            set: { if let v = $0 { vm.setHeight(unit: v) } }))
                }
                SettingsCard(title: "Weight") {
                    SegmentedChoice(
                        options: [(SharedCore.WeightUnit.pounds, "lb"), (SharedCore.WeightUnit.kilograms, "kg")],
                        selection: Binding(
                            get: { prefs.weight },
                            set: { if let v = $0 { vm.setWeight(unit: v) } }))
                }
                SettingsCard(title: "Temperature") {
                    SegmentedChoice(
                        options: [(TemperatureUnit.fahrenheit, "°F"), (TemperatureUnit.celsius, "°C")],
                        selection: Binding(
                            get: { prefs.temperature },
                            set: { if let v = $0 { vm.setTemperature(unit: v) } }))
                }
            }
            .formMaxWidth()
            .padding(.vertical, 16)
        }
        .background(Theme.canvas)
        .navigationTitle("Units")
        .accessibilityIdentifier("settings-units")  // IMPL-E2E-01 shared id
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.preferences) { value in
                if let p = value as? UnitPreferences { prefs = p }
            }
        }
        .onDisappear { subscription?.cancel() }
    }
}
