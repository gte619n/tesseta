import SwiftUI
// import SharedCore  // MealAdjustViewModel, MealAdjustUiState, MealAdjustment, PendingNutritionOp — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C — the async "Adjust with AI" review sheet. Parity
/// target (Android): `AdjustReviewSheet` (+ the submit affordance in
/// `AdjustWithAiSection`). Backed by the SHARED `MealAdjustViewModel`.
///
/// One sheet, two modes off the shared op-lifecycle (`PendingNutritionOp`):
///   - `.pending`        → a working note ("Adjusting… you'll get a notification")
///   - `.pendingReview`  → the old→new proposal diff with Apply / Discard and the
///     "also save to source" toggle (pre-filled from the submit-time choice,
///     changeable here — sent as the commit override)
///   - `.pendingRetake`  → the rejected state; offer resubmitting a correction
///
/// This sheet is also the target of the FCM "adjust-review" notification deep link.
struct MealAdjustReviewView: View {

    let date: String
    let entry: Entry
    let adjustment: MealAdjustment?

    @Environment(\.dismiss) private var dismiss
    @State private var saveToSource = false
    @State private var instruction = ""
    @State private var saving = false

    private var op: PendingNutritionOp { adjustment?.op ?? .pending }
    private var proposal: AdjustProposal? { adjustment?.proposal }
    private var isSingleProduct: Bool { proposal?.packagedProduct == true && proposal?.items.count == 1 }
    private var canSaveToSource: Bool { proposal != nil && (!isSingleProduct || entry.foodId != nil) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(entry.foodName).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                    switch op {
                    case .pending:
                        Text("Adjusting… you'll get a notification when it's ready.")
                            .font(.hfBodyMd).foregroundStyle(Theme.textSecondary)
                    case .pendingReview:
                        proposalCard
                        if canSaveToSource { saveToSourceToggle }
                        actionRow
                    case .pendingRetake, .rejected:
                        Text("That adjustment couldn't be read. Describe the fix again.")
                            .font(.hfBodyMd).foregroundStyle(Theme.textSecondary)
                        resubmitField
                    case .applied:
                        Label("Applied", systemImage: "checkmark.circle.fill").foregroundStyle(Theme.good)
                    }
                }
                .padding()
                .formMaxWidth()
            }
            .background(Theme.canvas)
            .navigationTitle("Review adjustment")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
            .onAppear { saveToSource = adjustment?.saveAsMeal ?? false }
        }
    }

    @ViewBuilder private var proposalCard: some View {
        if let proposal {
            SettingsCard(title: "Proposed change") {
                Text(proposal.mealName).font(.hfHeadingSm)
                ForEach(proposal.items) { item in
                    Text("• \(item.name)\(portionTail(item))")
                        .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                }
                Text("Calories: \(NutritionFormat.wholeNumber(proposal.oldTotals.caloriesKcal ?? 0)) → \(NutritionFormat.wholeNumber(proposal.newTotals.caloriesKcal ?? 0)) kcal")
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
        }
    }

    private var saveToSourceToggle: some View {
        Toggle(isOn: $saveToSource) {
            Text(isSingleProduct ? "Also update the saved food so it's right next time"
                 : "Also save this meal so it's right next time")
                .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
        }
        .tint(Theme.accent)
        .disabled(saving)
    }

    private var actionRow: some View {
        HStack(spacing: 10) {
            Button("Discard") { saving = true /* vm.wrapped.discard() */ ; dismiss() }
                .buttonStyle(.bordered).frame(maxWidth: .infinity)
            Button(saving ? "Applying…" : "Apply") {
                saving = true
                // vm.wrapped.setSaveAsMeal(saveToSource); vm.wrapped.apply()
                dismiss()
            }
            .buttonStyle(.borderedProminent).tint(Theme.accent).frame(maxWidth: .infinity)
            .disabled(saving || proposal == nil)
        }
    }

    private var resubmitField: some View {
        VStack(alignment: .leading, spacing: 8) {
            TextField("e.g. that's pearl couscous, not lentils", text: $instruction, axis: .vertical)
                .textFieldStyle(.roundedBorder)
            Button("Adjust with AI") {
                // vm.wrapped.submit(instruction, saveToSource)
                dismiss()
            }
            .buttonStyle(.borderedProminent).tint(Theme.accent)
            .disabled(instruction.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func portionTail(_ item: AdjustItem) -> String {
        var parts: [String] = []
        if let g = item.servingGrams { parts.append("\(NutritionFormat.wholeNumber(g)) g") }
        if let k = item.macros?.caloriesKcal { parts.append("\(NutritionFormat.wholeNumber(k)) kcal") }
        return parts.isEmpty ? "" : "  " + parts.joined(separator: " · ")
    }
}
