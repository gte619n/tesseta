import SwiftUI

/// Goals list (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): goals list (Routes.GOALS_LIST), roadmap
/// (GoalRoadmapRoute), and goal chat (GoalsChatRoute — SSE + markdown) (Wave E3).
/// Platform work: SSE client (reused from the workouts designer chat, Wave D-iii).
/// Shared ViewModel (Phase 1D): GoalsListViewModel (+ GoalChatViewModel).
struct GoalsListView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Goals, roadmap, goal chat land in Wave E3.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Goals")
    }
}
