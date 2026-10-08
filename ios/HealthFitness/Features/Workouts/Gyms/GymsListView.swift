import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D(iii) — the gyms list.
/// Parity target (Android): `feature-workouts/.../GymsListScreen.kt` +
/// `GymsListViewModel`. Observes the SHARED `GymsListViewModel` (online-first:
/// network read fills the list; mirror seed deferred). Routes to detail / new via
/// the `WorkoutsRoute` enum.
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

    private let vm: GymsListViewModel
    @State private var state: ScreenState = .loading
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.gymsListViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Gyms")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: WorkoutsRoute.newGym) { Image(systemName: "plus") }
                }
            }
            .onAppear {
                vm.refresh()
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? GymsListViewModel.UiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    private static func map(_ s: GymsListViewModel.UiState) -> ScreenState {
        if s.loading { return .loading }
        if let error = s.error { return .error(error) }
        let rows = s.locations.map { loc -> GymRow in
            GymRow(
                id: loc.locationId,
                name: loc.name,
                subtitle: loc.address ?? (loc.is24Hours ? "Open 24 hours" : "Gym"),
                isDefault: loc.isDefault
            )
        }
        return .ready(rows)
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
                    NavigationLink(value: WorkoutsRoute.gymDetail(locationId: gym.id)) {
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
                    }
                }
                .formMaxWidth()
            }
        }
    }
}

#Preview {
    NavigationStack { GymsListView() }
}
