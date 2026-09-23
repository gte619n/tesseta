import SwiftUI

/// Workouts hub (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): the workouts hub + programs / calendar / detail /
/// history / library (WorkoutsRoutes.HUB and its graph), plus the live session,
/// designer chat, progression console and gyms CRUD in later sub-waves (Wave D).
/// Shared ViewModel (Phase 1D): WorkoutsHubViewModel (+ WorkoutSessionViewModel
/// for the live session).
struct WorkoutsHubView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Programs, calendar, live session land in Wave D.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Workouts")
    }
}
