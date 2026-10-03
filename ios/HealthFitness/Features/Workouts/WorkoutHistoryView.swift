import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D — read-only workout history (every COMPLETED
/// session, newest first), bound to the SHARED `WorkoutHistoryViewModel`
/// (`GET api/me/workout-history`, paged). Parity target: Android
/// `WorkoutHistoryScreen` + `WorkoutHistoryViewModel`.
///
/// Paged: the shared VM fetches the first page and appends the next as the list
/// scrolls (`loadMore()` on the last row's `onAppear`). ADR-0018: a cached first
/// page shows instantly on re-entry. Rows carry the resolved program/phase titles.
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

    private let vm: WorkoutHistoryViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.workoutHistoryViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("History")
            .accessibilityIdentifier("workout-history")
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? WorkoutHistoryViewModel.State { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
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
                            vm.loadMore()
                        }
                    }
                }
                if state.loadingMore {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
            }
            .formMaxWidth()
            .refreshable { vm.load() }
        }
    }

    // MARK: - Mapping

    private static func map(_ s: WorkoutHistoryViewModel.State) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.error = s.error
        out.loadingMore = s.loadingMore
        out.hasMore = s.hasMore
        out.sessions = s.sessions.map(mapRow)
        return out
    }

    private static func mapRow(_ sw: SharedCore.ScheduledWorkout) -> HistoryRow {
        HistoryRow(
            id: sw.scheduledId,
            dateLabel: WorkoutFormat.dateLabel(localDateToDate(sw.date)),
            dayLabel: sw.dayLabel,
            programTitle: sw.programTitle,
            setsSummary: setsSummary(sw),
        )
    }

    /// "12 sets · 48 min" from the completed session's logged sets + duration.
    private static func setsSummary(_ sw: SharedCore.ScheduledWorkout) -> String? {
        var parts: [String] = []
        if let session = sw.session {
            let setCount = session.blocks.reduce(0) { acc, block in
                acc + block.prescriptions.reduce(0) { $0 + $1.loggedSets.count }
            }
            if setCount > 0 { parts.append(setCount == 1 ? "1 set" : "\(setCount) sets") }
        }
        if let secs = sw.durationSeconds?.intValue, secs > 0 {
            parts.append(WorkoutFormat.durationLabel(Int(secs)))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private static func localDateToDate(_ d: Kotlinx_datetimeLocalDate) -> Date {
        Date(timeIntervalSince1970: Double(d.toEpochDays()) * 86_400)
    }
}
