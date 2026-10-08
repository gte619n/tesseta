import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D(iii) — a single gym's detail.
/// Parity target (Android): `feature-workouts/.../GymDetailScreen.kt` +
/// `GymDetailViewModel`. Observes the SHARED `GymDetailViewModel`. Shows the
/// gym's cover / hours / amenities, its attached equipment (each removable), and
/// the actions: set-default, edit, scan-to-import equipment, delete.
struct GymDetailView: View {
    let locationId: String

    struct EquipmentRow: Identifiable { let id: String; let name: String; let category: String? }

    struct Gym {
        let name: String
        let address: String?
        let coverPhotoUrl: String?
        let hoursSummary: String
        let amenities: [String]
        let isDefault: Bool
    }

    enum ScreenState {
        case loading
        case ready(gym: Gym, equipment: [EquipmentRow])
        case error(String)
    }

    private let vm: GymDetailViewModel
    @State private var state: ScreenState = .loading
    @State private var showDeleteConfirm = false
    @State private var subscription: FlowSubscription?
    @Environment(\.dismiss) private var dismiss

    init(locationId: String) {
        self.locationId = locationId
        self.vm = IosComposition.shared.gymDetailViewModel(locationId: locationId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Gym")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: WorkoutsRoute.editGym(locationId: locationId)) { Text("Edit") }
                }
            }
            .onAppear {
                vm.refresh()
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? GymDetailViewModel.UiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    private static func map(_ s: GymDetailViewModel.UiState) -> ScreenState {
        if let loc = s.location {
            let gym = Gym(
                name: loc.name,
                address: loc.address,
                coverPhotoUrl: loc.coverPhotoUrl,
                hoursSummary: hoursSummary(loc),
                amenities: loc.amenities.map { $0.label },
                isDefault: loc.isDefault
            )
            let equipment = s.equipment.map { EquipmentRow(id: $0.equipmentId, name: $0.name, category: $0.category) }
            return .ready(gym: gym, equipment: equipment)
        }
        if s.loading { return .loading }
        if let error = s.error { return .error(error) }
        return .loading
    }

    private static func hoursSummary(_ loc: Location) -> String {
        if loc.is24Hours { return "Open 24 hours" }
        let open = loc.hours?.count ?? 0
        switch open {
        case 0: return "Hours not set"
        case 7: return "Open every day"
        default: return "Open \(open) day\(open == 1 ? "" : "s") a week"
        }
    }

    @ViewBuilder
    private var content: some View {
        switch state {
        case .loading:
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        case .error(let message):
            ContentUnavailableView("Couldn't load gym", systemImage: "dumbbell",
                                   description: Text(message))
        case .ready(let gym, let equipment):
            ScrollView {
                VStack(spacing: 16) {
                    SettingsCard(title: gym.name, description: gym.address) {
                        keyValue("Hours", gym.hoursSummary)
                        if gym.isDefault {
                            Text("Default gym").font(.hfBodySm).foregroundStyle(Theme.accent)
                        } else {
                            Button("Set as default") { vm.setDefault() }
                                .buttonStyle(.bordered).tint(Theme.accent)
                        }
                        if !gym.amenities.isEmpty {
                            FlowChips(items: gym.amenities)
                        }
                    }

                    SettingsCard(title: "Equipment",
                                 description: "What the designer can program around.") {
                        NavigationLink(value: WorkoutsRoute.gymScan(locationId: locationId)) {
                            Label("Scan to add equipment", systemImage: "camera.viewfinder")
                                .font(.hfBodyMd).foregroundStyle(Theme.accent)
                        }
                        if equipment.isEmpty {
                            Text("No equipment yet. Scan a walkthrough video to import it.")
                                .font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                        } else {
                            ForEach(equipment) { item in
                                HStack {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                                        if let category = item.category {
                                            Text(category).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                                        }
                                    }
                                    Spacer()
                                    Button { vm.removeEquipment(equipmentId: item.id) } label: {
                                        Image(systemName: "minus.circle").foregroundStyle(Theme.alert)
                                    }
                                    .buttonStyle(.plain)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }

                    Button(role: .destructive) { showDeleteConfirm = true } label: {
                        Text("Delete gym").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .confirmationDialog("Delete this gym?", isPresented: $showDeleteConfirm) {
                        Button("Delete", role: .destructive) { vm.delete { dismiss() } }
                    }
                }
                .padding()
                .formMaxWidth()
            }
        }
    }

    private func keyValue(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            Spacer()
            Text(value).font(.hfBodySm).foregroundStyle(Theme.textPrimary)
        }
    }
}

/// Wrapping amenity chips.
struct FlowChips: View {
    let items: [String]
    var body: some View {
        // A simple wrapping layout via a flexible grid of leading-aligned chips.
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 90), alignment: .leading)], alignment: .leading, spacing: 6) {
            ForEach(items, id: \.self) { item in
                Text(item).font(.hfCapsSm).foregroundStyle(Theme.textSecondary)
                    .padding(.horizontal, 8).padding(.vertical, 4)
                    .background(Theme.canvasMuted, in: Capsule())
            }
        }
    }
}

#Preview {
    NavigationStack { GymDetailView(locationId: "gym-1") }
}
