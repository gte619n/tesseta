import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals) — networked shared screen. Parity target:
/// Android `feature-goals/.../GoalRoadmapScreen` + `GoalRoadmapViewModel`. A
/// vertical timeline of phases, each with its steps; MANUAL steps toggle done
/// through the shared VM's `toggleStep` intent (optimistic pending disables the
/// checkbox), and overridden metric steps expose a "Reset to auto" affordance
/// routing to `resetStepToAuto`.
///
/// Backed by the SHARED `GoalRoadmapViewModel`
/// (shared/.../presentation/goals/GoalRoadmapViewModel.kt) over the mirror-backed
/// `MirrorGoalsRepository`, observed via `collectFlow` + a static `map(...)` to
/// the local mirror structs — the same SKIE-free pattern as `GoalsListView`.
struct GoalRoadmapView: View {

    let goalId: String

    // MARK: Local mirrors

    enum PhaseStatus { case locked, active, completed }

    struct StepRow: Identifiable, Hashable {
        let id: String
        let phaseId: String
        let title: String
        let done: Bool
        let manual: Bool     // MANUAL steps are user-checkable; metric steps auto-evaluate
        let regressed: Bool
        let metricReadout: String?   // "restingHr < 60 for 30d" etc, for bound steps
        let canResetToAuto: Bool     // overridden metric step → offer "Reset to auto"
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

    private let vm: GoalRoadmapViewModel
    @State private var state: ScreenState
    @State private var subscription: FlowSubscription?

    init(goalId: String) {
        self.goalId = goalId
        let model = IosComposition.shared.goalRoadmapViewModel(goalId: goalId)
        self.vm = model
        _state = State(initialValue: Self.map(model.state.value as! GoalRoadmapUiState))
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.title.isEmpty ? "Roadmap" : state.title)
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("goal-roadmap")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? GoalRoadmapUiState { state = Self.map(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading && state.phases.isEmpty {
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
                            onToggle: { step in toggle(step) },
                            onReset: { step in reset(step) }
                        )
                    }
                }
                .padding()
                .formMaxWidth()
            }
        }
    }

    private func toggle(_ step: StepRow) {
        vm.toggleStep(phaseId: step.phaseId, stepId: step.id, done: !step.done)
    }

    private func reset(_ step: StepRow) {
        vm.resetStepToAuto(phaseId: step.phaseId, stepId: step.id)
    }

    // MARK: Map shared UiState → local mirror

    static func map(_ s: GoalRoadmapUiState) -> ScreenState {
        guard let goal = s.goal else {
            return ScreenState(
                loading: s.loading,
                title: "",
                summary: "",
                phases: [],
                pendingStepIds: pendingIds(s),
                error: s.error
            )
        }

        let orderedPhases = goal.phases.sorted { $0.orderIndex < $1.orderIndex }
        let phaseRows: [PhaseRow] = orderedPhases.map { phase in
            let steps = phase.steps.sorted { $0.orderIndex < $1.orderIndex }.map { step in
                mapStep(step, phase: phase)
            }
            return PhaseRow(
                id: phase.phaseId,
                title: phase.title,
                dateRange: dateRange(phase.targetStartDate, phase.targetEndDate),
                status: mapStatus(phase.status),
                steps: steps
            )
        }

        return ScreenState(
            loading: s.loading,
            title: goal.title,
            summary: summary(orderedPhases),
            phases: phaseRows,
            pendingStepIds: pendingIds(s),
            error: s.error
        )
    }

    private static func pendingIds(_ s: GoalRoadmapUiState) -> Set<String> {
        Set(s.pendingStepIds.compactMap { $0 as? String })
    }

    private static func mapStatus(_ status: SharedCore.PhaseStatus) -> PhaseStatus {
        if status == SharedCore.PhaseStatus.completed { return .completed }
        if status == SharedCore.PhaseStatus.active { return .active }
        return .locked
    }

    private static func mapStep(_ step: SharedCore.Step, phase: SharedCore.Phase) -> StepRow {
        let isManual = step.kind == SharedCore.StepKind.manual
        let regressed = step.metricRegressed?.boolValue ?? false
        return StepRow(
            id: step.stepId,
            phaseId: phase.phaseId,
            title: step.title,
            done: step.done,
            manual: isManual,
            regressed: regressed,
            metricReadout: metricReadout(step),
            canResetToAuto: step.manualOverride
                && !isManual
                && phase.status != SharedCore.PhaseStatus.locked
        )
    }

    // "restingHr < 60 for 30d" — mirrors Android's StepRow metric readout.
    private static func metricReadout(_ step: SharedCore.Step) -> String? {
        guard let m = step.metric else { return nil }
        let base = "\(m.metricKey) \(m.comparator.symbol) \(formatTarget(m.targetValue))"
        if step.kind == SharedCore.StepKind.sustained, let w = m.windowDays?.intValue {
            return base + " for \(w)d"
        }
        return base
    }

    private static func formatTarget(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(value)
    }

    // "Phase 2 of 4 · 7 of 19 steps" — parity with Android GoalProgress.summary.
    private static func summary(_ phases: [SharedCore.Phase]) -> String {
        let total = phases.count
        let activeIndex = phases.firstIndex { $0.status == SharedCore.PhaseStatus.active }
        let completed = phases.filter { $0.status == SharedCore.PhaseStatus.completed }.count
        let allSteps = phases.flatMap { $0.steps }
        let doneSteps = allSteps.filter { $0.done }.count

        let phasePart: String
        if let idx = activeIndex {
            phasePart = "Phase \(idx + 1) of \(total)"
        } else if completed == total && total > 0 {
            phasePart = "All \(total) phases complete"
        } else {
            phasePart = "\(total) phases"
        }
        return "\(phasePart) · \(doneSteps) of \(allSteps.count) steps"
    }

    // "MAY 28 → JUL 12" range, or nil if neither date parses.
    private static func dateRange(_ start: String?, _ end: String?) -> String? {
        let s = capsDate(start)
        let e = capsDate(end)
        if s == nil && e == nil { return nil }
        return "\(s ?? "—") → \(e ?? "—")"
    }

    private static let isoParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withFullDate]
        return f
    }()

    private static let capsFormatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.dateFormat = "MMM d"
        return f
    }()

    private static func capsDate(_ raw: String?) -> String? {
        guard let raw, !raw.isEmpty else { return nil }
        let datePart = String(raw.prefix(10))
        guard let date = isoParser.date(from: datePart) else { return nil }
        return capsFormatter.string(from: date).uppercased()
    }
}

// MARK: - Phase card

private struct PhaseCard: View {
    let phase: GoalRoadmapView.PhaseRow
    let pendingStepIds: Set<String>
    let onToggle: (GoalRoadmapView.StepRow) -> Void
    let onReset: (GoalRoadmapView.StepRow) -> Void

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
                    onToggle: { onToggle(step) },
                    onReset: { onReset(step) }
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
    let onReset: () -> Void

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
                if let readout = step.metricReadout {
                    Text(readout)
                        .font(.hfMonoSm)
                        .foregroundStyle(Theme.textSecondary)
                }
                if step.regressed {
                    Text("Metric regressed")
                        .font(.hfCapsSm)
                        .foregroundStyle(Theme.warn)
                } else if !step.manual {
                    Text("Auto-tracked")
                        .font(.hfCapsSm)
                        .foregroundStyle(Theme.textQuaternary)
                }
                if step.canResetToAuto {
                    Button(action: onReset) {
                        HStack(spacing: 4) {
                            Image(systemName: "arrow.clockwise")
                                .font(.system(size: 10))
                            Text("Reset to auto").font(.hfCapsSm)
                        }
                        .foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                    .disabled(pending)
                    .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
    }
}
