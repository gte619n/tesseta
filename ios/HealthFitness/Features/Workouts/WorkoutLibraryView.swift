import SwiftUI
import SharedCore

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

    private let vm: WorkoutLibraryViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.workoutLibraryViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Library")
            .accessibilityIdentifier("workout-library")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    guard let s = value as? WorkoutLibraryUiState else { return }
                    state = Self.map(s)
                }
            }
            .onDisappear { subscription?.cancel() }
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

    // MARK: Map shared UiState → local mirror

    static func map(_ s: WorkoutLibraryUiState) -> ScreenState {
        ScreenState(
            loading: s.loading,
            items: s.items.map { item in
                LibraryRow(
                    id: item.id,
                    title: item.title,
                    summary: item.summary,
                    // The shallow list carries no exercise count (D7); fall back to the
                    // purpose/summary hint when present, else a neutral label.
                    exerciseCountLabel: item.exerciseCount > 0
                        ? "\(item.exerciseCount) exercises"
                        : (item.purpose ?? "Ad-hoc workout")
                )
            }
        )
    }
}
