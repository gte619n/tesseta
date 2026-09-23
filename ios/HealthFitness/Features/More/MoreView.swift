import SwiftUI

/// "More" feature directory (IMPL-IOS-01 Phase 2A).
///
/// Parity target (Android): MoreScreen — the phone-only directory that surfaces
/// the feature areas without a dedicated tab (Blood, Body, Goals, Settings). On
/// regular width these live directly in the split-view sidebar, so `MoreView`
/// is compact-only.
struct MoreView: View {
    var body: some View {
        List {
            ForEach(AppDestination.moreBucket) { dest in
                NavigationLink(value: dest) {
                    Label(dest.title, systemImage: dest.systemImage)
                        .font(.hfBodyMd)
                }
            }
        }
        .navigationTitle("More")
        .navigationDestination(for: AppDestination.self) { dest in
            dest.screen
        }
    }
}
