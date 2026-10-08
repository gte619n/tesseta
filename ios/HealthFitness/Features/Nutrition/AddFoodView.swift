import SwiftUI
import SharedCore

/// IMPL-IOS-01 (logging spine) — the add-food / saved-meal-relog sheet, now bound
/// to the shared `AddFoodViewModel` (local-first search: cached hits instantly,
/// debounced network revalidation) over the real `api/foods/search` +
/// `api/me/nutrition/meals/search` + recent-meals endpoints.
///
/// Search/recents state is read straight off the SKIE-bridged `AddFoodUiState`
/// (SharedCore types, no local mirror — like `MedicationsListView`). The actual
/// logging goes through the SHARED Today VM (`todayVM`) the parent holds, so a
/// logged entry re-fetches and renders on the open day:
///   - recent → `relogRecent`, saved meal → `logSavedMeal`,
///   - catalog food → `ServingHintView` → `addCatalogEntry`,
///   - describe → `describeMealAsync`.
struct AddFoodView: View {

    let meal: Meal
    let todayVM: NutritionTodayViewModel

    @Environment(\.dismiss) private var dismiss
    private let vm: AddFoodViewModel
    @State private var ui: AddFoodUiState
    @State private var subscription: FlowSubscription?
    @State private var query = ""

    init(meal: Meal, todayVM: NutritionTodayViewModel) {
        self.meal = meal
        self.todayVM = todayVM
        let model = IosComposition.shared.addFoodViewModel(mealWire: meal.rawValue)
        self.vm = model
        _ui = State(initialValue: model.state.value as! AddFoodUiState)
    }

    /// Local meal enum → the shared Kotlin `Meal` the VM log-intents expect.
    private var sharedMeal: SharedCore.Meal {
        switch meal {
        case .breakfast: return .breakfast
        case .lunch: return .lunch
        case .dinner: return .dinner
        case .snack: return .snack
        }
    }

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    Section("Describe") { describeRow }
                    Section("Recent") {
                        if ui.recentsLoading && ui.recents.isEmpty { ProgressView() }
                        ForEach(ui.recents, id: \.entryId) { recentRow($0) }
                    }
                } else {
                    if !ui.mealResults.isEmpty {
                        Section("Saved meals") {
                            ForEach(ui.mealResults, id: \.mealId) { savedMealRow($0) }
                        }
                    }
                    Section("Foods") {
                        if ui.searching && ui.results.isEmpty { ProgressView() }
                        ForEach(ui.results, id: \.foodId) { foodRow($0) }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search foods & meals")
            .onChange(of: query) { _, newValue in vm.onQueryChange(query: newValue) }
            .navigationTitle("Add food")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .formMaxWidth()
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? AddFoodUiState { ui = s }
                }
            }
            .onDisappear { subscription?.cancel() }
        }
    }

    private var describeRow: some View {
        NavigationLink {
            DescribeMealView(meal: meal) { text in
                todayVM.describeMealAsync(meal: sharedMeal, description: text)
                dismiss()
            }
        } label: {
            Label("Describe a meal with AI", systemImage: "sparkles")
        }
    }

    private func recentRow(_ entry: SharedCore.Entry) -> some View {
        Button {
            todayVM.relogRecent(meal: sharedMeal, entry: entry)
            dismiss()
        } label: {
            HStack {
                Text(entry.foodName).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(NutritionFormat.kcal(entry.macros.caloriesKcal?.doubleValue))
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private func savedMealRow(_ m: SharedCore.MealSearchResult) -> some View {
        Button {
            todayVM.logSavedMeal(meal: sharedMeal, result: m)
            dismiss()
        } label: {
            HStack {
                Image(systemName: "photo").foregroundStyle(Theme.textTertiary)
                Text(m.name).font(.hfBodyMd)
                Spacer()
                Text(NutritionFormat.kcal(m.macros.caloriesKcal?.doubleValue))
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
        }
        .swipeActions {
            Button("Archive", role: .destructive) { vm.onArchiveMeal(mealId: m.mealId) }
        }
    }

    private func foodRow(_ food: SharedCore.Food_) -> some View {
        NavigationLink {
            ServingHintView(food: Self.mapFood(food), meal: meal) { servingIndex, quantity in
                todayVM.addCatalogEntry(meal: sharedMeal, food: food,
                                        servingIndex: Int32(servingIndex), quantity: quantity)
                dismiss()
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(food.name).font(.hfBodyMd)
                if let brand = food.brand {
                    Text(brand).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                }
            }
        }
        .swipeActions {
            Button("Delete", role: .destructive) { vm.onDeleteFood(foodId: food.foodId) }
        }
    }

    // MARK: - SharedCore.Food → local Food (for ServingHintView's local preview math)

    private static func mapFood(_ f: SharedCore.Food_) -> Food {
        Food(
            foodId: f.foodId,
            name: f.name,
            brand: f.brand,
            barcode: f.barcode,
            macrosPer100g: mapMacros(f.macrosPer100g),
            servingSizes: f.servingSizes.map { ServingSize(label: $0.label, grams: $0.grams) },
            defaultServingIndex: Int(f.defaultServingIndex),
            imageUrl: f.imageUrl
        )
    }

    private static func mapMacros(_ m: SharedCore.Macros) -> Macros {
        Macros(
            caloriesKcal: m.caloriesKcal?.doubleValue,
            proteinGrams: m.proteinGrams?.doubleValue,
            carbsGrams: m.carbsGrams?.doubleValue,
            fatGrams: m.fatGrams?.doubleValue,
            fiberGrams: m.fiberGrams?.doubleValue,
            sugarGrams: m.sugarGrams?.doubleValue
        )
    }
}

/// The free-text "describe a meal" input (fire-and-forget async describe).
struct DescribeMealView: View {
    let meal: Meal
    let onLog: (String) -> Void
    @State private var text = ""

    var body: some View {
        Form {
            Section("Describe what you ate") {
                TextField("e.g. two eggs, toast, and black coffee", text: $text, axis: .vertical)
            }
            Button("Log it") {
                onLog(text.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .navigationTitle("Describe meal")
        .navigationBarTitleDisplayMode(.inline)
    }
}
