import ActivityKit
import Foundation

/// IMPL-IOS-01 Phase 3 Wave D(ii) — the ActivityKit attributes for the active
/// workout Live Activity (D10: replaces Android's `WorkoutSessionService`
/// foreground-service chronometer notification).
///
/// STATIC attributes never change for the life of the activity (the day label,
/// the wall-clock the session started at). The `ContentState` is the LIVE,
/// pushed-on-every-update payload the lock-screen + Dynamic Island render.
///
/// The content-state is a PURE PROJECTION of the shared
/// `WorkoutSessionViewModel` state + its single rest-timer source — the
/// `WorkoutActivityController` maps VM state → this struct and pushes it. Because
/// the rest countdown reads the ONE shared timer source, the Live Activity can
/// never disagree with the in-app overlay (the rest-timer-dual-state-gate lesson).
///
/// Timekeeping note: we ship `elapsedStart` / `restEndsAt` as `Date`s and let
/// SwiftUI's `Text(timerInterval:)` self-tick in the widget process, so the
/// activity counts smoothly WITHOUT a push every second — pushes happen only on
/// meaningful state changes (exercise advanced, rest started/skipped, finished).
struct WorkoutActivityAttributes: ActivityAttributes {

    public struct ContentState: Codable, Hashable {
        /// The exercise currently in focus (e.g. "Back Squat").
        var currentExercise: String
        /// "Set 2 of 3" style progress, or a phase label ("Warm-up").
        var setProgress: String
        /// Overall sets done / total across the whole session (for the ring).
        var completedSets: Int
        var totalSets: Int

        /// When a rest countdown is running, the wall-clock instant it ends — the
        /// widget renders `Text(timerInterval:)` down to this so it self-ticks.
        /// Nil when no rest is active (then the widget shows elapsed instead).
        var restEndsAt: Date?
        /// REST vs GET_READY, so the widget can label the countdown correctly.
        var restIsGetReady: Bool

        /// True once every prescribed set is logged (drives the "Finish" glyph).
        var isComplete: Bool

        /// Fraction 0…1 of prescribed sets completed, for the progress ring.
        var progress: Double {
            guard totalSets > 0 else { return 0 }
            return min(1, Double(completedSets) / Double(totalSets))
        }

        /// True while a rest countdown is active (vs. showing elapsed time).
        var isResting: Bool { restEndsAt != nil }
    }

    /// Day label from the session snapshot ("Lower A"), fixed for the activity.
    var dayLabel: String
    /// When the workout started — the widget counts elapsed up from here.
    var startedAt: Date
}
