import SwiftUI
// import SharedCore  // WorkoutDetailViewModel, WorkoutDetailUiState, WorkoutDay — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D — a single workout day, read-only viewer. Parity
/// target: Android `WorkoutDetailScreen` + `WorkoutDetailViewModel` (ported to
/// shared, keyed by programId/phaseId/dayId). Renders the day's blocks and
/// per-exercise prescriptions, and the Wave-D **prior performance** hint ("last
/// time you did…") from the shared VM's `priorPerformance` last-sets read.
///
/// "Run this workout today" pushes toward the live-session route once the
/// LIVE-SESSION agent adds `WorkoutsRoute.session` — this view surfaces the
/// `startedScheduledId` one-shot the shared VM emits after materializing.
struct WorkoutDetailView: View {

    let programId: String
    let phaseId: String
    let dayId: String

    struct ScreenState {
        var loading = true
        var programTitle: String?
        var phaseTitle: String?
        var dayLabel = ""
        var blocks: [BlockSection] = []
        var starting = false
        var startedScheduledId: String?
        var error: String?
    }

    struct BlockSection: Identifiable {
        let id: String       // blockId
        let title: String
        let exercises: [ExerciseRow]
    }

    struct ExerciseRow: Identifiable {
        let id: String       // exerciseId
        let name: String
        let targetLine: String            // "135 lb · 4 × 8 · rest 90s"
        let priorPerformance: String?     // "last time: 135 lb × 8 · 135 lb × 7"
    }

    @State private var state = ScreenState()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.dayLabel.isEmpty ? "Workout" : state.dayLabel)
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .bottom) { startBar }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(WorkoutDetailViewModel(
        //         repository: DI.workoutProgramRepository,
        //         programId: programId, phaseId: phaseId, dayId: dayId))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView("Couldn’t load workout", systemImage: "dumbbell",
                                   description: Text(error))
        } else {
            List {
                if let phase = state.phaseTitle {
                    Text(phase).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                        .listRowSeparator(.hidden)
                }
                ForEach(state.blocks) { block in
                    Section(block.title) {
                        ForEach(block.exercises) { ex in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(ex.name).font(.hfBodyMd)
                                Text(ex.targetLine).font(.hfMonoSm)
                                    .foregroundStyle(Theme.textSecondary)
                                if let prior = ex.priorPerformance {
                                    Text("Last time: \(prior)").font(.hfBodySm)
                                        .foregroundStyle(Theme.textTertiary)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    }
                }
            }
            .formMaxWidth()
        }
    }

    @ViewBuilder
    private var startBar: some View {
        if !state.loading && state.error == nil {
            // "Run this workout today" — materializes a session and (once the
            // LIVE-SESSION agent wires `WorkoutsRoute.session`) opens the logger.
            Button {
                // vm.startToday()  // Post-0D; navigation consumes startedScheduledId.
            } label: {
                Text(state.starting ? "Starting…" : "Run this workout today")
                    .font(.hfBodyMd)
                    .frame(maxWidth: .infinity)
                    .padding()
            }
            .background(Theme.accent)
            .foregroundStyle(Theme.textInverse)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .padding()
            .disabled(state.starting)
        }
    }

    // static func map(_ s: WorkoutDetailUiState) -> ScreenState { ... }  // Phase 0D
    // (map() builds each ExerciseRow.priorPerformance via WorkoutFormat.loggedSetsSummary,
    //  mirroring the shared ProgramFormat.loggedSetsSummary — see WorkoutFormatTests.)
}
