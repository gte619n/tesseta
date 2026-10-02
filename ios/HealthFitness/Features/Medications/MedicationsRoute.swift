import Foundation

/// Navigation routes within the Medications feature (IMPL-IOS-01). Extracted from
/// the old MedicationsListView stub when the list was wired to the shared
/// MedicationsViewModel (Phase 1C); referenced by MedicationDetailView et al.
enum MedicationsRoute: Hashable {
    case add
    case detail(String)
    case reminderSettings         // ReminderSettingsView
    case todaysDoses              // TodaysDosesView (D9 dose-checklist deep-link target)
}
