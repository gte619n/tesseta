import SwiftUI
// import SharedCore  // GoalRoadmapViewModel, GoalRoadmapUiState, GoalDeep, Phase, Step — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals). Parity target: Android
/// `feature-goals/.../GoalRoadmapScreen` + `GoalRoadmapViewModel`. A vertical
/// timeline of phases, each with its steps; steps toggle done through the shared
/// VM's `toggleStep` intent (optimistic pending state disables the checkbox).
///
/// Observes the SHARED `GoalRoadmapViewModel`
/// (shared/.../presentation/goals/GoalRoadmapViewModel.kt) via the
/// `ObservableViewModel` bridge — same shape as the medications detail view.
struct GoalRoadmapView: View {

    let goalId: String

    // MARK: Local mirrors (deleted post-0D — the view reads the SKIE-bridged
    // `GoalRoadmapUiState`/`GoalDeep` directly).

    enum PhaseStatus { case locked, active, completed }

    struct StepRow: Identifiable, Hashable {
        let id: String
        let phaseId: String
        let title: String
        let done: Bool
        let manual: Bool     // MANUAL steps are user-checkable; metric steps auto-evaluate
        let regressed: Bool
    }

    struct PhaseRow: Identifiable, Hashable {
        let id: String
        let title: String
        let dateRange: String?
        let status: PhaseStatus
        let steps: [StepRow]
    }

    struct ScreenState {
        var loading: Bool = true
        var title: String = ""
        var summary: String = ""       // "Phase 2 of 4 · 7 of 19 steps"
        var phases: [PhaseRow] = []
        var pendingStepIds: Set<String> = []
        var error: String?
    }

    @State private var state = ScreenState()

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.title.isEmpty ? "Roadmap" : state.title)
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(GoalRoadmapViewModel(goalId: goalId, repo: DI.goalsRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error, state.phases.isEmpty {
            ContentUnavailableView("Couldn’t load goal", systemImage: "target",
                                   description: Text(error))
        } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if !state.summary.isEmpty {
                        Text(state.summary)
                            .font(.hfMonoSm)
                            .foregroundStyle(Theme.textSecondary)
                    }
                    ForEach(state.phases) { phase in
                        PhaseCard(
                            phase: phase,
                            pendingStepIds: state.pendingStepIds,
                            onToggle: { step in toggle(step) }
                        )
                    }
                }
                .padding()
                .formMaxWidth()
            }
        }
    }

    private func toggle(_ step: StepRow) {
        // Post-0D: vm.wrapped.toggleStep(phaseId: step.phaseId, stepId: step.id, done: !step.done)
        _ = step
    }

    // static func map(_ s: GoalRoadmapUiState) -> ScreenState { ... }  // Phase 0D
}

// MARK: - Phase card

private struct PhaseCard: View {
    let phase: GoalRoadmapView.PhaseRow
    let pendingStepIds: Set<String>
    let onToggle: (GoalRoadmapView.StepRow) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                statusDot
                VStack(alignment: .leading, spacing: 2) {
                    Text(phase.title)
                        .font(.hfHeadingSm)
                        .foregroundStyle(Theme.textPrimary)
                    if let range = phase.dateRange {
                        Text(range)
                            .font(.hfCapsSm)
                            .foregroundStyle(Theme.textTertiary)
                    }
                }
            }
            ForEach(phase.steps) { step in
                StepRowView(
                    step: step,
                    pending: pendingStepIds.contains(step.id),
                    locked: phase.status == .locked,
                    onToggle: { onToggle(step) }
                )
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .strokeBorder(Theme.borderDefault, lineWidth: 0.5)
        )
        .opacity(phase.status == .locked ? 0.55 : 1)
    }

    private var statusDot: some View {
        let color: Color = switch phase.status {
        case .completed: Theme.good
        case .active: Theme.accent
        case .locked: Theme.muted
        }
        return Image(systemName: phase.status == .completed ? "checkmark.circle.fill"
                     : phase.status == .locked ? "lock.fill" : "circle")
            .foregroundStyle(color)
            .font(.system(size: 16))
    }
}

private struct StepRowView: View {
    let step: GoalRoadmapView.StepRow
    let pending: Bool
    let locked: Bool
    let onToggle: () -> Void

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Button(action: onToggle) {
                Image(systemName: step.done ? "checkmark.square.fill" : "square")
                    .foregroundStyle(step.done ? Theme.accent : Theme.textTertiary)
            }
            .buttonStyle(.plain)
            .disabled(locked || pending || !step.manual)
            .opacity(pending ? 0.4 : 1)

            VStack(alignment: .leading, spacing: 2) {
                Text(step.title)
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textPrimary)
                    .strikethrough(step.done, color: Theme.textTertiary)
                if step.regressed {
                    Text("Metric regressed")
                        .font(.hfCapsSm)
                        .foregroundStyle(Theme.warn)
                } else if !step.manual {
                    Text("Auto-tracked")
                        .font(.hfCapsSm)
                        .foregroundStyle(Theme.textQuaternary)
                }
            }
            Spacer(minLength: 0)
        }
    }
}
