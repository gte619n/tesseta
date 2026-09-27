import SwiftUI
// import SharedCore  // MedicationsViewModel, MedicationsUiState, Medication — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave B — REFERENCE feature view (the worked example every
/// Phase 3 vertical replicates). Parity target: Android
/// `feature-medical/.../list/MedicationsScreen.kt` + `MedicationsViewModel`.
/// Observes the SHARED `MedicationsViewModel`
/// (shared/.../presentation/medications/MedicationsViewModel.kt) through the
/// `ObservableViewModel` bridge — the view is a pure function of the shared
/// UI state, no business logic duplicated on iOS.
///
/// The pattern:
///   1. `@State var vm = ObservableViewModel(SharedVM(repo))`
///   2. a local `@State` mirror of the shared sealed UiState (replaced by the
///      SKIE-bridged enum once the XCFramework is built)
///   3. `.task { await vm.observe(vm.wrapped.state) { state = map($0) } }`
///   4. `switch` on the state → Loading / Ready / Error
struct MedicationsListView: View {

    /// Local mirror of the shared `MedicationsUiState`. Post-0D this is deleted
    /// and the view switches directly on the SKIE-bridged `MedicationsUiState`
    /// (SKIE renders a Kotlin sealed interface as a Swift enum).
    enum ScreenState {
        case loading
        case ready(active: [MedicationRow], discontinued: [MedicationRow])
        case error(String)
    }

    struct MedicationRow: Identifiable {
        let id: String
        let name: String
        let doseSummary: String
    }

    @State private var state: ScreenState = .loading
    @State private var tab: Tab = .current

    enum Tab: String, CaseIterable { case current = "Current", history = "History" }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Medications")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: MedicationsRoute.add) { Image(systemName: "plus") }
                        .accessibilityIdentifier("meds-add-button")  // IMPL-E2E-01 shared id
                }
            }
            .navigationDestination(for: MedicationsRoute.self) { route in
                switch route {
                case .add: AddMedicationView()                       // Wave B
                case .detail(let id): MedicationDetailView(medicationId: id) // Wave B
                case .reminderSettings: ReminderSettingsView()       // Wave B
                case .todaysDoses: TodaysDosesView()                 // Wave B (D9 deep-link target)
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(MedicationsViewModel(repo: DI.medicationRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn’t load medications", systemImage: "pills",
                                   description: Text(message))
        case .ready(let active, let discontinued):
            List {
                Picker("View", selection: $tab) {
                    ForEach(Tab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowSeparator(.hidden)

                let rows = tab == .current ? active : discontinued
                Section(tab == .current ? "Current" : "History") {
                    ForEach(rows) { med in
                        NavigationLink(value: MedicationsRoute.detail(med.id)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(med.name).font(.hfBodyMd)
                                Text(med.doseSummary).font(.hfBodySm)
                                    .foregroundStyle(Theme.textSecondary)
                            }
                        }
                    }
                }
            }
            .formMaxWidth()   // 600pt cap — iPad parity with Android's 600dp form width
            .accessibilityIdentifier("meds-list")  // IMPL-E2E-01 shared id
        }
    }

    // static func map(_ s: MedicationsUiState) -> ScreenState { ... }  // Phase 0D
}

enum MedicationsRoute: Hashable {
    case add
    case detail(String)
    case reminderSettings         // Wave B — ReminderSettingsView
    case todaysDoses              // Wave B — TodaysDosesView (D9 dose-checklist deep-link target)
}
