import SwiftUI
import SharedCore

/// IMPL-IOS-01 — Settings › Drinks (IMPL-DRINK-01 phone-side drink MANAGEMENT).
/// Parity target (Android): `feature-settings/.../drinks/DrinkSettingsScreen.kt`
/// + the shared `DrinkSettingsViewModel`. List my drinks, add one at a time
/// (AI analyze → review → save), edit, regenerate the image, reorder (move up/
/// down), archive.
///
/// Backed by the SHARED `DrinkSettingsViewModel` over `HttpDrinkRepository`
/// (`api/me/drinks` + analyze / image-regenerate / order), observed via
/// `collectFlow` + a static `map(...)` to a local `ScreenState` — the same
/// SKIE-free pattern as the other wired settings screens. The shared VM owns the
/// PENDING-image poll + optimistic reorder; this view just renders + dispatches
/// intents. NOT the Drink-Mode session feature (which has no shared VM).
struct DrinkSettingsView: View {

    /// Local mirror of one row (shared `Food`, qualified `SharedCore.Food` since
    /// the bare name collides with the catalog `Food_`).
    struct DrinkRow: Identifiable {
        let id: String            // Food.foodId
        let name: String
        let servingSummary: String  // e.g. "1.4 std · 145 kcal"
        let imageStatus: String
        let imageUrl: String?
        let food: SharedCore.Food   // the shared element the VM intents take
    }

    /// Local mirror of the shared `DrinkSettingsViewModel.UiState`.
    struct ScreenState {
        var loading = true
        var drinks: [DrinkRow] = []
        var error: String?
        var message: String?
        var editorOpen = false
    }

    private let vm: DrinkSettingsViewModel
    @State private var state: ScreenState
    @State private var subscription: FlowSubscription?

    init() {
        let model = IosComposition.shared.drinkSettingsViewModel()
        self.vm = model
        _state = State(initialValue: Self.map(model.state.value as! DrinkSettingsViewModel.UiState))
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Drinks")
            .accessibilityIdentifier("settings-drinks")  // IMPL-E2E-01 shared id
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { vm.openAdd() } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: Binding(
                get: { state.editorOpen },
                set: { if !$0 { vm.closeEditor() } }
            )) {
                DrinkEditorSheet(vm: vm)
            }
            .overlay(alignment: .bottom) { toast }
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    guard let s = value as? DrinkSettingsViewModel.UiState else { return }
                    state = Self.map(s)
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading && state.drinks.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let errorMessage = state.error, state.drinks.isEmpty {
            ContentUnavailableView("Couldn’t load drinks", systemImage: "wineglass",
                                   description: Text(errorMessage))
        } else if state.drinks.isEmpty {
            ContentUnavailableView("No drinks yet", systemImage: "wineglass",
                                   description: Text("Tap + to add your first drink."))
        } else {
            List {
                ForEach(Array(state.drinks.enumerated()), id: \.element.id) { index, drink in
                    drinkRow(drink, index: index)
                }
            }
            .listStyle(.plain)
            .formMaxWidth()
        }
    }

    private func drinkRow(_ drink: DrinkRow, index: Int) -> some View {
        HStack(spacing: 12) {
            drinkThumbnail(drink)
            VStack(alignment: .leading, spacing: 2) {
                Text(drink.name).font(.hfBodyMd)
                Text(drink.servingSummary).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            }
            Spacer()
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { vm.archive(drink: drink.food) } label: {
                Label("Archive", systemImage: "archivebox")
            }
        }
        .contextMenu {
            Button { vm.openEdit(drink: drink.food) } label: { Label("Edit", systemImage: "pencil") }
            Button { vm.regenerateImage(drink: drink.food) } label: {
                Label("Regenerate image", systemImage: "arrow.clockwise")
            }
            if index > 0 {
                Button { vm.moveUp(drink: drink.food) } label: { Label("Move up", systemImage: "arrow.up") }
            }
            if index < state.drinks.count - 1 {
                Button { vm.moveDown(drink: drink.food) } label: {
                    Label("Move down", systemImage: "arrow.down")
                }
            }
        }
    }

    @ViewBuilder
    private func drinkThumbnail(_ drink: DrinkRow) -> some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(Theme.canvasMuted)
            if drink.imageStatus == "PENDING" {
                ProgressView()
            } else if let url = drink.imageUrl, let u = URL(string: url) {
                AsyncImage(url: u) { $0.resizable().scaledToFill() } placeholder: { ProgressView() }
            } else {
                Image(systemName: "wineglass").foregroundStyle(Theme.textTertiary)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    @ViewBuilder
    private var toast: some View {
        if let message = state.message {
            Text(message)
                .font(.hfBodySm)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Theme.textPrimary, in: Capsule())
                .foregroundStyle(Theme.textInverse)
                .padding(.bottom, 16)
                .task { try? await Task.sleep(for: .seconds(2)); vm.consumeMessage() }
        }
    }

    // MARK: Map shared UiState → local mirror

    static func map(_ s: DrinkSettingsViewModel.UiState) -> ScreenState {
        ScreenState(
            loading: s.loading,
            drinks: s.drinks.map { row($0) },
            error: s.error,
            message: s.message,
            editorOpen: s.editor != nil
        )
    }

    private static func row(_ food: SharedCore.Food) -> DrinkRow {
        DrinkRow(
            id: food.foodId,
            name: food.name,
            servingSummary: servingSummary(food),
            imageStatus: food.imageStatus,
            imageUrl: food.imageUrl,
            food: food
        )
    }

    /// "1.4 std · 145 kcal" — standard-drink count (from the alcohol block) plus
    /// per-serving calories (from `servingMacros`, which already folds in the
    /// alcohol calories). Either half is dropped when absent.
    private static func servingSummary(_ food: SharedCore.Food) -> String {
        var parts: [String] = []
        if let std = food.alcohol?.standardDrinks?.doubleValue {
            parts.append("\(DrinkFormat.trimmed(std)) std")
        }
        if let kcal = food.servingMacros?.caloriesKcal?.doubleValue {
            parts.append("\(DrinkFormat.trimmed(kcal)) kcal")
        }
        return parts.joined(separator: " · ")
    }
}

/// The add / edit form, bound to the shared `DrinkSettingsViewModel.EditorState`
/// via `vm.updateEditor { ... }` (the VM is the single source of truth; raw
/// strings so the user types freely, parsed on `save`). ABV% + serving volume are
/// required to save (`EditorState.canSave`); "Analyze with AI" fills the form from
/// a proposal, or drops to manual entry on a 422 (`analyzeUnavailable`).
struct DrinkEditorSheet: View {
    let vm: DrinkSettingsViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var editor: DrinkSettingsViewModel.EditorState?
    @State private var subscription: FlowSubscription?

    var body: some View {
        NavigationStack {
            Group {
                if let editor {
                    form(editor)
                } else {
                    ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .navigationTitle(editor?.drinkId == nil ? "Add drink" : "Edit drink")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { vm.closeEditor() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { vm.save() }
                        .disabled(!(editor?.canSave ?? false))
                }
            }
        }
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                guard let s = value as? DrinkSettingsViewModel.UiState else { return }
                editor = s.editor
                if s.editor == nil { dismiss() }
            }
        }
        .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private func form(_ e: DrinkSettingsViewModel.EditorState) -> some View {
        Form {
            Section("Drink") {
                TextField("Name (e.g. IPA, Old Fashioned)",
                          text: field(e.name) { withName($0, $1) })
                Button {
                    vm.analyze()
                } label: {
                    if e.analyzing { ProgressView() } else { Text("Analyze with AI") }
                }
                .disabled(e.name.isEmpty || e.analyzing)
                if e.analyzeUnavailable {
                    Text("AI is unavailable — enter the details manually.")
                        .font(.hfBodySm).foregroundStyle(Theme.warn)
                }
            }
            Section("Alcohol") {
                numberField("ABV %", text: field(e.abvPercent) { withAbv($0, $1) })
                numberField("Serving volume (ml)", text: field(e.servingVolumeMl) { withVolume($0, $1) })
                TextField("Serving label (optional)", text: field(e.servingLabel) { withLabel($0, $1) })
            }
            Section("Mixer macros (optional)") {
                numberField("Carbs (g)", text: field(e.carbsGrams) { withCarbs($0, $1) })
                numberField("Sugar (g)", text: field(e.sugarGrams) { withSugar($0, $1) })
            }
            if e.derivedStandardDrinks != nil || e.derivedCaloriesKcal != nil {
                Section("Per serving") {
                    derivedRow("Standard drinks", e.derivedStandardDrinks?.doubleValue)
                    derivedRow("Alcohol (g)", e.derivedAlcoholGrams?.doubleValue)
                    derivedRow("Calories", e.derivedCaloriesKcal?.doubleValue)
                }
            }
            if let error = e.error {
                Text(error).font(.hfBodySm).foregroundStyle(Theme.alert)
            }
        }
    }

    /// A String binding whose setter routes the new value through the shared VM's
    /// `updateEditor` transform (builds a fresh `EditorState` via `doCopy`), so the
    /// VM stays the single source of truth for the form.
    private func field(
        _ current: String,
        _ transform: @escaping (DrinkSettingsViewModel.EditorState, String) -> DrinkSettingsViewModel.EditorState
    ) -> Binding<String> {
        Binding(
            get: { current },
            set: { newValue in
                vm.updateEditor { e in transform(e, newValue) }
            }
        )
    }

    // Per-field copy helpers: set ONE editable string field on the shared
    // EditorState via `doCopy`, preserving everything else. (The other fields are
    // VM-managed — analyzing/saving/derived readouts — and left untouched here.)
    private typealias Editor = DrinkSettingsViewModel.EditorState

    private func withName(_ e: Editor, _ v: String) -> Editor {
        e.doCopy(drinkId: e.drinkId, name: v, abvPercent: e.abvPercent, servingVolumeMl: e.servingVolumeMl, servingLabel: e.servingLabel, carbsGrams: e.carbsGrams, sugarGrams: e.sugarGrams, analyzing: e.analyzing, saving: e.saving, analyzeUnavailable: e.analyzeUnavailable, error: e.error, derivedAlcoholGrams: e.derivedAlcoholGrams, derivedStandardDrinks: e.derivedStandardDrinks, derivedCaloriesKcal: e.derivedCaloriesKcal)
    }
    private func withAbv(_ e: Editor, _ v: String) -> Editor {
        e.doCopy(drinkId: e.drinkId, name: e.name, abvPercent: v, servingVolumeMl: e.servingVolumeMl, servingLabel: e.servingLabel, carbsGrams: e.carbsGrams, sugarGrams: e.sugarGrams, analyzing: e.analyzing, saving: e.saving, analyzeUnavailable: e.analyzeUnavailable, error: e.error, derivedAlcoholGrams: e.derivedAlcoholGrams, derivedStandardDrinks: e.derivedStandardDrinks, derivedCaloriesKcal: e.derivedCaloriesKcal)
    }
    private func withVolume(_ e: Editor, _ v: String) -> Editor {
        e.doCopy(drinkId: e.drinkId, name: e.name, abvPercent: e.abvPercent, servingVolumeMl: v, servingLabel: e.servingLabel, carbsGrams: e.carbsGrams, sugarGrams: e.sugarGrams, analyzing: e.analyzing, saving: e.saving, analyzeUnavailable: e.analyzeUnavailable, error: e.error, derivedAlcoholGrams: e.derivedAlcoholGrams, derivedStandardDrinks: e.derivedStandardDrinks, derivedCaloriesKcal: e.derivedCaloriesKcal)
    }
    private func withLabel(_ e: Editor, _ v: String) -> Editor {
        e.doCopy(drinkId: e.drinkId, name: e.name, abvPercent: e.abvPercent, servingVolumeMl: e.servingVolumeMl, servingLabel: v, carbsGrams: e.carbsGrams, sugarGrams: e.sugarGrams, analyzing: e.analyzing, saving: e.saving, analyzeUnavailable: e.analyzeUnavailable, error: e.error, derivedAlcoholGrams: e.derivedAlcoholGrams, derivedStandardDrinks: e.derivedStandardDrinks, derivedCaloriesKcal: e.derivedCaloriesKcal)
    }
    private func withCarbs(_ e: Editor, _ v: String) -> Editor {
        e.doCopy(drinkId: e.drinkId, name: e.name, abvPercent: e.abvPercent, servingVolumeMl: e.servingVolumeMl, servingLabel: e.servingLabel, carbsGrams: v, sugarGrams: e.sugarGrams, analyzing: e.analyzing, saving: e.saving, analyzeUnavailable: e.analyzeUnavailable, error: e.error, derivedAlcoholGrams: e.derivedAlcoholGrams, derivedStandardDrinks: e.derivedStandardDrinks, derivedCaloriesKcal: e.derivedCaloriesKcal)
    }
    private func withSugar(_ e: Editor, _ v: String) -> Editor {
        e.doCopy(drinkId: e.drinkId, name: e.name, abvPercent: e.abvPercent, servingVolumeMl: e.servingVolumeMl, servingLabel: e.servingLabel, carbsGrams: e.carbsGrams, sugarGrams: v, analyzing: e.analyzing, saving: e.saving, analyzeUnavailable: e.analyzeUnavailable, error: e.error, derivedAlcoholGrams: e.derivedAlcoholGrams, derivedStandardDrinks: e.derivedStandardDrinks, derivedCaloriesKcal: e.derivedCaloriesKcal)
    }

    private func numberField(_ label: String, text: Binding<String>) -> some View {
        TextField(label, text: text)
            .keyboardType(.decimalPad)
    }

    @ViewBuilder
    private func derivedRow(_ label: String, _ value: Double?) -> some View {
        if let value {
            HStack {
                Text(label).foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(DrinkFormat.trimmed(value)).font(.hfMonoSm)
            }
        }
    }
}

/// Small shared formatter — a Double without a trailing ".0" (mirror of the shared
/// Kotlin `trimNumber`).
enum DrinkFormat {
    static func trimmed(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(value)
    }
}
