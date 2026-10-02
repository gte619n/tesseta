import SwiftUI
// import SharedCore  // NutritionTodayViewModel, NutritionTodayUiState, NutritionDay, … — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C — the nutrition day view (replaces the Wave-C
/// stub). Parity target (Android): `NutritionTodayScreen` + `NutritionTodayViewModel`.
/// Observes the SHARED `NutritionTodayViewModel`
/// (shared/.../presentation/nutrition/NutritionTodayViewModel.kt) through the
/// `ObservableViewModel` bridge — the view is a pure function of the shared
/// UI state; all mutation/adjust/leftover logic lives on the shared VM.
///
/// The screen: a day switcher, the macro-progress header (calories ring + the six
/// nutrient bars off `NutrientRow`), meal-grouped entry rows (including the
/// synthetic "logging…" rows the op-rail projects), and the capture/add
/// affordances. The adjust-review, leftover-review, edit, and add sheets are
/// driven off the shared VM's sheet-state ids.
struct NutritionTodayView: View {

    /// Local mirror of the shared `NutritionTodayUiState` (deleted post-0D, when
    /// the view binds the SKIE-bridged state directly).
    struct ScreenState {
        var loading = true
        var date = "2026-09-23"
        var day: NutritionDay?
        var error: String?
        var reviewingAdjustId: String?
        var reviewingLeftoverId: String?
        var adjustReviewBannerId: String?
        var editingEntry: Entry?
        var addSheetOpen = false
    }

    @State private var state = ScreenState()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Nutrition")
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
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(NutritionTodayViewModel(
        //         repository: DI.nutritionDayRepository, ops: DI.nutritionOpQueue,
        //         initialDate: state.date))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
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
        }
    }

    private var daySwitcher: some View {
        HStack {
            Button { /* Post-0D: vm.wrapped.previousDay() */ } label: {
                Image(systemName: "chevron.left")
            }
            Spacer()
            Text(state.date).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            Spacer()
            Button { /* Post-0D: vm.wrapped.nextDay() */ } label: {
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
    private func closeAdjustReview() { state.reviewingAdjustId = nil }      // Post-0D: vm.wrapped.closeAdjustReview()
    private func closeLeftoverReview() { state.reviewingLeftoverId = nil }

    // static func map(_ s: NutritionTodayUiState) -> ScreenState { ... }  // Phase 0D
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
