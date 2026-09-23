import SwiftUI
// import SharedCore  // WorkoutsHubViewModel, WorkoutsHubUiState — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — the workouts hub. Hosts the read-first
/// "This Week" landing (`WorkoutsLandingView`) and owns the ONE
/// `.navigationDestination(for: WorkoutsRoute.self)` for the whole workouts graph
/// (browse cases here; the live-session and designer/gyms agents extend
/// `WorkoutsRoute` + add their arms at the marked `#warning`-free comment slots).
///
/// Parity target (Android): the `workoutsGraph` starting at
/// `WorkoutsHubScreen`/`WorkoutsLandingScreen`. The hub is embedded in the tab's
/// `NavigationStack` (RootView.swift), so it pushes `WorkoutsRoute` values.
struct WorkoutsHubView: View {
    var body: some View {
        WorkoutsLandingView()
            .navigationTitle("Workouts")
            .navigationDestination(for: WorkoutsRoute.self) { route in
                switch route {
                case .landing:
                    WorkoutsLandingView()
                case .programs:
                    ProgramsListView()
                case .programDetail(let programId):
                    ProgramDetailView(programId: programId)
                case .workoutDetail(let programId, let phaseId, let dayId):
                    WorkoutDetailView(programId: programId, phaseId: phaseId, dayId: dayId)
                case .history:
                    WorkoutHistoryView()
                case .library:
                    WorkoutLibraryView()

                // Wave D-ii — live session (wired by integrator)
                case .session(let programId, let scheduledId):
                    WorkoutSessionView(programId: programId, scheduledId: scheduledId)

                // Wave D-iii — designer / progression / gyms (wired by integrator)
                case .designer:
                    WorkoutDesignerView()
                case .progressionConsole:
                    ProgressionConsoleView()
                case .gyms:
                    GymsListView()
                case .gymDetail(let locationId):
                    GymDetailView(locationId: locationId)
                case .newGym:
                    NewGymView()
                case .editGym(let locationId):
                    EditGymView(locationId: locationId)
                case .gymScan(let locationId):
                    GymScanView(locationId: locationId)
                }
            }
    }
}
