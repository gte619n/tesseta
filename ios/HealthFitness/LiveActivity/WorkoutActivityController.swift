import Foundation
import ActivityKit
// import SharedCore  // WorkoutSessionUiState, RestTimerState — once the XCFramework is built

/// IMPL-IOS-01 Phase 3 Wave D(ii) — starts / updates / ends the workout Live
/// Activity from the shared session VM's state (D10). The session view drives
/// this: it observes the shared `state` + `restTimer` flows and calls `sync` on
/// each meaningful change; the controller diffs and pushes a new content-state.
///
/// This is the iOS replacement for Android's `WorkoutSessionService` foreground
/// notification. Real ActivityKit — `Activity.request` / `.update` / `.end`.
///
/// The rest countdown in the content-state is a wall-clock `restEndsAt` derived
/// from the SAME shared `RestTimerState` the in-app overlay reads, so the two can
/// never diverge (the rest-timer-dual-state-gate lesson). We only push on
/// meaningful changes (exercise/set/rest transitions) — the widget self-ticks the
/// clock and the countdown via `Text(timerInterval:)`, so no per-second push.
@MainActor
final class WorkoutActivityController {

    static let shared = WorkoutActivityController()

    private var activity: Activity<WorkoutActivityAttributes>?
    /// Last pushed state, so we skip no-op updates (self-ticking handles time).
    private var lastState: WorkoutActivityAttributes.ContentState?

    /// A plain, framework-independent snapshot the session view builds from the
    /// shared VM state (keeps this controller compilable pre-XCFramework and makes
    /// the mapping unit-testable without ActivityKit).
    struct Snapshot: Equatable {
        var dayLabel: String
        var startedAt: Date
        var currentExercise: String
        var setProgress: String
        var completedSets: Int
        var totalSets: Int
        /// Seconds left on the single shared rest timer, or nil when not resting.
        var restRemainingSeconds: Int?
        var restIsGetReady: Bool
        var isComplete: Bool
    }

    /// Whether the user has enabled Live Activities for this app.
    var areActivitiesEnabled: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }

    /// Start (or, if one exists, update) the activity for a session snapshot.
    func start(_ snapshot: Snapshot) {
        guard areActivitiesEnabled else { return }
        if activity != nil { update(snapshot); return }

        let attributes = WorkoutActivityAttributes(
            dayLabel: snapshot.dayLabel,
            startedAt: snapshot.startedAt
        )
        let content = Self.contentState(from: snapshot)
        do {
            activity = try Activity.request(
                attributes: attributes,
                content: .init(state: content, staleDate: nil)
            )
            lastState = content
        } catch {
            // Live Activities can be off / over the system limit — degrade silently
            // (the in-app UI is the source of truth; the activity is a mirror).
            activity = nil
        }
    }

    /// Push a new content-state when the session materially changes.
    func update(_ snapshot: Snapshot) {
        guard let activity else { start(snapshot); return }
        let content = Self.contentState(from: snapshot)
        // Skip pushes that don't change anything the widget can't self-tick.
        guard content != lastState else { return }
        lastState = content
        Task {
            await activity.update(.init(state: content, staleDate: nil))
        }
    }

    /// End the activity (session finished / skipped / discarded).
    func end() {
        guard let activity else { return }
        let ending = activity
        self.activity = nil
        self.lastState = nil
        Task {
            await ending.end(nil, dismissalPolicy: .immediate)
        }
    }

    /// Pure mapping: session snapshot → ActivityKit content-state. The rest
    /// countdown becomes a wall-clock `restEndsAt` (now + remaining) so the widget
    /// counts it down itself. Exposed `static` + pure so the mapping is tested
    /// without requesting a real activity.
    static func contentState(
        from snapshot: Snapshot,
        now: Date = .now
    ) -> WorkoutActivityAttributes.ContentState {
        let restEndsAt: Date? = snapshot.restRemainingSeconds
            .flatMap { $0 > 0 ? now.addingTimeInterval(TimeInterval($0)) : nil }
        return WorkoutActivityAttributes.ContentState(
            currentExercise: snapshot.currentExercise,
            setProgress: snapshot.setProgress,
            completedSets: snapshot.completedSets,
            totalSets: snapshot.totalSets,
            restEndsAt: restEndsAt,
            restIsGetReady: snapshot.restIsGetReady,
            isComplete: snapshot.isComplete
        )
    }
}
