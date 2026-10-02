import SwiftUI
import SharedCore

// Pin the shared types for readability. `WeightUnit` is NOT aliased here because
// an app-local `enum WeightUnit` (BodyCompositionModels) already owns that name
// at module scope — it's referenced as `SharedCore.WeightUnit` inline instead.
private typealias HeightUnit = SharedCore.HeightUnit
private typealias TemperatureUnit = SharedCore.TemperatureUnit
private typealias UnitPreferences = SharedCore.UnitPreferences

/// Settings › Units (IMPL-IOS-01 Phase 1C — first shared-ViewModel-backed screen).
///
/// This is the proven end-to-end pattern every other feature screen follows:
///   shared `UnitsViewModel` (KMP) ──SKIE──▶ `ObservableViewModel` bridge ──▶ SwiftUI.
/// The ViewModel's `preferences` StateFlow surfaces as a Swift `AsyncSequence`
/// (SKIE); we collect it in `.task` and republish into `@State`, and the three
/// `set*` calls write through to the NSUserDefaults-backed repository — so a
/// change persists and the row re-renders immediately.
struct UnitsView: View {
    @State private var vm = ObservableViewModel(IosComposition.shared.unitsViewModel())
    @State private var prefs = UnitPreferences(
        height: .feetInches, weight: .pounds, temperature: .fahrenheit)

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Height") {
                    SegmentedChoice(
                        options: [(HeightUnit.feetInches, "ft / in"), (.centimeters, "cm")],
                        selection: Binding(
                            get: { prefs.height },
                            set: { if let v = $0 { vm.wrapped.setHeight(unit: v) } }))
                }
                SettingsCard(title: "Weight") {
                    SegmentedChoice(
                        options: [(SharedCore.WeightUnit.pounds, "lb"), (.kilograms, "kg")],
                        selection: Binding(
                            get: { prefs.weight },
                            set: { if let v = $0 { vm.wrapped.setWeight(unit: v) } }))
                }
                SettingsCard(title: "Temperature") {
                    SegmentedChoice(
                        options: [(TemperatureUnit.fahrenheit, "°F"), (.celsius, "°C")],
                        selection: Binding(
                            get: { prefs.temperature },
                            set: { if let v = $0 { vm.wrapped.setTemperature(unit: v) } }))
                }
            }
            .formMaxWidth()
            .padding(.vertical, 16)
        }
        .background(Theme.canvas)
        .navigationTitle("Units")
        .accessibilityIdentifier("settings-units")  // IMPL-E2E-01 shared id
        .task {
            // SKIE exposes the property as the ObjC-bridgeable SkieKotlinStateFlow;
            // annotating the type bridges it to SkieSwiftStateFlow, which conforms
            // to AsyncSequence. Then republish each emission into @State.
            let flow: SharedCore.SkieSwiftStateFlow<UnitPreferences> = vm.wrapped.preferences
            await vm.observe(flow) { prefs = $0 }
        }
    }
}
