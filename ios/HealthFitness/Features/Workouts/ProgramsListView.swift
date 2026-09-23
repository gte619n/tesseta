import SwiftUI
// import SharedCore  // ProgramsListViewModel, ProgramsListUiState, WorkoutProgram — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — the full programs list. Parity target: Android
/// `ProgramsListScreen` + `ProgramsListViewModel` (ported to shared). Reactive,
/// offline-first: renders instantly from the mirror and updates in place on sync;
/// pull-to-refresh re-subscribes. Rows push `WorkoutsRoute.programDetail`.
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

    @State private var state = ScreenState()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Programs")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    // "Design a program" — routes to the designer once the
                    // DESIGNER agent adds `WorkoutsRoute.designer`.
                    Button { } label: { Image(systemName: "plus") }
                        .disabled(true)
                        .accessibilityLabel("Design a program")
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(ProgramsListViewModel(repository: DI.workoutProgramRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
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
        }
    }

    // static func map(_ s: ProgramsListUiState) -> ScreenState { ... }  // Phase 0D
}
