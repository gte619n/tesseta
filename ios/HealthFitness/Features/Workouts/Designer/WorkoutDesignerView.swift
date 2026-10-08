import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave D(iii) — the AI program-designer chat.
/// Parity target (Android): `feature-workouts/.../program/chat/WorkoutDesignerScreen.kt`
/// + `WorkoutDesignerViewModel`. Backed by the SHARED `WorkoutDesignerViewModel`
/// (sseClient + chatRepository + locationRepository + goalsRepository +
/// idGenerator) over the real SSE transport ([KtorSseClient]) + the networked
/// commit/thread half ([HttpWorkoutProgramChatRepository]). Observed via
/// `collectFlow` + a static `map(...)` to local mirror structs (SKIE-free).
///
/// The flow: a setup form (training days + a gym per day + an optional goal),
/// then a streaming conversation. Assistant text renders as markdown and grows
/// as tokens land; a `proposal` event attaches a program card the user can
/// commit (Save program) or discard.
///
/// SCOPE (see §"Decisions for review" in IMPL-IOS-01-OFFLINE-SYNC): the shared
/// `WorkoutDesignerViewModel` does NOT port Android's in-card PROGRAM-EDIT tree
/// (ProgramProposalEdit/Phase/Day/Block/PrescriptionEdit) nor the TRT labs
/// safety panel — those are Android-Compose-only; the shared VM commits the
/// streamed proposal as-is. So this view wires STREAMING + DISPLAY + accept +
/// discard. Commit is best-effort: the flat shared `ProgramProposal` can't carry
/// exerciseIds, so an unresolved exercise surfaces the backend's 422 issues on
/// the card rather than silently succeeding.
struct WorkoutDesignerView: View {

    // MARK: Local mirrors of the shared UI state

    struct GymOption: Identifiable, Hashable { let id: String; let name: String }
    struct GoalOption: Identifiable, Hashable { let id: String; let title: String }

    enum Weekday: String, CaseIterable, Identifiable {
        case mon = "Mon", tue = "Tue", wed = "Wed", thu = "Thu", fri = "Fri", sat = "Sat", sun = "Sun"
        var id: String { rawValue }

        /// The bridged shared `DayOfWeek` (MON..SUN) for this Swift day.
        var shared: SharedCore.DayOfWeek {
            switch self {
            case .mon: return .mon
            case .tue: return .tue
            case .wed: return .wed
            case .thu: return .thu
            case .fri: return .fri
            case .sat: return .sat
            case .sun: return .sun
            }
        }

        static func from(_ d: SharedCore.DayOfWeek) -> Weekday {
            switch d {
            case SharedCore.DayOfWeek.mon: return .mon
            case SharedCore.DayOfWeek.tue: return .tue
            case SharedCore.DayOfWeek.wed: return .wed
            case SharedCore.DayOfWeek.thu: return .thu
            case SharedCore.DayOfWeek.fri: return .fri
            case SharedCore.DayOfWeek.sat: return .sat
            default: return .sun
            }
        }
    }

    struct SetupState {
        var gyms: [GymOption] = []
        var goals: [GoalOption] = []
        var trainingDays: Set<Weekday> = []
        var dayLocations: [Weekday: String] = [:]
        var goalId: String?
        var loading = true
        var isReady: Bool { !trainingDays.isEmpty && trainingDays.allSatisfy { dayLocations[$0] != nil } }
    }

    struct ProposalCardModel: Hashable {
        let messageId: String
        let title: String
        let description: String?
        let phases: [ProposalPhaseModel]
        let phaseCount: Int
        let dayCount: Int
        let warnings: [String]
        let issues: [String]
        let committedProgramId: String?
        let saving: Bool
    }

    struct ProposalPhaseModel: Hashable, Identifiable {
        let id = UUID()
        let title: String
        let focus: String?
        let days: [ProposalDayModel]
    }

    struct ProposalDayModel: Hashable, Identifiable {
        let id = UUID()
        let label: String
        let lines: [String]   // "Squat · 3×5", "Bench · 3×8"
    }

    enum Message: Identifiable {
        case user(id: String, text: String)
        case assistant(id: String, text: String, streaming: Bool, proposal: ProposalCardModel?)
        var id: String {
            switch self {
            case .user(let id, _), .assistant(let id, _, _, _): return id
            }
        }
    }

    private let vm: WorkoutDesignerViewModel
    @State private var subscription: FlowSubscription?
    @State private var setup = SetupState()
    @State private var started = false
    @State private var messages: [Message] = []
    @State private var streaming = false
    @State private var error: String?
    @State private var draft = ""

    init(programId: String? = nil) {
        self.vm = IosComposition.shared.workoutDesignerViewModel(programId: programId)
    }

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("AI Designer")
            .navigationBarTitleDisplayMode(.inline)
            .accessibilityIdentifier("workout-designer")  // IMPL-E2E-01 shared id
            .onAppear {
                subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                    if let s = value as? WorkoutDesignerUiState { apply(s) }
                }
            }
            .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder
    private var content: some View {
        if started {
            chat
        } else {
            setupForm
        }
    }

    // MARK: - Setup form

    private var setupForm: some View {
        ScrollView {
            VStack(spacing: 16) {
                SettingsCard(title: "Training days",
                             description: "Pick the days you'll train and a gym for each.") {
                    ForEach(Weekday.allCases) { day in
                        dayRow(day)
                    }
                }

                if !setup.goals.isEmpty {
                    SettingsCard(title: "Goal (optional)",
                                 description: "Ground the program in a goal you're working toward.") {
                        ForEach(setup.goals) { goal in
                            HStack {
                                Text(goal.title).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                                Spacer()
                                if setup.goalId == goal.id {
                                    Image(systemName: "checkmark").foregroundStyle(Theme.accent)
                                }
                            }
                            .contentShape(Rectangle())
                            .onTapGesture {
                                let next = setup.goalId == goal.id ? nil : goal.id
                                vm.setGoal(goalId: next)
                            }
                            .padding(.vertical, 6)
                        }
                    }
                }

                SettingsCard(title: "Describe your program",
                             description: "e.g. \"A 4-day upper/lower split grounded in my recent lifts\"") {
                    composer(placeholder: "What should this program do?", canSend: setup.isReady)
                    if let error { errorText(error) }
                }
            }
            .padding()
            .formMaxWidth()
        }
    }

    private func dayRow(_ day: Weekday) -> some View {
        let selected = setup.trainingDays.contains(day)
        return VStack(spacing: 8) {
            Toggle(isOn: Binding(
                get: { selected },
                set: { _ in vm.toggleTrainingDay(day: day.shared) }
            )) {
                Text(day.rawValue).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            }
            .tint(Theme.accent)

            if selected && setup.gyms.count > 1 {
                SegmentedChoice(
                    options: setup.gyms.map { (value: $0.id, label: $0.name) },
                    selection: Binding(
                        get: { setup.dayLocations[day] },
                        set: { if let loc = $0 { vm.setDayGym(day: day.shared, locationId: loc) } }
                    )
                )
            }
        }
    }

    // MARK: - Chat

    private var chat: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(messages) { message in
                            messageView(message).id(message.id)
                        }
                    }
                    .padding()
                    .formMaxWidth()
                }
                .onChange(of: messages.count) { _, _ in
                    if let last = messages.last { withAnimation { proxy.scrollTo(last.id, anchor: .bottom) } }
                }
            }
            Divider()
            VStack(spacing: 8) {
                if let error { errorText(error) }
                composer(placeholder: "Refine the program…", canSend: true)
            }
            .padding()
            .formMaxWidth()
        }
    }

    @ViewBuilder
    private func messageView(_ message: Message) -> some View {
        switch message {
        case .user(_, let text):
            HStack {
                Spacer(minLength: 40)
                Text(text)
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textInverse)
                    .padding(10)
                    .background(Theme.accent, in: RoundedRectangle(cornerRadius: 12))
            }
        case .assistant(_, let text, let isStreaming, let proposal):
            VStack(alignment: .leading, spacing: 8) {
                if !text.isEmpty {
                    Text(markdown(text))
                        .font(.hfBodyMd)
                        .foregroundStyle(Theme.textPrimary)
                }
                if isStreaming && text.isEmpty {
                    ProgressView().controlSize(.small)
                }
                if let proposal { proposalCardView(proposal) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Theme.borderDefault, lineWidth: 0.5))
        }
    }

    @ViewBuilder
    private func proposalCardView(_ card: ProposalCardModel) -> some View {
        if let programId = card.committedProgramId {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.good)
                Text("Program created").font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
                Spacer()
            }
            .padding(12)
            .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 10))
            .accessibilityIdentifier("program-committed-\(programId)")
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Text(card.title).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
                if let desc = card.description, !desc.isEmpty {
                    Text(desc).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                }
                Text("\(card.phaseCount) phase\(card.phaseCount == 1 ? "" : "s") · \(card.dayCount) training day\(card.dayCount == 1 ? "" : "s")")
                    .font(.hfBodySm).foregroundStyle(Theme.textSecondary)

                ForEach(card.phases) { phase in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(phase.title + (phase.focus.map { " — \($0)" } ?? ""))
                            .font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                        ForEach(phase.days) { day in
                            VStack(alignment: .leading, spacing: 1) {
                                Text(day.label).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                                ForEach(day.lines, id: \.self) { line in
                                    Text("• \(line)").font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                                }
                            }
                            .padding(.leading, 8)
                        }
                    }
                    .padding(.top, 2)
                }

                ForEach(card.warnings, id: \.self) { warning in
                    Label(warning, systemImage: "exclamationmark.triangle")
                        .font(.hfBodySm).foregroundStyle(Theme.warn)
                }
                ForEach(card.issues, id: \.self) { issue in
                    Label(issue, systemImage: "xmark.octagon")
                        .font(.hfBodySm).foregroundStyle(Theme.alert)
                }
                HStack {
                    Button(action: { vm.commit(messageId: card.messageId, edited: editedProposal(card.messageId)) }) {
                        if card.saving { ProgressView().controlSize(.small) } else { Text("Save program") }
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(!card.issues.isEmpty || card.saving)
                    Button("Discard") { vm.discard(messageId: card.messageId) }
                        .buttonStyle(.bordered)
                        .disabled(card.saving)
                }
            }
            .padding(12)
            .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 10))
        }
    }

    /// The proposal to commit for [messageId]. NOTE (deferred): in-card EDITING of
    /// the proposal before commit (the Android ProgramProposalEdit tree) isn't
    /// wired — we re-read the streamed proposal off the VM state and commit it
    /// as-is; the backend re-validates and returns actionable issues on a 422.
    private func editedProposal(_ messageId: String) -> SharedCore.ProgramProposal {
        if let s = vm.state.value as? WorkoutDesignerUiState,
           let m = s.messages.first(where: { $0.id == messageId }) as? DesignerMessageAssistant,
           let p = m.proposal {
            return p
        }
        return SharedCore.ProgramProposal(title: "", description: nil, phases: [])
    }

    // MARK: - Shared bits

    private func composer(placeholder: String, canSend: Bool) -> some View {
        HStack(spacing: 8) {
            TextField(placeholder, text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .padding(10)
                .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 10))
            Button {
                let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                guard !text.isEmpty else { return }
                vm.send(message: text)
                draft = ""
            } label: {
                Image(systemName: "arrow.up.circle.fill").font(.title2)
            }
            .tint(Theme.accent)
            .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty || streaming || !canSend)
        }
    }

    private func errorText(_ message: String) -> some View {
        Text(message).font(.hfBodySm).foregroundStyle(Theme.alert)
            .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func markdown(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text)) ?? AttributedString(text)
    }

    // MARK: - Apply shared UiState → local mirror

    private func apply(_ s: WorkoutDesignerUiState) {
        let gyms = s.setup.gyms.map { GymOption(id: $0.locationId, name: $0.name) }
        let goals = s.setup.goals.map { GoalOption(id: $0.goalId, title: $0.title) }
        let days = Set(s.setup.trainingDays.compactMap { ($0 as? SharedCore.DayOfWeek).map { Weekday.from($0) } })
        var locs: [Weekday: String] = [:]
        for (k, v) in s.setup.dayLocations {
            if let d = k as? SharedCore.DayOfWeek, let loc = v as? String {
                locs[Weekday.from(d)] = loc
            }
        }
        setup = SetupState(gyms: gyms, goals: goals, trainingDays: days,
                           dayLocations: locs, goalId: s.setup.goalId, loading: s.setup.loading)
        started = s.started
        streaming = s.streaming
        error = s.error

        messages = s.messages.compactMap { any in
            if let u = any as? DesignerMessageUser {
                return Message.user(id: u.id, text: u.text)
            }
            if let a = any as? DesignerMessageAssistant {
                let issues = (s.proposalIssues[a.id] as? [String]) ?? []
                let warnings = (s.proposalWarnings[a.id] as? [String]) ?? []
                let committed = s.committedProgramIds[a.id]
                let saving = s.savingMessageIds.contains(a.id)
                let card = a.proposal.map {
                    Self.mapProposal($0, messageId: a.id, issues: issues, warnings: warnings,
                                     committedProgramId: committed, saving: saving)
                }
                return Message.assistant(id: a.id, text: a.text, streaming: a.streaming, proposal: card)
            }
            return nil
        }
    }

    private static func mapProposal(_ p: SharedCore.ProgramProposal,
                                    messageId: String,
                                    issues: [String],
                                    warnings: [String],
                                    committedProgramId: String?,
                                    saving: Bool) -> ProposalCardModel {
        let phases: [ProposalPhaseModel] = p.phases.map { phase in
            let days = phase.days.map { day -> ProposalDayModel in
                let lines = day.blocks.flatMap { block in
                    block.prescriptions.map { rx in prescriptionLine(rx) }
                }
                return ProposalDayModel(label: day.label, lines: lines)
            }
            return ProposalPhaseModel(title: phase.title, focus: phase.focus, days: days)
        }
        let dayCount = p.phases.reduce(0) { $0 + $1.days.count }
        return ProposalCardModel(
            messageId: messageId,
            title: p.title,
            description: p.description_,
            phases: phases,
            phaseCount: p.phases.count,
            dayCount: dayCount,
            warnings: warnings,
            issues: issues,
            committedProgramId: committedProgramId,
            saving: saving
        )
    }

    private static func prescriptionLine(_ rx: SharedCore.ProposalPrescription) -> String {
        var parts = [rx.exerciseName]
        if let sets = rx.sets?.intValue {
            if let lo = rx.repsMin?.intValue, let hi = rx.repsMax?.intValue {
                parts.append(lo == hi ? "\(sets)×\(lo)" : "\(sets)×\(lo)–\(hi)")
            } else if let lo = rx.repsMin?.intValue {
                parts.append("\(sets)×\(lo)")
            } else if let dur = rx.durationSeconds?.intValue {
                parts.append("\(sets)×\(dur)s")
            } else {
                parts.append("\(sets) sets")
            }
        } else if let dur = rx.durationSeconds?.intValue {
            parts.append("\(dur)s")
        }
        return parts.joined(separator: " · ")
    }
}

#Preview {
    NavigationStack { WorkoutDesignerView() }
}
