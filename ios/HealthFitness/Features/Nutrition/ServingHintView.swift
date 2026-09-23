import SwiftUI
// import SharedCore  // NutritionTodayViewModel.addCatalogEntry / servingHint, Food, Macros — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C — the serving picker + lazy "typical serving" hint
/// shown when logging a catalog food. Parity target (Android): `ServingPicker.kt`
/// + the lazy `servingHint` fetch in the edit sheet.
///
/// The live macro preview recomputes from `Food.macrosPer100g.forPortion(...)` on
/// every serving/quantity change — the SAME shared portion math the backend and
/// Android use (XPLAT single source), so the preview matches what gets logged.
struct ServingHintView: View {

    let food: Food
    let meal: Meal
    let onLogged: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var servingIndex = 0
    @State private var quantity = 1.0
    @State private var hint: String?

    private var serving: ServingSize? { food.servingSizes[safe: servingIndex] }
    private var previewMacros: Macros {
        food.macrosPer100g.forPortion(servingGrams: serving?.grams ?? 100, quantity: quantity)
    }

    var body: some View {
        Form {
            Section(food.name) {
                if !food.servingSizes.isEmpty {
                    Picker("Serving", selection: $servingIndex) {
                        ForEach(Array(food.servingSizes.enumerated()), id: \.offset) { i, s in
                            Text(s.label).tag(i)
                        }
                    }
                }
                Stepper("Quantity: \(quantity, specifier: "%.1f")×", value: $quantity, in: 0.5...10, step: 0.5)
                if let hint {
                    Text(hint).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                }
            }
            Section("This serving") {
                ForEach(NutrientRow.allCases) { row in
                    HStack {
                        Text(row.rawValue).font(.hfBodyMd)
                        Spacer()
                        Text(row.format(row.value(previewMacros)))
                            .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                    }
                }
            }
            Button("Log to \(meal.label)") {
                // Post-0D: todayVM.addCatalogEntry(meal, food, servingIndex, quantity)
                onLogged()
            }
        }
        .navigationTitle("Log food")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            servingIndex = min(food.defaultServingIndex, max(food.servingSizes.count - 1, 0))
            // Post-0D: hint = try? await todayVM.servingHint(entryId)
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
