import Testing
import Foundation
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave D(ii) — the Live Activity content-state mapping and
/// the rest-timer state mapping. Both are PURE (no ActivityKit request, no
/// SharedCore), so they run today. They pin the two seams where the session state
/// crosses into the widget:
///   1. `WorkoutActivityController.contentState(from:)` — snapshot → ContentState,
///      including the rest-remaining → wall-clock `restEndsAt` conversion.
///   2. `WorkoutSessionView.RestState` — the single-source rest state's derived
///      flags the overlay + beep read (`isFinished`).
@Suite("Workout Live Activity + rest-timer mapping")
struct WorkoutLiveActivityTests {

    // MARK: Live Activity content-state mapping

    @Test("resting snapshot → restEndsAt is now + remaining, isResting true")
    func restingMapsToEndDate() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let snapshot = WorkoutActivityController.Snapshot(
            dayLabel: "Lower A",
            startedAt: now.addingTimeInterval(-600),
            currentExercise: "Back Squat",
            setProgress: "Set 2 of 3",
            completedSets: 4,
            totalSets: 12,
            restRemainingSeconds: 90,
            restIsGetReady: false,
            isComplete: false
        )
        let state = WorkoutActivityController.contentState(from: snapshot, now: now)

        #expect(state.currentExercise == "Back Squat")
        #expect(state.setProgress == "Set 2 of 3")
        #expect(state.isResting == true)
        #expect(state.restIsGetReady == false)
        #expect(state.restEndsAt == now.addingTimeInterval(90))
        #expect(abs(state.progress - (4.0 / 12.0)) < 0.0001)
        #expect(state.isComplete == false)
    }

    @Test("no rest → restEndsAt nil, isResting false (widget shows elapsed)")
    func noRestMapsToNilEndDate() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let snapshot = WorkoutActivityController.Snapshot(
            dayLabel: "Lower A", startedAt: now,
            currentExercise: "Back Squat", setProgress: "Set 1 of 3",
            completedSets: 0, totalSets: 12,
            restRemainingSeconds: nil, restIsGetReady: false, isComplete: false
        )
        let state = WorkoutActivityController.contentState(from: snapshot, now: now)
        #expect(state.restEndsAt == nil)
        #expect(state.isResting == false)
        #expect(state.progress == 0)
    }

    @Test("zero remaining is treated as no rest (avoids a stuck 0:00 countdown)")
    func zeroRemainingIsNotResting() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let snapshot = WorkoutActivityController.Snapshot(
            dayLabel: "Lower A", startedAt: now,
            currentExercise: "Squat", setProgress: "", completedSets: 12, totalSets: 12,
            restRemainingSeconds: 0, restIsGetReady: false, isComplete: true
        )
        let state = WorkoutActivityController.contentState(from: snapshot, now: now)
        #expect(state.restEndsAt == nil)
        #expect(state.isResting == false)
        #expect(state.isComplete == true)
        #expect(state.progress == 1.0)
    }

    @Test("get-ready pre-roll carries its flag through to the content-state")
    func getReadyFlagPropagates() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let snapshot = WorkoutActivityController.Snapshot(
            dayLabel: "Mobility", startedAt: now,
            currentExercise: "Couch Stretch", setProgress: "Hold 1 of 2",
            completedSets: 0, totalSets: 2,
            restRemainingSeconds: 10, restIsGetReady: true, isComplete: false
        )
        let state = WorkoutActivityController.contentState(from: snapshot, now: now)
        #expect(state.restIsGetReady == true)
        #expect(state.restEndsAt == now.addingTimeInterval(10))
    }

    // MARK: Rest-timer state mapping (the single source's derived flags)

    @Test("rest state is finished exactly at zero (drives the one-shot beep)")
    func restStateFinishedAtZero() {
        let running = WorkoutSessionView.RestState(
            totalSeconds: 90, remainingSeconds: 3, isGetReady: false, isPaused: false)
        #expect(running.isFinished == false)

        let finished = WorkoutSessionView.RestState(
            totalSeconds: 90, remainingSeconds: 0, isGetReady: false, isPaused: false)
        #expect(finished.isFinished == true)
    }

    @Test("RestState equatability gates redundant Live Activity pushes")
    func restStateEquatable() {
        let a = WorkoutSessionView.RestState(
            totalSeconds: 90, remainingSeconds: 45, isGetReady: false, isPaused: false)
        let b = WorkoutSessionView.RestState(
            totalSeconds: 90, remainingSeconds: 45, isGetReady: false, isPaused: false)
        let c = WorkoutSessionView.RestState(
            totalSeconds: 90, remainingSeconds: 44, isGetReady: false, isPaused: false)
        #expect(a == b)
        #expect(a != c)
    }
}
