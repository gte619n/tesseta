import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D — the full programs list, now bound to the SHARED
/// `ProgramsListViewModel` over the real backend (`GET api/me/workout-programs`).
/// Parity target: Android `ProgramsListScreen` + `ProgramsListViewModel`.
///
/// Follows the proven SKIE-free pattern (see `BloodOverviewView`): subscribe to the
/// Kotlin `StateFlow` via `IosComposition.collectFlow` and fold each emission
/// through [map] into the local `ScreenState` mirror. Rows push
/// `WorkoutsRoute.programDetail`; pull-to-refresh re-subscribes.
struct ProgramsListView: View {

    struct ScreenState {
        var loading = true
        var programs: [ProgramRow] = []
        var error: String?
    }

    struct ProgramRow: Identifiable {
        let id: String        // programId
        let title: String
        let subtitle: String  // status · "Mon · Wed · Fri" training days
        let phaseProgress: String? // "2 / 4 phases"
    }

    private let vm: ProgramsListViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?

    init() {
        self.vm = IosComposition.shared.programsListViewModel()
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Programs")
            .accessibilityIdentifier("programs-list")
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? ProgramsListUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    // "Design a program" — routes to the designer once the
                    // DESIGNER agent wires `WorkoutsRoute.designer`.
                    Button { } label: { Image(systemName: "plus") }
                        .disabled(true)
                        .accessibilityLabel("Design a program")
                }
            }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView("Couldn’t load programs", systemImage: "list.bullet.rectangle",
                                   description: Text(error))
        } else if state.programs.isEmpty {
            ContentUnavailableView("No programs yet", systemImage: "list.bullet.rectangle",
                                   description: Text("Designed and AI-generated programs show up here."))
        } else {
            List(state.programs) { program in
                NavigationLink(value: WorkoutsRoute.programDetail(programId: program.id)) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text(program.title).font(.hfBodyMd)
                        Text(program.subtitle).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                        if let progress = program.phaseProgress {
                            Text(progress).font(.hfMonoSm).foregroundStyle(Theme.textTertiary)
                        }
                    }
                }
            }
            .formMaxWidth()
            .refreshable { vm.refresh() }
        }
    }

    // MARK: - Mapping (SKIE-free: Kotlin state → local ScreenState mirror)

    private static func map(_ s: ProgramsListUiState) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.error = s.error
        out.programs = s.programs.map(mapProgram)
        return out
    }

    private static func mapProgram(_ p: SharedCore.WorkoutProgram) -> ProgramRow {
        let status = WorkoutFormat.statusLabel(p.status.name)
        let days = WorkoutFormat.trainingDaysSummary(p.trainingDays.map { $0.name })
        let subtitle = days.isEmpty ? status : "\(status) · \(days)"
        // phaseProgress is a Kotlin Pair<Int,Int>; read first/second.
        let completed = (p.phaseProgress.first as? KotlinInt)?.intValue ?? 0
        let total = (p.phaseProgress.second as? KotlinInt)?.intValue ?? 0
        let progress: String? = total > 0 ? "\(completed) / \(total) phases" : nil
        return ProgramRow(id: p.programId, title: p.title, subtitle: subtitle, phaseProgress: progress)
    }
}
