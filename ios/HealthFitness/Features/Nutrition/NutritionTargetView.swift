import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave C — the macro-target editor (networked shared screen).
/// Parity target (Android): `NutritionTargetScreen` + `NutritionTargetViewModel`.
/// Loads the current target, edits the six macro fields, saves; `saved` drives a
/// one-shot confirmation.
///
/// Backed by the SHARED `NutritionTargetViewModel`
/// (shared/.../presentation/nutrition/NutritionTargetViewModel.kt) over
/// `HttpNutritionDayRepository` (`GET/PUT api/me/nutrition/target`), observed via
/// `collectFlow` + a static `map(...)` to the local `ScreenState` — the same
/// SKIE-free pattern as `NutritionTodayView`. The editable `Draft` holds the field
/// bindings; on each emission (unless a save is in flight) it is re-seeded from the
/// shared `target`, and `save` hands a shared `Macros` straight to the VM.
struct NutritionTargetView: View {

    /// Editable field bindings (raw strings so the user can type freely); `macros`
    /// parses them into a shared `Macros` for the VM's `save`.
    struct Draft {
        var calories = ""
        var protein = ""
        var carbs = ""
        var fat = ""
        var fiber = ""
        var sugar = ""

        var macros: SharedCore.Macros {
            SharedCore.Macros(
                caloriesKcal: Double(calories).map { KotlinDouble(double: $0) },
                proteinGrams: Double(protein).map { KotlinDouble(double: $0) },
                carbsGrams: Double(carbs).map { KotlinDouble(double: $0) },
                fatGrams: Double(fat).map { KotlinDouble(double: $0) },
                fiberGrams: Double(fiber).map { KotlinDouble(double: $0) },
                sugarGrams: Double(sugar).map { KotlinDouble(double: $0) }
            )
        }

        init() {}
        init(_ m: SharedCore.Macros?) {
            func str(_ k: KotlinDouble?) -> String {
                guard let v = k?.doubleValue else { return "" }
                return NutritionFormat.wholeNumber(v)
            }
            calories = str(m?.caloriesKcal)
            protein = str(m?.proteinGrams)
            carbs = str(m?.carbsGrams)
            fat = str(m?.fatGrams)
            fiber = str(m?.fiberGrams)
            sugar = str(m?.sugarGrams)
        }
    }

    /// Local mirror of the shared `NutritionTargetUiState`.
    struct ScreenState {
        var loading = true
        var saving = false
        var saved = false
        var error: String?
        var target: SharedCore.Macros?
    }

    private let vm: NutritionTargetViewModel
    @State private var state: ScreenState
    @State private var draft = Draft()
    @State private var subscription: FlowSubscription?

    init() {
        let model = IosComposition.shared.nutritionTargetViewModel()
        self.vm = model
        _state = State(initialValue: Self.map(model.state.value as! NutritionTargetUiState))
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Daily targets")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("nutrition-target")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    guard let s = value as? NutritionTargetUiState else { return }
                    let mapped = Self.map(s)
                    state = mapped
                    // Re-seed the editable draft from the server target, but never while
                    // a save is mid-flight (so the user's in-progress edits aren't stomped).
                    if !mapped.saving { draft = Draft(mapped.target) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(spacing: 16) {
                    if let error = state.error {
                        Text(error).font(.hfBodySm).foregroundStyle(Theme.alert)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    SettingsCard(title: "Macros", description: "Your daily goal for each nutrient.") {
                        field("Calories", text: $draft.calories, unit: "kcal")
                        field("Protein", text: $draft.protein, unit: "g")
                        field("Carbs", text: $draft.carbs, unit: "g")
                        field("Fat", text: $draft.fat, unit: "g")
                        field("Fiber", text: $draft.fiber, unit: "g")
                        field("Sugar", text: $draft.sugar, unit: "g")
                    }
                    Button {
                        vm.save(target: draft.macros)
                    } label: {
                        Text(state.saving ? "Saving…" : (state.saved ? "Saved ✓" : "Save"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(state.saving)
                }
                .padding()
                .formMaxWidth()
            }
        }
    }

    private func field(_ label: String, text: Binding<String>, unit: String) -> some View {
        HStack {
            Text(label).font(.hfBodyMd)
            Spacer()
            TextField("0", text: text)
                .keyboardType(.numberPad)
                .multilineTextAlignment(.trailing)
                .font(.hfMonoSm)
                .frame(width: 80)
            Text(unit).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
        }
    }

    // MARK: Map shared UiState → local mirror

    static func map(_ s: NutritionTargetUiState) -> ScreenState {
        ScreenState(
            loading: s.loading,
            saving: s.saving,
            saved: s.saved,
            error: s.error,
            target: s.target
        )
    }
}
