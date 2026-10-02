import SwiftUI
import SharedCore

/// Goals list (IMPL-IOS-01 Phase 1C — networked shared screen).
///
/// Backed by the shared `GoalsListViewModel` over `GET /api/me/goals`. The
/// filter (Active/Completed/Archived) and the goal list are a pure function of
/// the shared `GoalsListUiState`, surfaced via `collectFlow`.
struct GoalsListView: View {
    private let vm: GoalsListViewModel
    @State private var state: GoalsListUiState
    @State private var subscription: FlowSubscription?

    init() {
        let model = IosComposition.shared.goalsListViewModel()
        self.vm = model
        _state = State(initialValue: model.state.value as! GoalsListUiState)
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                SegmentedChoice(
                    options: [(GoalsFilter.active, "Active"),
                              (GoalsFilter.completed, "Completed"),
                              (GoalsFilter.archived, "Archived")],
                    selection: Binding(
                        get: { state.filter },
                        set: { if let f = $0 { vm.setFilter(filter: f) } }))

                content
            }
            .formMaxWidth()
            .padding(16)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.canvas)
        .navigationTitle("Goals")
        .accessibilityIdentifier("goals-list")  // IMPL-E2E-01 shared id
        .onAppear {
            subscription = IosComposition.shared.collectFlow(flow: vm.state) { value in
                if let s = value as? GoalsListUiState { state = s }
            }
        }
        .onDisappear { subscription?.cancel() }
    }

    @ViewBuilder private var content: some View {
        if let error = state.error {
            message("Couldn't load goals", error, retry: true)
        } else if state.goals.isEmpty {
            if state.loading {
                ProgressView().controlSize(.large).tint(Theme.accent).padding(.top, 40)
            } else {
                message("No \(state.filter.label.lowercased()) goals", "", retry: false)
            }
        } else {
            SettingsCard(title: state.filter.label) {
                ForEach(state.goals, id: \.goalId) { goal in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(goal.title).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                        if let desc = goal.description_, !desc.isEmpty {
                            Text(desc).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                                .lineLimit(2)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 6)
                }
            }
        }
    }

    private func message(_ title: String, _ body: String, retry: Bool) -> some View {
        VStack(spacing: 16) {
            Text(title).font(.hfHeadingSm).foregroundStyle(Theme.textPrimary)
            if !body.isEmpty {
                Text(body).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if retry {
                Button("Retry") { vm.refresh() }
                    .buttonStyle(.borderedProminent).tint(Theme.accent)
            }
        }
        .padding(32)
    }
}
