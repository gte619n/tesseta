import SwiftUI
// import SharedCore  // WorkoutDesignerViewModel, WorkoutDesignerUiState, DesignerMessage,
//                    // ProgramProposal, GymOption, GoalOption — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave D(iii) — the AI program-designer chat.
/// Parity target (Android): `feature-workouts/.../program/chat/WorkoutDesignerScreen.kt`
/// + `WorkoutDesignerViewModel`. Observes the SHARED
/// `WorkoutDesignerViewModel` (shared/.../presentation/workouts/WorkoutDesignerViewModel.kt)
/// through `ObservableViewModel` — the view is a pure function of the shared UI
/// state; the SSE fold + commit logic live in the shared VM.
///
/// The flow: a setup form (training days + a gym per day + an optional goal),
/// then a streaming conversation. Assistant text renders as markdown via
/// `AttributedString(markdown:)` and grows as tokens land; a `proposal` event
/// attaches an editable program card the user can commit or discard.
struct WorkoutDesignerView: View {

    // MARK: Local mirrors of the shared UI state (deleted post-0D; the view then
    // switches directly on the SKIE-bridged `WorkoutDesignerUiState`).

    struct GymOption: Identifiable, Hashable { let id: String; let name: String }
    struct GoalOption: Identifiable, Hashable { let id: String; let title: String }

    enum Weekday: String, CaseIterable, Identifiable {
        case sun = "Sun", mon = "Mon", tue = "Tue", wed = "Wed", thu = "Thu", fri = "Fri", sat = "Sat"
        var id: String { rawValue }
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

    struct ProposalCard {
        let title: String
        let phaseCount: Int
        let dayCount: Int
        let warnings: [String]
        let issues: [String]
    }

    enum Message: Identifiable {
        case user(id: String, text: String)
        case assistant(id: String, text: String, streaming: Bool, proposal: ProposalCard?)
        var id: String {
            switch self {
            case .user(let id, _), .assistant(let id, _, _, _): return id
            }
        }
    }

    @State private var setup = SetupState()
    @State private var started = false
    @State private var messages: [Message] = []
    @State private var streaming = false
    @State private var error: String?
    @State private var draft = ""

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("AI Designer")
            .navigationBarTitleDisplayMode(.inline)
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(WorkoutDesignerViewModel(...))
        //     await vm.observe(vm.wrapped.state) { self.apply($0) }
        // }
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
                            .onTapGesture { setup.goalId = setup.goalId == goal.id ? nil : goal.id }
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
                set: { on in
                    if on {
                        setup.trainingDays.insert(day)
                        if setup.dayLocations[day] == nil { setup.dayLocations[day] = setup.gyms.first?.id }
                    } else {
                        setup.trainingDays.remove(day)
                        setup.dayLocations[day] = nil
                    }
                }
            )) {
                Text(day.rawValue).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
            }
            .tint(Theme.accent)

            if selected && setup.gyms.count > 1 {
                SegmentedChoice(
                    options: setup.gyms.map { (value: $0.id, label: $0.name) },
                    selection: Binding(
                        get: { setup.dayLocations[day] },
                        set: { setup.dayLocations[day] = $0 }
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
                Text(markdown(text))
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textPrimary)
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

    private func proposalCardView(_ card: ProposalCard) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(card.title).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            Text("\(card.phaseCount) phase\(card.phaseCount == 1 ? "" : "s") · \(card.dayCount) training day\(card.dayCount == 1 ? "" : "s")")
                .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            ForEach(card.warnings, id: \.self) { warning in
                Label(warning, systemImage: "exclamationmark.triangle")
                    .font(.hfBodySm).foregroundStyle(Theme.warn)
            }
            ForEach(card.issues, id: \.self) { issue in
                Label(issue, systemImage: "xmark.octagon")
                    .font(.hfBodySm).foregroundStyle(Theme.alert)
            }
            HStack {
                Button("Save program") { /* vm.commit(messageId, edited) */ }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
                    .disabled(!card.issues.isEmpty)
                Button("Discard") { /* vm.discard(messageId) */ }
                    .buttonStyle(.bordered)
            }
        }
        .padding(12)
        .background(Theme.accentBg, in: RoundedRectangle(cornerRadius: 10))
    }

    // MARK: - Shared bits

    private func composer(placeholder: String, canSend: Bool) -> some View {
        HStack(spacing: 8) {
            TextField(placeholder, text: $draft, axis: .vertical)
                .textFieldStyle(.plain)
                .padding(10)
                .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 10))
            Button {
                // vm.send(draft); draft = ""
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

    /// iOS-native markdown for the streamed assistant text (falls back to plain).
    private func markdown(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text)) ?? AttributedString(text)
    }
}

#Preview {
    NavigationStack { WorkoutDesignerView() }
}
