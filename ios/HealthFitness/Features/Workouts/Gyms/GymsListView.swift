import SwiftUI
// import SharedCore  // GymsListViewModel, its UiState, Location — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D(iii) — the gyms list.
/// Parity target (Android): `feature-workouts/.../GymsListScreen.kt` +
/// `GymsListViewModel`. Observes the SHARED `GymsListViewModel` (offline-first:
/// seeds from the Room mirror, then revalidates). Routes to detail / new via the
/// shared `WorkoutsRoute` enum the integrator wires (see summary).
struct GymsListView: View {

    struct GymRow: Identifiable {
        let id: String            // locationId
        let name: String
        let subtitle: String
        let isDefault: Bool
    }

    enum ScreenState {
        case loading
        case ready([GymRow])
        case error(String)
    }

    @State private var state: ScreenState = .loading

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Gyms")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    // NavigationLink(value: WorkoutsRoute.newGym) { Image(systemName: "plus") }
                    Image(systemName: "plus")
                }
            }
        // Post-0D: .task { observe GymsListViewModel.state }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn't load gyms", systemImage: "dumbbell",
                                   description: Text(message))
        case .ready(let rows):
            if rows.isEmpty {
                ContentUnavailableView {
                    Label("No gyms yet", systemImage: "dumbbell")
                } description: {
                    Text("Add a gym so the designer knows what equipment you have.")
                }
            } else {
                List(rows) { gym in
                    // NavigationLink(value: WorkoutsRoute.gymDetail(locationId: gym.id)) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(gym.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                            Text(gym.subtitle).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                        }
                        Spacer()
                        if gym.isDefault {
                            Text("Default").font(.hfCapsSm).foregroundStyle(Theme.accent)
                                .padding(.horizontal, 8).padding(.vertical, 3)
                                .background(Theme.accentBg, in: Capsule())
                        }
                    }
                    // }
                }
                .formMaxWidth()
            }
        }
    }
}

#Preview {
    NavigationStack { GymsListView() }
}
