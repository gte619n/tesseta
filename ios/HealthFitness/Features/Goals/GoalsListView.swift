import SwiftUI
// import SharedCore  // GoalsListViewModel, GoalsListUiState, Goal, GoalsFilter — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals). Parity target: Android
/// `feature-goals/.../GoalsListScreen` + `GoalsListViewModel`. Observes the
/// SHARED `GoalsListViewModel` (shared/.../presentation/goals/GoalsListViewModel.kt)
/// through the `ObservableViewModel` bridge — filter chips + list are a pure
/// function of the shared UI state.
///
/// The pattern is the medications reference vertical (MedicationsListView):
///   1. `@State var vm = ObservableViewModel(GoalsListViewModel(repo))`
///   2. a local `@State` mirror of the shared `GoalsListUiState`
///   3. `.task { await vm.observe(vm.wrapped.state) { state = map($0) } }`
struct GoalsListView: View {

    // MARK: Local mirrors (deleted post-0D; the view switches on the SKIE-
    // bridged shared types directly).

    enum Filter: String, CaseIterable, Identifiable {
        case active = "Active", completed = "Completed", archived = "Archived"
        var id: String { rawValue }
    }

    struct GoalRow: Identifiable, Hashable {
        let id: String
        let title: String
        let domainLabel: String
        let subtitle: String?
    }

    struct ScreenState {
        var loading: Bool = true
        var goals: [GoalRow] = []
        var error: String?
    }

    @State private var state = ScreenState()
    @State private var filter: Filter = .active

    var body: some View {
        content
            .background(Theme.canvas)
            .navigationTitle("Goals")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    NavigationLink(value: GoalsRoute.chat) {
                        Image(systemName: "sparkles")
                    }
                    .accessibilityLabel("Plan a goal")
                }
            }
            .navigationDestination(for: GoalsRoute.self) { route in
                switch route {
                case .roadmap(let id): GoalRoadmapView(goalId: id)
                case .chat: GoalsChatView()
                }
            }
        // Post-0D:
        // .task {
        //     let vm = ObservableViewModel(GoalsListViewModel(repo: DI.goalsRepository))
        //     await vm.observe(vm.wrapped.state) { self.state = Self.map($0) }
        // }
    }

    @ViewBuilder
    private var content: some View {
        if state.loading {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        } else if let error = state.error {
            ContentUnavailableView("Couldn’t load goals", systemImage: "target",
                                   description: Text(error))
        } else {
            List {
                Picker("Filter", selection: $filter) {
                    ForEach(Filter.allCases) { Text($0.rawValue).tag($0) }
                }
                .pickerStyle(.segmented)
                .listRowSeparator(.hidden)
                // Post-0D: .onChange(of: filter) { vm.wrapped.setFilter(map(filter)) }

                if state.goals.isEmpty {
                    Section {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("No \(filter.rawValue.lowercased()) goals yet")
                                .font(.hfHeadingSm)
                                .foregroundStyle(Theme.textPrimary)
                            Text("Tap ✦ to plan a phased roadmap with the AI coach.")
                                .font(.hfBodySm)
                                .foregroundStyle(Theme.textSecondary)
                        }
                        .padding(.vertical, 8)
                    }
                    .listRowBackground(Color.clear)
                } else {
                    Section {
                        ForEach(state.goals) { goal in
                            NavigationLink(value: GoalsRoute.roadmap(goal.id)) {
                                GoalCard(goal: goal)
                            }
                        }
                    }
                }
            }
            .formMaxWidth()
        }
    }

    // static func map(_ s: GoalsListUiState) -> ScreenState { ... }  // Phase 0D
}

/// A single goal row — domain caps-label over the title + optional subtitle.
private struct GoalCard: View {
    let goal: GoalsListView.GoalRow

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(goal.domainLabel.uppercased())
                .font(.hfCapsSm)
                .foregroundStyle(Theme.textTertiary)
            Text(goal.title)
                .font(.hfBodyLg)
                .foregroundStyle(Theme.textPrimary)
            if let subtitle = goal.subtitle {
                Text(subtitle)
                    .font(.hfBodySm)
                    .foregroundStyle(Theme.textSecondary)
            }
        }
        .padding(.vertical, 2)
    }
}

/// Navigation destinations under the Goals tab.
enum GoalsRoute: Hashable {
    case roadmap(String)   // GoalRoadmapView — deep goal (phases + steps)
    case chat              // GoalsChatView — SSE + markdown proposal chat
}
