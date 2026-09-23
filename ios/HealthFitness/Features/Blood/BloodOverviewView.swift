import SwiftUI

/// Blood / labs overview (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): the blood graph — overview (BloodRoutes.OVERVIEW),
/// add reading, marker detail, lab report upload (Wave E1). Platform work:
/// document camera for lab report capture.
/// Shared ViewModel (Phase 1D): BloodOverviewViewModel.
struct BloodOverviewView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Blood markers & lab uploads land in Wave E1.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Blood")
    }
}
