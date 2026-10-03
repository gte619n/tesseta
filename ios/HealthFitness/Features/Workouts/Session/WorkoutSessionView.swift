import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D(ii) — the LIVE active-workout logger. Parity target:
/// Android `feature-workouts/.../session/WorkoutSessionScreen.kt` +
/// `WorkoutSessionViewModel`. Observes the SHARED `WorkoutSessionViewModel`
/// (shared/.../presentation/workouts/WorkoutSessionViewModel.kt) through the
/// `ObservableViewModel` bridge — the view is a pure function of the shared state,
/// no logging/timer logic duplicated on iOS.
///
/// KEY DESIGN (D10, rest-timer-dual-state-gate): the rest overlay, the
/// rest-complete beep (`RestCompleteChime`), AND the Live Activity all read the
/// ONE shared `restTimer` source. There is no second on-screen clock. When that
/// single source crosses to zero the beep fires once; when it changes at all the
/// Live Activity is re-synced. This is the whole point of the shared VM owning the
/// self-ticking timer.
///
/// INTEGRATOR NOTE — navigation: this screen is pushed for a
/// `(programId, scheduledId)` pair. The sibling (Wave D-i) owns
/// `WorkoutsRoutes.swift`; it must add a case:
///     case session(programId: String, scheduledId: String)
/// and a `.navigationDestination(for: WorkoutsRoute.self)` arm that builds
/// `WorkoutSessionView(programId:scheduledId:)`. I did NOT edit that sibling file
/// (collision avoidance); wire this case there.
struct WorkoutSessionView: View {

    let programId: String
    let scheduledId: String

    // MARK: Local mirrors of the shared state (deleted post-0D; the view then
    // switches directly on the SKIE-bridged shared types).

    /// Mirror of shared `WorkoutSessionUiState`.
    struct ScreenState {
        var loading = true
        var dayLabel = ""
        var startedAt = Date()
        var started = false
        var exercises: [ExerciseRow] = []
        var completedSets = 0
        var totalSets = 0
        var prompt: Prompt?
        var completed = false
        var closed = false
        var recap: String?
        var recapLoading = false
        var error: String?
        var isComplete: Bool { totalSets > 0 && completedSets >= totalSets }
    }

    enum Prompt { case finishSummary, skip, discard }

    struct ExerciseRow: Identifiable {
        let id: String           // blockId#orderIndex
        let blockId: String      // carried so the row can rebuild its PrescriptionKey
        let orderIndex: Int
        let name: String
        let blockTitle: String
        let targetSummary: String   // "3 × 5–8 · 135 lb"
        let setsDone: Int
        let setsTotal: Int
        let isTimed: Bool
        let isCurrent: Bool
    }

    /// Mirror of shared `RestTimerState` (the ONE source). `remaining` is driven
    /// by the shared VM's ticker; the view never computes it from a second clock.
    struct RestState: Equatable {
        var totalSeconds: Int
        var remainingSeconds: Int
        var isGetReady: Bool
        var isPaused: Bool
        var isFinished: Bool { remainingSeconds <= 0 }
    }

    private let vm: WorkoutSessionViewModel
    @State private var state = ScreenState()
    @State private var rest: RestState?
    @State private var stateSub: FlowSubscription?
    @State private var restSub: FlowSubscription?
    /// Guards the one-shot beep: we only chime on the finished-EDGE of the single source.
    @State private var didChimeForCurrentRest = false
    @State private var feeling: Int?
    @Environment(\.dismiss) private var dismiss

    init(programId: String, scheduledId: String) {
        self.programId = programId
        self.scheduledId = scheduledId
        // The shared VM self-loads (start → observeDraft) in its init; it resumes an
        // existing on-device draft, so a workout survives process death end-to-end.
        self.vm = IosComposition.shared.workoutSessionViewModel(programId: programId, scheduledId: scheduledId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle(state.dayLabel.isEmpty ? "Workout" : state.dayLabel)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Finish workout") { vm.requestFinish() }
                        Button("Skip session", role: .destructive) { vm.requestSkip() }
                        Button("Discard draft", role: .destructive) { vm.requestDiscard() }
                    } label: { Image(systemName: "ellipsis.circle") }
                }
            }
            .onAppear {
                stateSub = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? WorkoutSessionUiState { state = Self.map(s) }
                }
                restSub = IosComposition.shared.collectFlow(flow: vm.restTimer) { value in
                    rest = Self.mapRest(value as? RestTimerState)
                }
            }
            .onChange(of: state.closed) { _, closed in if closed { dismiss() } }
            .overlay { if let rest { restOverlay(rest) } }
            .sheet(item: promptBinding) { prompt in promptSheet(prompt) }
            .fullScreenCover(isPresented: Binding(get: { state.completed }, set: { _ in })) {
                WorkoutRecapView(
                    dayLabel: state.dayLabel,
                    completedSets: state.completedSets,
                    totalSets: state.totalSets,
                    elapsed: Date().timeIntervalSince(state.startedAt),
                    recap: state.recap,
                    recapLoading: state.recapLoading,
                    onDone: { vm.dismissCompleted() }   // → state.closed → pop
                )
            }
            // The SINGLE rest source drives the Live Activity + the beep. React to
            // any change of `rest` (not a separate clock): re-sync the activity...
            .onChange(of: rest) { _, newValue in
                syncLiveActivity()
                // ...and chime exactly once on the finished edge.
                if let r = newValue, r.isFinished, !didChimeForCurrentRest {
                    didChimeForCurrentRest = true
                    RestCompleteChime.shared.playRestComplete()
                } else if let r = newValue, !r.isFinished {
                    didChimeForCurrentRest = false
                } else if newValue == nil {
                    didChimeForCurrentRest = false
                }
            }
            .onChange(of: state.started) { _, _ in syncLiveActivity() }
            .onChange(of: state.completedSets) { _, _ in syncLiveActivity() }
            .onDisappear {
                stateSub?.cancel()
                restSub?.cancel()
                WorkoutActivityController.shared.end()
            }
    }

    // MARK: Content

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error, state.exercises.isEmpty {
            ContentUnavailableView("Couldn’t start the workout", systemImage: "dumbbell",
                                   description: Text(error))
        } else if !state.started {
            startGate
        } else {
            logger
        }
    }

    private var startGate: some View {
        VStack(spacing: 20) {
            Spacer()
            Image(systemName: "dumbbell.fill").font(.system(size: 44))
                .foregroundStyle(Theme.accent)
            Text(state.dayLabel).font(.hfHeadingLg)
            Text("\(state.totalSets) sets across \(state.exercises.count) exercises")
                .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            Spacer()
            Button {
                vm.markStarted()
            } label: {
                Text("Start workout").font(.hfHeadingSm)
                    .frame(maxWidth: .infinity).padding()
            }
            .buttonStyle(.borderedProminent).tint(Theme.accent)
            .padding(.horizontal)
        }
        .formMaxWidth()
    }

    private var logger: some View {
        ScrollView {
            VStack(spacing: 12) {
                chronometer
                ForEach(state.exercises) { row in
                    ExerciseCard(row: row,
                                 onLogSet: { logSet(row) },
                                 onUndoSet: { undoSet(row) })
                }
                Button {
                    vm.requestFinish()
                } label: {
                    Text(state.isComplete ? "Finish workout" : "Finish early")
                        .frame(maxWidth: .infinity).padding()
                }
                .buttonStyle(.borderedProminent)
                .tint(state.isComplete ? Theme.good : Theme.neutral)
                .padding(.top, 4)
            }
            .padding()
            .formMaxWidth()
        }
    }

    /// The elapsed chronometer — self-ticking from `startedAt` (SwiftUI's `.timer`
    /// style). Parity with Android's foreground-service chronometer, now also
    /// rendered by the Live Activity.
    private var chronometer: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text("Elapsed").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Text(state.startedAt, style: .timer)
                    .font(.hfDisplayMd).foregroundStyle(Theme.textPrimary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text("Sets").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Text("\(state.completedSets) / \(state.totalSets)")
                    .font(.hfHeadingLg).foregroundStyle(Theme.textPrimary)
            }
        }
        .padding()
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: Rest overlay (keyed to the SINGLE shared source)

    @ViewBuilder
    private func restOverlay(_ rest: RestState) -> some View {
        VStack {
            Spacer()
            VStack(spacing: 12) {
                Text(rest.isGetReady ? "Get ready" : "Rest")
                    .font(.hfCapsSm).foregroundStyle(Theme.textInverse.opacity(0.7))
                Text(formatCountdown(rest.remainingSeconds))
                    .font(.hfDisplayXl).foregroundStyle(Theme.textInverse)
                    .monospacedDigit()
                ProgressView(value: Double(rest.totalSeconds - rest.remainingSeconds),
                             total: Double(max(rest.totalSeconds, 1)))
                    .tint(rest.isGetReady ? Theme.warn : Theme.accent)
                    .frame(width: 200)
                HStack(spacing: 16) {
                    if rest.isGetReady {
                        Button(rest.isPaused ? "Resume" : "Pause") {
                            if rest.isPaused { vm.resumeTimer() } else { vm.pauseTimer() }
                        }
                    }
                    Button("Skip rest") {
                        vm.dismissRest()  // clears the single source for ALL consumers
                    }.buttonStyle(.borderedProminent).tint(Theme.accent)
                }
            }
            .padding(24)
            .background(.black.opacity(0.9), in: RoundedRectangle(cornerRadius: 20))
            .padding()
            Spacer()
        }
        .background {
            Color.black.opacity(0.4).ignoresSafeArea()
        }
    }

    // MARK: Prompts

    private var promptBinding: Binding<Prompt?> {
        Binding(get: { state.prompt }, set: { if $0 == nil { vm.dismissPrompt() } })
    }

    @ViewBuilder
    private func promptSheet(_ prompt: Prompt) -> some View {
        switch prompt {
        case .finishSummary:
            FinishSummarySheet(
                completedSets: state.completedSets,
                totalSets: state.totalSets,
                feeling: $feeling,
                onConfirm: {
                    vm.confirmFinish(feeling: feeling.map { KotlinInt(int: Int32($0)) })
                },
                onCancel: { vm.dismissPrompt() }
            )
            .presentationDetents([.medium])
        case .skip:
            ConfirmSheet(title: "Skip this session?",
                         message: "Your logged sets are cleared and the session is marked skipped.",
                         confirmLabel: "Skip",
                         onConfirm: { vm.confirmSkip() },
                         onCancel: { vm.dismissPrompt() })
            .presentationDetents([.height(220)])
        case .discard:
            ConfirmSheet(title: "Discard this draft?",
                         message: "Nothing is uploaded — this in-progress workout is thrown away.",
                         confirmLabel: "Discard",
                         onConfirm: { vm.confirmDiscard() },
                         onCancel: { vm.dismissPrompt() })
            .presentationDetents([.height(220)])
        }
    }

    // MARK: Intents (post-0D these call the shared VM)

    private func logSet(_ row: ExerciseRow) {
        // Check off the next set: the shared VM appends a defaulted set + starts the
        // single rest source (and auto-opens finish on the last set).
        vm.toggleSet(key: key(row), setIndex: Int32(row.setsDone))
    }
    private func undoSet(_ row: ExerciseRow) {
        guard row.setsDone > 0 else { return }
        vm.toggleSet(key: key(row), setIndex: Int32(row.setsDone - 1))
    }
    private func key(_ row: ExerciseRow) -> PrescriptionKey {
        PrescriptionKey(blockId: row.blockId, orderIndex: Int32(row.orderIndex))
    }

    // MARK: - Mapping (SKIE-bridged shared state → local mirror)

    static func map(_ s: WorkoutSessionUiState) -> ScreenState {
        var out = ScreenState()
        out.loading = s.loading
        out.started = s.started
        out.completed = s.completed
        out.closed = s.closed
        out.recap = s.recap
        out.recapLoading = s.recapLoading
        out.error = s.error
        out.prompt = mapPrompt(s.prompt)
        if let draft = s.draft {
            out.dayLabel = draft.scheduled.dayLabel
            out.startedAt = Date(timeIntervalSince1970: Double(draft.startedAt.toEpochMilliseconds()) / 1000.0)
            out.completedSets = Int(draft.totalLoggedSets)
            out.totalSets = Int(draft.totalPrescribedSets())
            out.exercises = draft.sessionRows().map { r in
                ExerciseRow(
                    id: "\(r.blockId)#\(r.orderIndex)",
                    blockId: r.blockId,
                    orderIndex: Int(r.orderIndex),
                    name: r.name,
                    blockTitle: r.blockTitle,
                    targetSummary: r.targetSummary,
                    setsDone: Int(r.setsDone),
                    setsTotal: Int(r.setsTotal),
                    isTimed: r.isTimed,
                    isCurrent: r.isCurrent
                )
            }
        }
        return out
    }

    static func mapRest(_ r: RestTimerState?) -> RestState? {
        guard let r else { return nil }
        return RestState(
            totalSeconds: Int(r.totalSeconds),
            remainingSeconds: Int(r.remainingSeconds),
            isGetReady: r.kind == RestKind.getReady,
            isPaused: r.isPaused
        )
    }

    static func mapPrompt(_ p: SessionPrompt?) -> Prompt? {
        guard let p else { return nil }
        if p == SessionPrompt.finishSummary { return .finishSummary }
        if p == SessionPrompt.skip { return .skip }
        if p == SessionPrompt.discard { return .discard }
        return nil
    }

    /// Build the Live Activity snapshot from the CURRENT shared state + the ONE
    /// rest source and push it. Called on every meaningful change.
    private func syncLiveActivity() {
        guard state.started, !state.completed else {
            WorkoutActivityController.shared.end(); return
        }
        let current = state.exercises.first(where: { $0.isCurrent }) ?? state.exercises.first
        let snapshot = WorkoutActivityController.Snapshot(
            dayLabel: state.dayLabel,
            startedAt: state.startedAt,
            currentExercise: current?.name ?? "Workout",
            setProgress: current.map { "Set \(min($0.setsDone + 1, $0.setsTotal)) of \($0.setsTotal)" } ?? "",
            completedSets: state.completedSets,
            totalSets: state.totalSets,
            restRemainingSeconds: rest.map { $0.remainingSeconds },
            restIsGetReady: rest?.isGetReady ?? false,
            isComplete: state.isComplete
        )
        WorkoutActivityController.shared.start(snapshot)
    }

    private func formatCountdown(_ seconds: Int) -> String {
        let s = max(0, seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }
}

// MARK: - Exercise card + set logger

private struct ExerciseCard: View {
    let row: WorkoutSessionView.ExerciseRow
    let onLogSet: () -> Void
    let onUndoSet: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(row.blockTitle.uppercased())
                .font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
            HStack {
                Text(row.name).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
                Spacer()
                if row.isCurrent {
                    Text("NOW").font(.hfCapsSm).foregroundStyle(Theme.accent)
                }
            }
            Text(row.targetSummary).font(.hfBodySm).foregroundStyle(Theme.textSecondary)

            // Set checkboxes: one per prescribed set, filled as logged.
            HStack(spacing: 8) {
                ForEach(0..<max(row.setsTotal, 1), id: \.self) { i in
                    Image(systemName: i < row.setsDone ? "checkmark.circle.fill" : "circle")
                        .font(.title3)
                        .foregroundStyle(i < row.setsDone ? Theme.good : Theme.borderStrong)
                }
                Spacer()
                Button(action: onLogSet) {
                    Text(row.isTimed ? "Log hold" : "Log set").font(.hfBodySm)
                }
                .buttonStyle(.bordered).tint(Theme.accent)
                .disabled(row.setsDone >= row.setsTotal)
            }
        }
        .padding()
        .background(row.isCurrent ? Theme.accentBg : Theme.surface,
                    in: RoundedRectangle(cornerRadius: 12))
    }
}

// MARK: - Finish summary (with RIR/effort + feeling capture)

private struct FinishSummarySheet: View {
    let completedSets: Int
    let totalSets: Int
    @Binding var feeling: Int?
    let onConfirm: () -> Void
    let onCancel: () -> Void

    /// The post-workout feeling scale (1…5), the iOS parity of Android's
    /// finish-summary feeling row. Per-set RIR/effort is captured inline on the
    /// last working set (see `RirEffortPicker`); this is the whole-session feeling.
    private let feelings = [1, 2, 3, 4, 5]

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("\(completedSets) of \(totalSets) sets logged")
                    .font(.hfHeadingLg).foregroundStyle(Theme.textPrimary)
                VStack(spacing: 8) {
                    Text("How did it feel?").font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                    HStack(spacing: 12) {
                        ForEach(feelings, id: \.self) { value in
                            Button {
                                feeling = value
                            } label: {
                                Image(systemName: feeling == value ? "circle.fill" : "circle")
                                    .foregroundStyle(feeling == value ? Theme.accent : Theme.borderStrong)
                                Text(feelingLabel(value)).font(.hfCapsSm)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                Spacer()
                Button(action: onConfirm) {
                    Text("Finish").frame(maxWidth: .infinity).padding()
                }.buttonStyle(.borderedProminent).tint(Theme.good)
            }
            .padding()
            .navigationTitle("Finish workout")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel", action: onCancel) } }
        }
    }

    private func feelingLabel(_ v: Int) -> String {
        switch v { case 1: return "😣"; case 2: return "😕"; case 3: return "😐"; case 4: return "🙂"; default: return "💪" }
    }
}

/// One-tap RIR (reps-in-reserve) / timed-effort picker shown on the LAST working
/// set of an exercise — parity with Android's last-set RIR gate + timed-effort
/// row (`RIR_CHOICES` / `TIMED_EFFORT_CHOICES` in the shared `SessionFormat`).
/// Pre-selects the inferred value; tapping a chip flips the source to REPORTED.
struct RirEffortPicker: View {
    let isTimed: Bool
    @Binding var rir: Int?
    @Binding var timedEffort: String?

    private let rirChoices = [0, 1, 2, 3, 4, 5]
    private let effortChoices = ["LESS", "SAME", "MORE"]

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(isTimed ? "How was the hold?" : "Reps in reserve")
                .font(.hfCapsSm).foregroundStyle(Theme.textSecondary)
            if isTimed {
                HStack {
                    ForEach(effortChoices, id: \.self) { choice in
                        chip(label: effortLabel(choice), selected: timedEffort == choice) {
                            timedEffort = choice
                        }
                    }
                }
            } else {
                HStack {
                    ForEach(rirChoices, id: \.self) { choice in
                        chip(label: choice == 5 ? "5+" : "\(choice)", selected: rir == choice) {
                            rir = choice
                        }
                    }
                }
            }
        }
    }

    private func chip(label: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(label).font(.hfBodySm)
                .padding(.vertical, 6).padding(.horizontal, 12)
                .background(selected ? Theme.accent : Theme.canvasMuted,
                            in: Capsule())
                .foregroundStyle(selected ? Theme.textInverse : Theme.textPrimary)
        }.buttonStyle(.plain)
    }

    private func effortLabel(_ v: String) -> String {
        switch v { case "LESS": return "Too hard"; case "MORE": return "Too easy"; default: return "Just right" }
    }
}

private struct ConfirmSheet: View {
    let title: String
    let message: String
    let confirmLabel: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text(title).font(.hfHeadingSm)
            Text(message).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                .multilineTextAlignment(.center)
            HStack {
                Button("Cancel", action: onCancel).buttonStyle(.bordered)
                Button(confirmLabel, role: .destructive, action: onConfirm)
                    .buttonStyle(.borderedProminent).tint(Theme.alert)
            }
        }.padding()
    }
}

extension WorkoutSessionView.Prompt: Identifiable {
    var id: Int { switch self { case .finishSummary: return 0; case .skip: return 1; case .discard: return 2 } }
}
