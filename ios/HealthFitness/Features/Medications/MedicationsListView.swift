import SwiftUI

/// Medications list (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): the medications graph — list/add/detail
/// (MedicationRoutes.LIST), reminder settings, and today's doses (Wave B).
/// Platform work: D9 pre-scheduled local notifications (calendar triggers,
/// Take/Snooze/Dismiss categories, dose-checklist deep link, midnight replan).
/// Shared ViewModel (Phase 1D): MedicationsViewModel (+ the shared ReminderEngine
/// planning logic; only delivery is platform).
struct MedicationsListView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Medications & reminders land in Wave B.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Medications")
    }
}
