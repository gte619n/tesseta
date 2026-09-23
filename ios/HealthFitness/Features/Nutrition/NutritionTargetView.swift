import SwiftUI
// import SharedCore  // NutritionTargetViewModel, NutritionTargetUiState, Macros — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C — the macro-target editor. Parity target (Android):
/// `NutritionTargetScreen` + `NutritionTargetViewModel`. Loads the current target,
/// edits the six macro fields, saves; `saved` drives a one-shot confirmation.
///
/// Observes the SHARED `NutritionTargetViewModel` through the `ObservableViewModel`
/// bridge post-0D; the local `Draft` mirror below keeps the field bindings.
struct NutritionTargetView: View {

    struct Draft {
        var calories = ""
        var protein = ""
        var carbs = ""
        var fat = ""
        var fiber = ""
        var sugar = ""

        var macros: Macros {
            Macros(caloriesKcal: Double(calories), proteinGrams: Double(protein),
                   carbsGrams: Double(carbs), fatGrams: Double(fat),
                   fiberGrams: Double(fiber), sugarGrams: Double(sugar))
        }
        init() {}
        init(_ m: Macros?) {
            calories = m?.caloriesKcal.map { NutritionFormat.wholeNumber($0) } ?? ""
            protein = m?.proteinGrams.map { NutritionFormat.wholeNumber($0) } ?? ""
            carbs = m?.carbsGrams.map { NutritionFormat.wholeNumber($0) } ?? ""
            fat = m?.fatGrams.map { NutritionFormat.wholeNumber($0) } ?? ""
            fiber = m?.fiberGrams.map { NutritionFormat.wholeNumber($0) } ?? ""
            sugar = m?.sugarGrams.map { NutritionFormat.wholeNumber($0) } ?? ""
        }
    }

    @State private var loading = true
    @State private var saving = false
    @State private var saved = false
    @State private var draft = Draft()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Daily targets")
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(NutritionTargetViewModel(DI.nutritionDayRepository))
        //     await vm.observe(vm.wrapped.state) { s in
        //         loading = s.loading; saving = s.saving; saved = s.saved
        //         if !s.saving { draft = Draft(s.target) }
        //     }
        // }
    }

    @ViewBuilder private var content: some View {
        if loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else {
            ScrollView {
                VStack(spacing: 16) {
                    SettingsCard(title: "Macros", description: "Your daily goal for each nutrient.") {
                        field("Calories", text: $draft.calories, unit: "kcal")
                        field("Protein", text: $draft.protein, unit: "g")
                        field("Carbs", text: $draft.carbs, unit: "g")
                        field("Fat", text: $draft.fat, unit: "g")
                        field("Fiber", text: $draft.fiber, unit: "g")
                        field("Sugar", text: $draft.sugar, unit: "g")
                    }
                    Button {
                        saving = true
                        // Post-0D: vm.wrapped.save(draft.macros)
                    } label: {
                        Text(saving ? "Saving…" : (saved ? "Saved ✓" : "Save"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(saving)
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
}
