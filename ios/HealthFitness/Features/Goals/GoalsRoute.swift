import Foundation

/// Navigation routes within the Goals feature (IMPL-IOS-01). Extracted from the
/// old GoalsListView stub when the list was wired to the shared
/// GoalsListViewModel (Phase 1C).
enum GoalsRoute: Hashable {
    case roadmap(String)   // GoalRoadmapView — deep goal (phases + steps)
    case chat              // GoalsChatView — SSE + markdown proposal chat
}
