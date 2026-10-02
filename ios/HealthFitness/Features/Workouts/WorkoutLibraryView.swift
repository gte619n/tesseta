import SwiftUI
// import SharedCore  // WorkoutLibraryViewModel, WorkoutLibraryUiState, AdHocLibraryItem — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — the ad-hoc workout library (IMPL-ADHOC-01).
/// Parity target: Android `WorkoutLibraryScreen` + `WorkoutLibraryViewModel`
/// (ported to shared). Reactive, offline-first: renders instantly from the
/// `adhocWorkouts` mirror and updates in place as the background sync lands
/// web-generated workouts. Read-only for now (generate/run-offline land in a
/// later wave); archived items are filtered out by the shared VM.
struct WorkoutLibraryView: View {

    struct ScreenState {
        var loading = true
        var items: [LibraryRow] = []
    }

    struct LibraryRow: Identifiable {
        let id: String
        let title: String
        let summary: String?
        let exerciseCountLabel: String  // "5 exercises"
    }

    @State private var state = ScreenState()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Library")
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(WorkoutLibraryViewModel(repository: DI.adHocLibraryRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if state.items.isEmpty {
            ContentUnavailableView("Your library is empty", systemImage: "books.vertical",
                                   description: Text("Purpose-built workouts you generate show up here."))
        } else {
            List(state.items) { item in
                VStack(alignment: .leading, spacing: 3) {
                    Text(item.title).font(.hfBodyMd)
                    if let summary = item.summary {
                        Text(summary).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                    }
                    Text(item.exerciseCountLabel).font(.hfMonoSm).foregroundStyle(Theme.textTertiary)
                }
            }
            .formMaxWidth()
        }
    }

    // static func map(_ s: WorkoutLibraryUiState) -> ScreenState { ... }  // Phase 0D
}
