import Foundation

/// IMPL-IOS-01 Phase 3 Wave D — the SHARED navigation route enum for the whole
/// workouts vertical, mirroring the Android `feature-workouts/.../nav/WorkoutsRoutes.kt`
/// string routes as a type-safe SwiftUI `NavigationStack` path.
///
/// Wave D is split across three sibling agents. This enum is the collision-free
/// SSOT: THIS agent (hub/programs/history/library reading) owns the browse cases;
/// the LIVE-SESSION agent and the DESIGNER/GYMS agent add their own cases at the
/// marked extension points. Keep additions in the same enum so a single
/// `.navigationDestination(for: WorkoutsRoute.self)` covers the whole graph.
///
/// Parity note: the hub is embedded in the tab's `NavigationStack` (see
/// RootView.swift), so views push `WorkoutsRoute` values rather than owning their
/// own stacks — matching Android's single `workoutsGraph`.
enum WorkoutsRoute: Hashable {

    // MARK: Browse — THIS agent (Wave D-i)

    /// Read-first "This Week" landing (the workouts hub → featured program +
    /// compliance grid + streak). Rendered by `WorkoutsLandingView`.
    case landing

    /// The full programs list. → `ProgramsListView`.
    case programs

    /// One program's detail (deep tree, this-week strip, activate/edit). → `ProgramDetailView`.
    case programDetail(programId: String)

    /// A single workout day within a phase (read-only viewer + prior performance).
    /// phaseId disambiguates because dayId is unique only within its phase's
    /// weekly microcycle. → `WorkoutDetailView`.
    case workoutDetail(programId: String, phaseId: String, dayId: String)

    /// Read-only workout history (every COMPLETED session). → `WorkoutHistoryView`.
    case history

    /// Ad-hoc workout library (IMPL-ADHOC-01). → `WorkoutLibraryView`.
    case library

    // MARK: Live session — Wave D-ii (case wired by integrator).
    /// The active-session logger, keyed by (programId, scheduledId) so start and
    /// resume are the same destination. → `WorkoutSessionView`.
    case session(programId: String, scheduledId: String)

    // MARK: Designer / gyms / progression — Wave D-iii (cases wired by integrator).
    case designer(programId: String?)            // → WorkoutDesignerView
    case progressionConsole                       // → ProgressionConsoleView
    case gyms                                      // → GymsListView
    case gymDetail(locationId: String)             // → GymDetailView
    case newGym                                    // → NewGymView
    case editGym(locationId: String)               // → EditGymView
    case gymScan(locationId: String)               // → GymScanView
}
