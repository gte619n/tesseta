import SwiftUI
// import SharedCore  // AddFoodViewModel, AddFoodUiState, Food, MealSearchResult, Entry — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C — the add-food / saved-meal-relog sheet. Parity
/// target (Android): `AddFoodSheet` + `AddFoodViewModel`. Backed by the SHARED
/// `AddFoodViewModel` (local-first search: cached hits instantly, debounced
/// network revalidation).
///
/// Empty query → the one-tap "recent meals" list (re-logs via the Today VM).
/// Typing → a "Saved meals" group above catalog-food results, plus a "quick add"
/// and a "describe with AI" affordance.
struct AddFoodView: View {

    let meal: Meal

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var searching = false
    @State private var results: [Food] = []
    @State private var mealResults: [MealSearchResult] = []
    @State private var recents: [Entry] = []
    @State private var recentsLoading = true

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    Section("Describe") { describeRow }
                    Section("Recent") {
                        if recentsLoading { ProgressView() }
                        ForEach(recents) { entry in
                            recentRow(entry)
                        }
                    }
                } else {
                    if !mealResults.isEmpty {
                        Section("Saved meals") {
                            ForEach(mealResults) { m in savedMealRow(m) }
                        }
                    }
                    Section("Foods") {
                        if searching && results.isEmpty { ProgressView() }
                        ForEach(results) { food in foodRow(food) }
                    }
                }
            }
            .searchable(text: $query, prompt: "Search foods & meals")
            .onChange(of: query) { _, _ in /* Post-0D: vm.wrapped.onQueryChange(query) */ }
            .navigationTitle("Add food")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
            .formMaxWidth()
        }
    }

    private var describeRow: some View {
        NavigationLink {
            DescribeMealView(meal: meal) { dismiss() }
        } label: {
            Label("Describe a meal with AI", systemImage: "sparkles")
        }
    }

    private func recentRow(_ entry: Entry) -> some View {
        Button {
            // Post-0D: todayVM.relogRecent(meal, entry)
            dismiss()
        } label: {
            HStack {
                Text(entry.foodName).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                Spacer()
                Text(NutritionFormat.kcal(entry.macros.caloriesKcal))
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private func savedMealRow(_ m: MealSearchResult) -> some View {
        Button {
            // Post-0D: todayVM.logSavedMeal(meal, m)
            dismiss()
        } label: {
            HStack {
                Image(systemName: "photo").foregroundStyle(Theme.textTertiary)
                Text(m.name).font(.hfBodyMd)
                Spacer()
                Text(NutritionFormat.kcal(m.macros.caloriesKcal))
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
        }
        .swipeActions {
            Button("Archive", role: .destructive) { /* vm.wrapped.onArchiveMeal(m.mealId) */ }
        }
    }

    private func foodRow(_ food: Food) -> some View {
        NavigationLink {
            ServingHintView(food: food, meal: meal) { dismiss() }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(food.name).font(.hfBodyMd)
                if let brand = food.brand {
                    Text(brand).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                }
            }
        }
        .swipeActions {
            Button("Delete", role: .destructive) { /* vm.wrapped.onDeleteFood(food.foodId) */ }
        }
    }
}

/// The free-text "describe a meal" input (fire-and-forget async describe).
struct DescribeMealView: View {
    let meal: Meal
    let onDone: () -> Void
    @State private var text = ""

    var body: some View {
        Form {
            Section("Describe what you ate") {
                TextField("e.g. two eggs, toast, and black coffee", text: $text, axis: .vertical)
            }
            Button("Log it") {
                // Post-0D: todayVM.describeMealAsync(meal, text)
                onDone()
            }
            .disabled(text.trimmingCharacters(in: .whitespaces).isEmpty)
        }
        .navigationTitle("Describe meal")
        .navigationBarTitleDisplayMode(.inline)
    }
}
