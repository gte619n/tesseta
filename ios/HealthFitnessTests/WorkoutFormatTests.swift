import Testing
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave D — parity tests for the LOCAL mirror of the shared
/// `presentation/workouts/ProgramFormat.kt` set/rep/weight/duration formatters
/// (`WorkoutFormat`). Plain Swift (no SharedCore dependency), so this runs today.
/// These outputs MUST match the shared Kotlin `loggedSetsSummary` /
/// `loggedSetLabel` / `durationLabel` exactly — the whole point of the mirror is
/// that it renders identically until the XCFramework replaces it. Any drift here
/// or in the Kotlin SSOT fails a test on one side.
@Suite("Workout formatting")
struct WorkoutFormatTests {

    @Test("trimNumber drops a trailing .0 but keeps real fractions")
    func trimNumber() {
        #expect(WorkoutFormat.trimNumber(135) == "135")
        #expect(WorkoutFormat.trimNumber(135.0) == "135")
        #expect(WorkoutFormat.trimNumber(2.5) == "2.5")
    }

    @Test("durationLabel: whole minutes, mixed, and sub-minute")
    func durationLabel() {
        #expect(WorkoutFormat.durationLabel(60) == "1 min")
        #expect(WorkoutFormat.durationLabel(120) == "2 min")
        #expect(WorkoutFormat.durationLabel(90) == "1m 30s")
        #expect(WorkoutFormat.durationLabel(45) == "45s")
    }

    @Test("loggedSetLabel: loaded, bodyweight, reps-only, timed, empty")
    func loggedSetLabel() {
        #expect(WorkoutFormat.loggedSetLabel(.init(weightLbs: 135, reps: 8)) == "135 lb × 8")
        #expect(WorkoutFormat.loggedSetLabel(.init(weightLbs: 0, reps: 12)) == "BW × 12")
        #expect(WorkoutFormat.loggedSetLabel(.init(weightLbs: nil, reps: 10)) == "10 reps")
        #expect(WorkoutFormat.loggedSetLabel(.init(weightLbs: nil, reps: nil, durationSeconds: 60)) == "1 min")
        #expect(WorkoutFormat.loggedSetLabel(.init(weightLbs: nil, reps: nil)) == "—")
    }

    @Test("loggedSetsSummary collapses consecutive identical sets with (×N)")
    func loggedSetsSummary() {
        // Matches the shared ProgramFormat.loggedSetsSummary example exactly.
        let sets: [WorkoutFormat.LoggedSet] = [
            .init(weightLbs: 135, reps: 8),
            .init(weightLbs: 135, reps: 8),
            .init(weightLbs: 135, reps: 7),
        ]
        #expect(WorkoutFormat.loggedSetsSummary(sets) == "135 lb × 8 (×2) · 135 lb × 7")
    }

    @Test("loggedSetsSummary is nil for a plan-only prescription")
    func emptySummary() {
        #expect(WorkoutFormat.loggedSetsSummary([]) == nil)
    }
}
