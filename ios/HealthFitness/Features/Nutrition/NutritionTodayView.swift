import SwiftUI
import SharedCore

/// IMPL-IOS-01 (logging spine) — the nutrition day view, now bound to the SHARED
/// `NutritionTodayViewModel` over the real backend
/// (`HttpNutritionDayRepository` → GET/POST/PATCH/DELETE api/me/nutrition/…).
/// Parity target (Android): `NutritionTodayScreen` + `NutritionTodayViewModel`.
///
/// Follows the proven SKIE-free pattern (see `UnitsView`/`MedicationsListView`):
/// subscribe to the Kotlin `StateFlow` via `IosComposition.collectFlow` and fold
/// each emission through [map] into the local `ScreenState` mirror, so all the
/// existing dashlet subviews (which read the app-local mirror types) are
/// unchanged. Bare type names (`NutritionDay`, `Entry`, `Macros`, …) resolve to
/// the app-local structs; the SKIE-bridged Kotlin inputs are read as
/// `SharedCore.*` inside [map].
///
/// Wired this pass: networked read of the day, day navigation (prev/next), and
/// pull-to-refresh. Add-food, edit, delete, adjust, and leftover affordances reuse
/// the same repository + VM and land next.
struct NutritionTodayView: View {

    /// Local mirror of the shared `NutritionTodayUiState`, folded from each
    /// StateFlow emission by [map].
    struct ScreenState {
        var loading = true
        var date: String
        var day: NutritionDay?
        var error: String?
        var reviewingAdjustId: String?
        var reviewingLeftoverId: String?
        var adjustReviewBannerId: String?
        var editingEntry: Entry?
        var addSheetOpen = false
    }

    private let vm: NutritionTodayViewModel
    @State private var state: ScreenState
    @State private var subscription: FlowSubscription?

    init() {
        let today = Self.todayISO()
        self.vm = IosComposition.shared.nutritionTodayViewModel(initialDate: today)
        _state = State(initialValue: ScreenState(date: today))
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Nutrition")
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? NutritionTodayUiState { state = Self.merge(s, into: state) }
                }
                vm.refresh()   // VM doesn't self-load; kick the first fetch
            }
            .onDisappear { subscription?.cancel() }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: NutritionRoute.capture(date: state.date)) {
                        Image(systemName: "camera")
                    }
                }
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: NutritionRoute.target) { Image(systemName: "target") }
                }
            }
            .navigationDestination(for: NutritionRoute.self) { route in
                switch route {
                case .capture(let date): NutritionCaptureView(date: date)
                case .captureLeftover(let date, let entryId):
                    NutritionCaptureView(date: date, leftoverEntryId: entryId)
                case .target: NutritionTargetView()
                }
            }
            .sheet(item: Binding(get: { state.addSheetOpen ? AddSheetToken() : nil },
                                 set: { if $0 == nil { state.addSheetOpen = false } })) { _ in
                AddFoodView(meal: Meal.forHour(Calendar.current.component(.hour, from: Date())))
            }
            .sheet(item: $state.editingEntry) { entry in
                // A composite opens the ingredients editor; a single food the edit sheet.
                NavigationStack { EditEntryPlaceholder(entry: entry) }
            }
            .sheet(item: Binding(get: { adjustReviewEntry },
                                 set: { if $0 == nil { closeAdjustReview() } })) { entry in
                MealAdjustReviewView(date: state.date, entry: entry, adjustment: adjustmentFor(entry))
            }
            .sheet(item: Binding(get: { leftoverReviewEntry },
                                 set: { if $0 == nil { closeLeftoverReview() } })) { entry in
                LeftoverReviewView(date: state.date, entry: entry, leftover: leftoverFor(entry))
            }
    }

    @ViewBuilder
    private var content: some View {
        switch (state.loading, state.error, state.day) {
        case (true, _, nil):
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case (_, .some(let message), nil):
            ContentUnavailableView("Couldn’t load nutrition", systemImage: "fork.knife",
                                   description: Text(message))
        default:
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    daySwitcher
                    if let bannerId = state.adjustReviewBannerId, let entry = entry(bannerId) {
                        adjustBanner(entry)
                    }
                    MacroProgressHeader(totals: state.day?.totals ?? .empty,
                                        target: state.day?.target)
                    ForEach(state.day?.meals ?? []) { group in
                        MealSection(group: group,
                                    onTapEntry: { state.editingEntry = $0 },
                                    onReviewAdjust: { state.reviewingAdjustId = $0.entryId },
                                    onReviewLeftover: { state.reviewingLeftoverId = $0.entryId })
                    }
                    Button {
                        state.addSheetOpen = true
                    } label: {
                        Label("Add food", systemImage: "plus").font(.hfBodyMd)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
                }
                .padding()
                .formMaxWidth()
            }
            .refreshable { vm.onPullRefresh() }
        }
    }

    private var daySwitcher: some View {
        HStack {
            Button { vm.previousDay() } label: {
                Image(systemName: "chevron.left")
            }
            Spacer()
            Text(state.date).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            Spacer()
            Button { vm.nextDay() } label: {
                Image(systemName: "chevron.right")
            }
        }
        .foregroundStyle(Theme.textSecondary)
    }

    private func adjustBanner(_ entry: Entry) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Adjustment ready").font(.hfHeadingSm)
                Text(entry.foodName).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            }
            Spacer()
            Button("Review") { state.reviewingAdjustId = entry.entryId }
                .buttonStyle(.bordered)
            Button { state.adjustReviewBannerId = nil } label: { Image(systemName: "xmark") }
        }
        .padding(12)
        .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: sheet-token helpers (bridge the id-based shared sheet state to `item:`)

    private func entry(_ id: String) -> Entry? {
        state.day?.meals.flatMap { $0.entries }.first { $0.entryId == id }
    }
    private var adjustReviewEntry: Entry? { state.reviewingAdjustId.flatMap(entry) }
    private var leftoverReviewEntry: Entry? { state.reviewingLeftoverId.flatMap(entry) }
    private func adjustmentFor(_ entry: Entry) -> MealAdjustment? { nil }  // carried by SKIE state post-0D
    private func leftoverFor(_ entry: Entry) -> Leftover? { nil }
    private func closeAdjustReview() { state.reviewingAdjustId = nil }
    private func closeLeftoverReview() { state.reviewingLeftoverId = nil }

    // MARK: - Mapping (SKIE-bridged Kotlin state → local ScreenState mirror)
    //
    // Folds the networked VM state into the local mirror. Only the VM-owned fields
    // (loading/date/day/error) are taken from the emission; the sheet-presentation
    // ids (add/edit/review) stay local view state so a background re-emit never
    // dismisses an open sheet.

    static func merge(_ s: NutritionTodayUiState, into current: ScreenState) -> ScreenState {
        var next = current
        next.loading = s.loading
        next.date = s.date
        next.day = s.displayDay.map(mapDay)
        next.error = s.error
        return next
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

    private static func mapIngredient(_ i: SharedCore.EntryIngredient) -> EntryIngredient {
        EntryIngredient(
            name: i.name,
            foodId: i.foodId,
            servingLabel: i.servingLabel,
            servingGrams: i.servingGrams?.doubleValue,
            quantity: i.quantity?.doubleValue,
            macros: mapMacros(i.macros)
        )
    }

    private static func mapEntry(_ e: SharedCore.Entry) -> Entry {
        Entry(
            entryId: e.entryId,
            meal: e.meal,
            foodId: e.foodId,
            foodName: e.foodName,
            servingLabel: e.servingLabel,
            servingGrams: e.servingGrams?.doubleValue,
            quantity: e.quantity,
            macros: mapMacros(e.macros),
            source: e.source,
            imageUrl: e.imageUrl,
            imageStatus: e.imageStatus,
            analysisStatus: e.analysisStatus,
            ingredients: e.ingredients?.map(mapIngredient)
        )
    }

    private static func mapMealGroup(_ g: SharedCore.MealGroup) -> MealGroup {
        MealGroup(meal: g.meal, subtotal: mapMacros(g.subtotal), entries: g.entries.map(mapEntry))
    }

    private static func mapDay(_ d: SharedCore.NutritionDay) -> NutritionDay {
        NutritionDay(
            date: d.date,
            totals: mapMacros(d.totals),
            target: d.target.map(mapMacros),
            meals: d.meals.map(mapMealGroup)
        )
    }

    /// Today's date as `yyyy-MM-dd` in the device's local time zone (the day key
    /// the backend indexes nutrition days by).
    private static func todayISO() -> String {
        let f = DateFormatter()
        f.calendar = Calendar(identifier: .gregorian)
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return f.string(from: Date())
    }
}

private struct AddSheetToken: Identifiable { let id = "add" }

/// A composite/single edit sheet placeholder (the full EditEntry sheet is a
/// sibling Wave-C item; kept minimal here so the day view compiles).
private struct EditEntryPlaceholder: View {
    let entry: Entry
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        List {
            Section(entry.foodName) {
                ForEach(NutrientRow.allCases) { row in
                    HStack {
                        Text(row.rawValue)
                        Spacer()
                        Text(row.format(row.value(entry.macros)))
                            .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                    }
                }
            }
        }
        .navigationTitle("Edit")
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Done") { dismiss() } } }
    }
}

enum NutritionRoute: Hashable {
    case capture(date: String)
    case captureLeftover(date: String, entryId: String)   // D10 leftover mode
    case target
}
