import SwiftUI

/// Nutrition today view (IMPL-IOS-01 Phase 2A stub).
///
/// Parity target (Android): NutritionTodayRoute + capture (camera + barcode/OCR,
/// NutritionCaptureRoute), targets (NutritionTargetRoute), entry edit/portion,
/// adjust-with-AI and leftover flows, saved meals/relog, serving hints (Wave C).
/// Shared ViewModel (Phase 1D): NutritionTodayViewModel (+ capture/adjust VMs).
struct NutritionTodayView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Meal log, capture, targets land in Wave C.")
                    .font(.hfBodyMd)
                    .foregroundStyle(Theme.textSecondary)
            }
            .padding()
            .formMaxWidth()
        }
        .background(Theme.canvas)
        .navigationTitle("Nutrition")
    }
}
