import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D — a single workout day, read-only viewer, bound to
/// the SHARED `WorkoutDetailViewModel` (deep program read + per-exercise
/// prior-performance last-sets + "run this workout today"). Parity target: Android
/// `WorkoutDetailScreen` + `WorkoutDetailViewModel`.
///
/// "Run this workout today" materializes a session dated today via the VM; the
/// `startedScheduledId` one-shot then pushes `WorkoutsRoute.session` into the logger.
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

    /// One-shot nav target: set when the VM materializes today's session, consumed
    /// by a `navigationDestination(item:)` push into the logger.
    private struct StartedSession: Identifiable, Hashable {
        let programId: String
        let scheduledId: String
        var id: String { "\(programId)/\(scheduledId)" }
    }

    private let vm: WorkoutDetailViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?
    @State private var started: StartedSession?

    init(programId: String, phaseId: String, dayId: String) {
        self.programId = programId
        self.phaseId = phaseId
        self.dayId = dayId
        self.vm = IosComposition.shared.workoutDetailViewModel(
            programId: programId, phaseId: phaseId, dayId: dayId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.dayLabel.isEmpty ? "Workout" : state.dayLabel)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("workout-detail")
            .safeAreaInset(edge: .bottom) { startBar }
            .navigationDestination(item: $started) { s in
                WorkoutSessionView(programId: s.programId, scheduledId: s.scheduledId)
            }
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? WorkoutDetailUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
            .onChange(of: state.startedScheduledId) { _, scheduledId in
                guard let scheduledId else { return }
                started = StartedSession(programId: programId, scheduledId: scheduledId)
                vm.consumeStarted()
            }
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
            Button {
                vm.startToday()
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

    // MARK: - Mapping

    private static func map(_ s: WorkoutDetailUiState) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.error = s.error
        out.programTitle = s.programTitle
        out.phaseTitle = s.phaseTitle
        out.dayLabel = s.day?.label ?? ""
        out.starting = s.starting
        out.startedScheduledId = s.startedScheduledId
        if let day = s.day {
            out.blocks = day.blocks.map { block in
                BlockSection(
                    id: block.blockId,
                    title: block.title,
                    exercises: block.prescriptions.map { p in
                        mapExercise(p, prior: s.priorPerformance)
                    },
                )
            }
        }
        return out
    }

    private static func mapExercise(
        _ p: SharedCore.Prescription,
        prior: [String: [SharedCore.LoggedSet]],
    ) -> ExerciseRow {
        let exId = p.exercise?.exerciseId ?? p.exerciseId
        let name = p.exercise?.name ?? "Exercise"
        let priorSets = prior[exId]
        return ExerciseRow(
            id: exId,
            name: name,
            targetLine: targetLine(p),
            priorPerformance: priorSets.flatMap { WorkoutFormat.loggedSetsSummary($0.map(mapLoggedSet)) },
        )
    }

    /// Mirror of shared `prescriptionTargetLine`: "135 lb · 4 × 8 · rest 90s".
    private static func targetLine(_ p: SharedCore.Prescription) -> String {
        var parts: [String] = []
        if p.isTimed {
            if let secs = p.durationSeconds?.intValue { parts.append(WorkoutFormat.durationLabel(Int(secs))) }
        } else {
            if let lbs = p.targetWeightLbs?.doubleValue, lbs > 0 {
                parts.append("\(WorkoutFormat.trimNumber(lbs)) lb")
            } else if p.isBodyweight {
                parts.append("BW")
            }
            let sets = p.sets?.intValue
            let reps = fixedRepTarget(p)
            if let sets, let reps { parts.append("\(sets) × \(reps)") }
            else if let sets { parts.append("\(sets) sets") }
            else if let reps { parts.append("\(reps) reps") }
        }
        if let rest = p.restSeconds?.intValue { parts.append("rest \(restLabel(Int(rest)))") }
        return parts.joined(separator: " · ")
    }

    private static func fixedRepTarget(_ p: SharedCore.Prescription) -> Int? {
        let up = p.rationale?.direction == SharedCore.ProgressionDirection.up
        let min = p.repsMin?.intValue
        let max = p.repsMax?.intValue
        let chosen = up ? (min ?? max) : (max ?? min)
        return chosen.map { Int($0) }
    }

    private static func restLabel(_ seconds: Int) -> String {
        (seconds % 60 == 0 && seconds >= 60) ? "\(seconds / 60)m" : "\(seconds)s"
    }

    private static func mapLoggedSet(_ s: SharedCore.LoggedSet) -> WorkoutFormat.LoggedSet {
        WorkoutFormat.LoggedSet(
            weightLbs: s.weightLbs?.doubleValue,
            reps: (s.reps?.intValue).map { Int($0) },
            durationSeconds: (s.durationSeconds?.intValue).map { Int($0) },
        )
    }
}
