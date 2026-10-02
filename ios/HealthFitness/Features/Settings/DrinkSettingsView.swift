import SwiftUI
// import SharedCore  // DrinkSettingsViewModel, DrinkSettingsViewModel.UiState,
//                       DrinkSettingsViewModel.EditorState, Food — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave A2 — Settings › Drinks (IMPL-DRINK-01 phone-side
/// drink management). Parity target (Android):
/// `feature-settings/.../drinks/DrinkSettingsScreen.kt` + the shared
/// `DrinkSettingsViewModel`. List my drinks, add one at a time (AI analyze →
/// review → save), edit, regenerate the image, reorder (move up/down), archive.
/// The shared VM polls while any image is PENDING so a generated glass photo
/// resolves in place; the view just renders its UiState.
struct DrinkSettingsView: View {

    struct DrinkRow: Identifiable {
        let id: String            // Food.foodId
        let name: String
        let servingSummary: String  // e.g. "1.4 std · 145 kcal"
        let imageStatus: String     // "READY" | "PENDING" | "NONE" | …
        let imageUrl: String?
    }

    @State private var drinks: [DrinkRow] = []
    @State private var loading = true
    @State private var errorMessage: String?
    @State private var message: String?        // transient toast
    @State private var editorPresented = false // editor != nil

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Drinks")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        // vm.wrapped.openAdd(); editorPresented = true
                        editorPresented = true
                    } label: { Image(systemName: "plus") }
                }
            }
            .sheet(isPresented: $editorPresented) {
                DrinkEditorSheet()   // binds vm.wrapped.editor / analyze / save
            }
            .overlay(alignment: .bottom) { toast }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(DrinkSettingsViewModel(repo: DI.drinkRepository))
        //     await vm.observe(vm.wrapped.state) { self.apply($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if loading && drinks.isEmpty {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let errorMessage, drinks.isEmpty {
            ContentUnavailableView("Couldn’t load drinks", systemImage: "wineglass",
                                   description: Text(errorMessage))
        } else if drinks.isEmpty {
            ContentUnavailableView("No drinks yet", systemImage: "wineglass",
                                   description: Text("Tap + to add your first drink."))
        } else {
            List {
                ForEach(Array(drinks.enumerated()), id: \.element.id) { index, drink in
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
            Button(role: .destructive) { /* vm.wrapped.archive(drink) */ } label: {
                Label("Archive", systemImage: "archivebox")
            }
        }
        .contextMenu {
            Button { /* vm.wrapped.openEdit(drink) */ } label: { Label("Edit", systemImage: "pencil") }
            Button { /* vm.wrapped.regenerateImage(drink) */ } label: {
                Label("Regenerate image", systemImage: "arrow.clockwise")
            }
            if index > 0 {
                Button { /* vm.wrapped.moveUp(drink) */ } label: { Label("Move up", systemImage: "arrow.up") }
            }
            if index < drinks.count - 1 {
                Button { /* vm.wrapped.moveDown(drink) */ } label: {
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
        if let message {
            Text(message)
                .font(.hfBodySm)
                .padding(.horizontal, 14).padding(.vertical, 8)
                .background(Theme.textPrimary, in: Capsule())
                .foregroundStyle(Theme.textInverse)
                .padding(.bottom, 16)
                .task { try? await Task.sleep(for: .seconds(2)); /* vm.wrapped.consumeMessage() */ }
        }
    }
}

/// The add / edit form (DrinkSettingsViewModel.EditorState). ABV% + serving
/// volume are required to save (`EditorState.canSave`); "Analyze with AI" fills
/// the form from a proposal, or drops to manual entry on a 422 (analyzeUnavailable).
struct DrinkEditorSheet: View {
    @Environment(\.dismiss) private var dismiss

    // Mirror of EditorState (raw strings so the user types freely; the shared VM
    // parses on save).
    @State private var name = ""
    @State private var abvPercent = ""
    @State private var servingVolumeMl = ""
    @State private var servingLabel = ""
    @State private var carbsGrams = ""
    @State private var sugarGrams = ""
    @State private var analyzing = false
    @State private var saving = false
    @State private var analyzeUnavailable = false
    @State private var error: String?
    // Read-only derived readouts from the last analyze/edit.
    @State private var derivedAlcoholGrams: Double?
    @State private var derivedStandardDrinks: Double?
    @State private var derivedCaloriesKcal: Double?

    private var canSave: Bool {
        !saving && !name.isEmpty
            && Double(abvPercent) != nil && Double(servingVolumeMl) != nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Drink") {
                    TextField("Name (e.g. IPA, Old Fashioned)", text: $name)
                    Button {
                        // vm.wrapped.analyze()
                    } label: {
                        if analyzing { ProgressView() } else { Text("Analyze with AI") }
                    }
                    .disabled(name.isEmpty || analyzing)
                    if analyzeUnavailable {
                        Text("AI is unavailable — enter the details manually.")
                            .font(.hfBodySm).foregroundStyle(Theme.warn)
                    }
                }
                Section("Alcohol") {
                    numberField("ABV %", text: $abvPercent)
                    numberField("Serving volume (ml)", text: $servingVolumeMl)
                    TextField("Serving label (optional)", text: $servingLabel)
                }
                Section("Mixer macros (optional)") {
                    numberField("Carbs (g)", text: $carbsGrams)
                    numberField("Sugar (g)", text: $sugarGrams)
                }
                if derivedStandardDrinks != nil || derivedCaloriesKcal != nil {
                    Section("Per serving") {
                        derivedRow("Standard drinks", derivedStandardDrinks)
                        derivedRow("Alcohol (g)", derivedAlcoholGrams)
                        derivedRow("Calories", derivedCaloriesKcal)
                    }
                }
                if let error {
                    Text(error).font(.hfBodySm).foregroundStyle(Theme.alert)
                }
            }
            .navigationTitle("Add drink")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { /* vm.wrapped.closeEditor() */ dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { /* vm.wrapped.save() */ }
                        .disabled(!canSave)
                }
            }
        }
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
                Text(trimmedNumber(value)).font(.hfMonoSm)
            }
        }
    }

    /// Format a Double without a trailing ".0" (mirror of the shared `trimNumber`).
    private func trimmedNumber(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(value)
    }
}
