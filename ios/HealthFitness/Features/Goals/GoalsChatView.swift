import SwiftUI
// import SharedCore  // GoalsChatViewModel, GoalsChatUiState, ChatMessage, GoalProposal — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals). Parity target: Android
/// `feature-goals/.../GoalsChatScreen` + `GoalsChatViewModel` + the reusable
/// `core-chat/ChatThread` composable. A streaming SSE chat: the user sends a
/// prompt, the shared VM opens the SSE stream and folds assistant tokens into a
/// growing bubble rendered as markdown (native `AttributedString(markdown:)`,
/// see ChatMarkdown), and an attached AI goal proposal renders an editable card.
///
/// The shared `GoalsChatViewModel` OWNS the stream + `send` intent; this view is
/// a pure function of its `GoalsChatUiState` (bridged through `ObservableViewModel`).
struct GoalsChatView: View {

    // MARK: Local mirrors (deleted post-0D — the view switches on the SKIE-
    // bridged `ChatMessage` sealed type directly).

    enum Role { case user, assistant }

    struct Message: Identifiable, Hashable {
        let id: String
        let role: Role
        var text: String
        var streaming: Bool
        var hasProposal: Bool
    }

    struct ScreenState {
        var messages: [Message] = []
        var streaming: Bool = false
        var error: String?
    }

    @State private var state = ScreenState()
    @State private var draft: String = ""
    @FocusState private var composerFocused: Bool

    /// Empty-state prompts (mirror `GoalChatScope.suggestedPrompts` in the shared VM).
    private let suggestedPrompts = [
        "Help me build a plan to get my ApoB into optimal range",
        "Plan a 12-week strength base",
        "I want to improve my sleep score — design a roadmap",
    ]

    var body: some View {
        VStack(spacing: 0) {
            transcript
            Divider()
            composer
        }
        .background(Theme.canvas)
        .navigationTitle("Plan a goal")
        .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(GoalsChatViewModel(sse: DI.sseClient, ...))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
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
                            MessageBubble(message: message)
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
                // Keep the newest tokens in view as the stream grows.
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
        // Post-0D: vm.wrapped.send(message: text)
        _ = text
    }
}

// MARK: - Bubbles

private struct MessageBubble: View {
    let message: GoalsChatView.Message

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
                        // Native markdown rendering of the streamed assistant text.
                        Text(ChatMarkdown.attributed(message.text))
                            .font(.hfBodyMd)
                            .foregroundStyle(Theme.textPrimary)
                            .padding(.horizontal, 13)
                            .padding(.vertical, 9)
                            .background(Theme.surface, in: BubbleShape(tail: .leading))
                        Spacer(minLength: 40)
                    }
                }
                if message.hasProposal {
                    ProposalCardPlaceholder()
                }
            }
        }
    }
}

/// Three-dot typing indicator shown while the assistant bubble is empty + streaming.
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

/// Placeholder for the editable AI goal-proposal card. Post-0D this reads the
/// bridged `GoalProposal` off the assistant message and renders the full
/// editable form (title/domain/target date + phases/steps + metric bindings)
/// with inline `validationError` flags, then commits through `vm.commit(...)`.
private struct ProposalCardPlaceholder: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Label("Drafted roadmap", systemImage: "target")
                .font(.hfHeadingSm)
                .foregroundStyle(Theme.textPrimary)
            Text("Review and edit the phases, then save to create this goal.")
                .font(.hfBodySm)
                .foregroundStyle(Theme.textSecondary)
            Button("Save goal") {}
                .buttonStyle(.borderedProminent)
                .tint(Theme.accent)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 12))
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
        // The bottom tail corner is squared on the sending side.
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
