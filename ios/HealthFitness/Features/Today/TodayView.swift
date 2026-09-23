import SwiftUI

/// Today / Dashboard hub (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): PhoneTodayScreen + the dashlet grid
/// (DashboardRoot). Aggregates cross-feature "today" cards — doses due, nutrition
/// totals, next workout, latest metrics — each linking into its feature area.
/// Shared ViewModel (Phase 1D): DashboardViewModel.
struct TodayView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Dashlets land in Wave A1.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Today")
    }
}
