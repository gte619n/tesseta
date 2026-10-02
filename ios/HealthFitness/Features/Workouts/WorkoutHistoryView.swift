import SwiftUI
// import SharedCore  // WorkoutHistoryViewModel, WorkoutHistoryViewModel.State — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — read-only workout history (every COMPLETED
/// session, newest first). Parity target: Android `WorkoutHistoryScreen` +
/// `WorkoutHistoryViewModel` (ported to shared). Paged: the shared VM fetches the
/// first page and appends the next as the list scrolls (`loadMore` on the last
/// row's `onAppear`). ADR-0018: a cached first page shows instantly on re-entry.
/// Rows are grouped by program/phase using the resolved history titles.
struct WorkoutHistoryView: View {

    struct ScreenState {
        var loading = true
        var sessions: [HistoryRow] = []
        var loadingMore = false
        var hasMore = false
        var error: String?
    }

    struct HistoryRow: Identifiable {
        let id: String            // scheduledId
        let dateLabel: String     // "MON 9/22"
        let dayLabel: String
        let programTitle: String?
        let setsSummary: String?  // "12 sets · 48 min"
    }

    @State private var state = ScreenState()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("History")
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(WorkoutHistoryViewModel(
        //         repository: DI.workoutProgramRepository,
        //         sessionRepository: DI.workoutSessionRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView("Couldn’t load history", systemImage: "clock.arrow.circlepath",
                                   description: Text(error))
        } else if state.sessions.isEmpty {
            ContentUnavailableView("No completed workouts yet", systemImage: "clock.arrow.circlepath",
                                   description: Text("Finished sessions show up here."))
        } else {
            List {
                ForEach(state.sessions) { row in
                    VStack(alignment: .leading, spacing: 3) {
                        if let program = row.programTitle {
                            Text(program).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                        }
                        Text(row.dayLabel).font(.hfBodyMd)
                        Text(row.dateLabel).font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                        if let summary = row.setsSummary {
                            Text(summary).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                        }
                    }
                    .onAppear {
                        if row.id == state.sessions.last?.id && state.hasMore {
                            // vm.loadMore()  // Post-0D: append the next page.
                        }
                    }
                }
                if state.loadingMore {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
            }
            .formMaxWidth()
        }
    }

    // static func map(_ s: WorkoutHistoryViewModel.State) -> ScreenState { ... }  // Phase 0D
}
