import SwiftUI
import SharedCore

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals). Parity target: Android
/// `feature-goals/.../GoalsChatScreen` + `GoalsChatViewModel` + the reusable
/// `core-chat/ChatThread` composable. A streaming SSE chat: the user sends a
/// prompt, the shared VM opens the SSE stream and folds assistant tokens into a
/// growing bubble rendered as markdown (native `AttributedString(markdown:)`,
/// see ChatMarkdown), and an attached AI goal proposal renders an editable card.
///
/// Backed by the SHARED `GoalsChatViewModel` (sseClient + chatRepository +
/// idGenerator) over the real SSE transport ([KtorSseClient]) + the networked
/// commit/thread half ([HttpChatRepository]). Observed via `collectFlow` + a
/// static `map(...)` to local mirror structs — the same SKIE-free pattern as
/// `GoalRoadmapView`. The shared VM OWNS the stream + `send`/`commit`/`discard`.
struct GoalsChatView: View {

    // MARK: Local mirrors

    enum Role { case user, assistant }

    struct Message: Identifiable, Hashable {
        let id: String
        let role: Role
        var text: String
        var streaming: Bool
        var proposal: ProposalView?
    }

    /// Flattened, display-ready view of the bridged `GoalProposal` + commit state.
    struct ProposalView: Hashable {
        let title: String
        let domainLabel: String?
        let targetDate: String?
        let phases: [ProposalPhaseView]
        let validationError: String?
        let committedGoalId: String?   // non-nil once committed → collapsed state
        let saving: Bool
    }

    struct ProposalPhaseView: Hashable, Identifiable {
        let id = UUID()
        let title: String
        let steps: [String]        // "Run 5k · restingHr < 60 for 30d"
        let validationError: String?
    }

    struct ScreenState {
        var messages: [Message] = []
        var streaming: Bool = false
        var error: String?
    }

    private let vm: GoalsChatViewModel
    @State private var state = ScreenState()
    @State private var subscription: FlowSubscription?
    @State private var draft: String = ""
    @FocusState private var composerFocused: Bool

    private let suggestedPrompts = [
        "Help me build a plan to get my ApoB into optimal range",
        "Plan a 12-week strength base",
        "I want to improve my sleep score — design a roadmap",
    ]

    init() {
        self.vm = IosComposition.shared.goalsChatViewModel()
    }

    var body: some View {
        VStack(spacing: 0) {
            transcript
            Divider()
            composer
        }
        .background(Theme.canvas)
        .navigationTitle("Plan a goal")
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("goals-chat")  // IMPL-E2E-01 shared id
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                if let s = value as? GoalsChatUiState { state = Self.map(s) }
            }
        }
        .onDisappear { subscription?.cancel() }
    }

    // MARK: Transcript

    @ViewBuilder
    private var transcript: some View {
        if state.messages.isEmpty {
            emptyState
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 12) {
                        ForEach(state.messages) { message in
                            MessageBubble(message: message,
                                          onCommit: { commit(message) },
                                          onDiscard: { vm.discard(messageId: message.id) })
                                .id(message.id)
                        }
                        if let error = state.error {
                            Text(error)
                                .font(.hfBodySm)
                                .foregroundStyle(Theme.alert)
                                .frame(maxWidth: .infinity, alignment: .center)
                        }
                    }
                    .padding()
                    .formMaxWidth()
                }
                .onChange(of: state.messages.last?.text) {
                    if let last = state.messages.last {
                        withAnimation(.easeOut(duration: 0.15)) {
                            proxy.scrollTo(last.id, anchor: .bottom)
                        }
                    }
                }
            }
        }
    }

    private var emptyState: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Plan a new goal")
                    .font(.hfHeadingLg)
                    .foregroundStyle(Theme.textPrimary)
                Text("Describe what you want to achieve and I’ll draft a phased roadmap you can edit before saving.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
                    .padding(.bottom, 6)
                ForEach(suggestedPrompts, id: \.self) { prompt in
                    Button { send(prompt) } label: {
                        Text(prompt)
                            .font(.hfBodyMd)
                            .foregroundStyle(Theme.textPrimary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 14)
                            .padding(.vertical, 12)
                            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding()
            .formMaxWidth()
        }
    }

    // MARK: Composer

    private var composer: some View {
        HStack(spacing: 8) {
            TextField("Message", text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...5)
                .focused($composerFocused)
                .font(.hfBodyMd)
                .padding(.horizontal, 12)
                .padding(.vertical, 9)
                .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 18))
                .onSubmit(submit)

            Button(action: submit) {
                Image(systemName: "arrow.up.circle.fill")
                    .font(.system(size: 28))
                    .foregroundStyle(canSend ? Theme.accent : Theme.textQuaternary)
            }
            .buttonStyle(.plain)
            .disabled(!canSend)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(Theme.surface)
    }

    // The composer gate: a non-empty draft AND the VM not mid-stream (the shared
    // `send` is a no-op while streaming; mirroring Android's isOnline/streaming gate).
    private var canSend: Bool {
        !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !state.streaming
    }

    private func submit() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty, !state.streaming else { return }
        send(text)
        draft = ""
    }

    private func send(_ text: String) {
        vm.send(message: text)
    }

    /// Commit the proposal on [message]. The VM re-validates server-side; a 400
    /// re-seeds the card with inline `validationError` flags (no local edit model
    /// yet — title/phase edits are a deferred affordance, see the designer note).
    private func commit(_ message: Message) {
        guard let assistant = vm.state.value as? GoalsChatUiState else { return }
        guard let m = assistant.messages.first(where: { $0.id == message.id }) as? ChatMessageAssistant,
              let proposal = m.proposal else { return }
        vm.commit(messageId: message.id, edited: proposal)
    }

    // MARK: Map shared UiState → local mirror

    static func map(_ s: GoalsChatUiState) -> ScreenState {
        let msgs: [Message] = s.messages.compactMap { any in
            if let u = any as? ChatMessageUser {
                return Message(id: u.id, role: .user, text: u.text, streaming: false, proposal: nil)
            }
            if let a = any as? ChatMessageAssistant {
                let committedId = s.committedGoalIds[a.id]
                let saving = s.savingMessageIds.contains(a.id)
                let proposal = a.proposal.map { mapProposal($0, committedGoalId: committedId, saving: saving) }
                return Message(id: a.id, role: .assistant, text: a.text,
                               streaming: a.streaming, proposal: proposal)
            }
            return nil
        }
        return ScreenState(messages: msgs, streaming: s.streaming, error: s.error)
    }

    private static func mapProposal(_ p: SharedCore.GoalProposal,
                                    committedGoalId: String?,
                                    saving: Bool) -> ProposalView {
        let phases: [ProposalPhaseView] = p.phases.map { phase in
            let steps = phase.steps.map { stepLine($0) }
            return ProposalPhaseView(title: phase.title ?? "Phase",
                                     steps: steps,
                                     validationError: phase.validationError)
        }
        return ProposalView(
            title: p.title ?? "Drafted roadmap",
            domainLabel: p.domain.map { domainLabel($0) },
            targetDate: p.targetDate,
            phases: phases,
            validationError: p.validationError,
            committedGoalId: committedGoalId,
            saving: saving
        )
    }

    private static func stepLine(_ step: SharedCore.ProposalStep) -> String {
        let title = step.title ?? "Step"
        guard let m = step.metric, let key = m.metricKey, let cmp = m.comparator,
              let target = m.targetValue?.doubleValue else {
            return title
        }
        let targetStr = target == target.rounded() ? String(Int(target)) : String(target)
        var readout = "\(key) \(cmp.symbol) \(targetStr)"
        if step.kind == SharedCore.StepKind.sustained, let w = m.windowDays?.intValue {
            readout += " for \(w)d"
        }
        return "\(title) · \(readout)"
    }

    private static func domainLabel(_ d: SharedCore.GoalDomain) -> String {
        switch d {
        case SharedCore.GoalDomain.cardiovascular: return "Cardiovascular"
        case SharedCore.GoalDomain.bodyComposition: return "Body composition"
        case SharedCore.GoalDomain.strength: return "Strength"
        case SharedCore.GoalDomain.metabolic: return "Metabolic"
        case SharedCore.GoalDomain.sleep: return "Sleep"
        case SharedCore.GoalDomain.longevity: return "Longevity"
        default: return "Other"
        }
    }
}

// MARK: - Bubbles

private struct MessageBubble: View {
    let message: GoalsChatView.Message
    let onCommit: () -> Void
    let onDiscard: () -> Void

    var body: some View {
        switch message.role {
        case .user:
            HStack {
                Spacer(minLength: 40)
                Text(message.text)
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textInverse)
                    .padding(.horizontal, 13)
                    .padding(.vertical, 9)
                    .background(Theme.accent, in: BubbleShape(tail: .trailing))
            }
        case .assistant:
            VStack(alignment: .leading, spacing: 8) {
                if message.text.isEmpty && message.streaming {
                    TypingIndicator()
                } else if !message.text.isEmpty {
                    HStack {
                        Text(ChatMarkdown.attributed(message.text))
                            .font(.hfBodyMd)
                            .foregroundStyle(Theme.textPrimary)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 9)
                            .background(Theme.surface, in: BubbleShape(tail: .leading))
                        Spacer(minLength: 40)
                    }
                }
                if let proposal = message.proposal {
                    ProposalCard(proposal: proposal, onCommit: onCommit, onDiscard: onDiscard)
                }
            }
        }
    }
}

private struct TypingIndicator: View {
    @State private var phase = 0.0
    var body: some View {
        HStack(spacing: 4) {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .fill(Theme.textTertiary)
                    .frame(width: 6, height: 6)
                    .opacity(0.3 + 0.7 * abs(sin(phase + Double(i) * 0.6)))
            }
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 11)
        .background(Theme.surface, in: BubbleShape(tail: .leading))
        .onAppear {
            withAnimation(.linear(duration: 1.2).repeatForever(autoreverses: false)) {
                phase = .pi * 2
            }
        }
    }
}

/// The editable AI goal-proposal card. Renders the drafted roadmap (title /
/// domain / target date + phases/steps with inline `validationError` flags) and
/// commits through `vm.commit(...)`. Once committed it collapses to a success
/// row. NOTE (deferred): per-field INLINE EDITING of the proposal before commit
/// (title/phase/step/metric fields) is not yet wired — the card commits the
/// streamed proposal as-is; the server re-validates and re-flags fields on a 400.
private struct ProposalCard: View {
    let proposal: GoalsChatView.ProposalView
    let onCommit: () -> Void
    let onDiscard: () -> Void

    var body: some View {
        if let goalId = proposal.committedGoalId {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(Theme.good)
                Text("Goal created").font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
                Spacer()
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 12))
            .accessibilityIdentifier("goal-committed-\(goalId)")
        } else {
            VStack(alignment: .leading, spacing: 8) {
                Label(proposal.title, systemImage: "target")
                    .font(.hfHeadingSm)
                    .foregroundStyle(Theme.textPrimary)
                if let domain = proposal.domainLabel {
                    Text(domain).font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                }
                if let target = proposal.targetDate {
                    Text("Target: \(target)").font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                }
                ForEach(proposal.phases) { phase in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(phase.title).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                        ForEach(phase.steps, id: \.self) { step in
                            Text("• \(step)").font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                        }
                        if let err = phase.validationError {
                            Text(err).font(.hfCapsSm).foregroundStyle(Theme.alert)
                        }
                    }
                    .padding(.top, 2)
                }
                if let err = proposal.validationError {
                    Text(err).font(.hfBodySm).foregroundStyle(Theme.alert)
                }
                HStack {
                    Button(action: onCommit) {
                        if proposal.saving {
                            ProgressView().controlSize(.small)
                        } else {
                            Text("Save goal")
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Theme.accent)
                    .disabled(proposal.saving)
                    Button("Discard", action: onDiscard)
                        .buttonStyle(.bordered)
                        .disabled(proposal.saving)
                }
                .padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

/// A chat-bubble shape with one squared-off tail corner (leading = assistant,
/// trailing = user), matching the Android `RoundedCornerShape(14,14,14,4)` bubbles.
private struct BubbleShape: Shape {
    enum Tail { case leading, trailing }
    let tail: Tail

    func path(in rect: CGRect) -> Path {
        let r: CGFloat = 14
        let tail: CGFloat = 4
        let bottomLeading = self.tail == .leading ? tail : r
        let bottomTrailing = self.tail == .trailing ? tail : r
        return Path(roundedRect: rect, cornerRadii: RectangleCornerRadii(
            topLeading: r,
            bottomLeading: bottomLeading,
            bottomTrailing: bottomTrailing,
            topTrailing: r
        ))
    }
}
