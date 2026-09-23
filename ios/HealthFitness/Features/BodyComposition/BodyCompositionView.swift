import SwiftUI

/// Body composition (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): the body-composition graph — trends
/// (BodyCompositionRoutes.BODY), DEXA detail, upload (Wave E2). Platform work:
/// PDF upload for DEXA reports.
/// Shared ViewModel (Phase 1D): BodyCompositionViewModel.
struct BodyCompositionView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Body-composition trends & DEXA land in Wave E2.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Body")
    }
}
