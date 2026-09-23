import SwiftUI
// import SharedCore  // LeftoverViewModel, LeftoverUiState, Leftover, PendingNutritionOp — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C (IMPL-LEFTOVER-01) — the Remove-Leftovers review
/// sheet. Parity target (Android): `LeftoverReviewSheet`. Backed by the SHARED
/// `LeftoverViewModel`.
///
/// Off the shared op-lifecycle (`PendingNutritionOp`):
///   - `.pending`        → "Analyzing leftovers…" working note
///   - `.pendingReview`  → the Served→Ate diff (per-ingredient + totals) with
///     Apply / Discard
///   - `.applied`        → a "restore full portion" affordance (D15)
///   - `.pendingRetake`  → the rejected/unreadable state; offer a retake
///     (deep-links back into the leftover camera)
///
/// This sheet is the target of the FCM "leftover-review" / "retake" deep links.
struct LeftoverReviewView: View {

    let date: String
    let entry: Entry
    let leftover: Leftover?

    @Environment(\.dismiss) private var dismiss
    @State private var saving = false

    private var op: PendingNutritionOp { leftover?.op ?? .pending }
    private var proposal: LeftoverProposal? { leftover?.proposal }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(entry.foodName).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
                    switch op {
                    case .pending:
                        Text("Analyzing leftovers…").font(.hfBodyMd).foregroundStyle(Theme.textSecondary)
                    case .pendingReview:
                        diffCard
                        actionRow
                    case .applied:
                        Label("Leftovers applied — macros reflect what you ate.",
                              systemImage: "checkmark.circle.fill")
                            .font(.hfBodyMd).foregroundStyle(Theme.good)
                        Button("Restore full portion") { saving = true /* vm.wrapped.restoreFullPortion() */; dismiss() }
                            .buttonStyle(.bordered)
                    case .pendingRetake, .rejected:
                        Text("Couldn't read the leftovers photo.")
                            .font(.hfBodyMd).foregroundStyle(Theme.textSecondary)
                        NavigationLink("Retake photo",
                                       value: NutritionRoute.captureLeftover(date: date, entryId: entry.entryId))
                            .buttonStyle(.borderedProminent)
                    }
                }
                .padding()
                .formMaxWidth()
            }
            .background(Theme.canvas)
            .navigationTitle("Review leftovers")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        }
    }

    @ViewBuilder private var diffCard: some View {
        if let proposal {
            SettingsCard(title: "What you ate") {
                ForEach(proposal.items) { item in
                    HStack {
                        Text(item.name).font(.hfBodyMd)
                        Spacer()
                        Text("\(NutritionFormat.grams(item.consumedGrams)) of \(NutritionFormat.grams(item.servedGrams))")
                            .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                    }
                }
                Divider()
                HStack {
                    Text("Calories").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                    Spacer()
                    Text("\(NutritionFormat.wholeNumber(proposal.consumedTotals?.caloriesKcal ?? 0)) of \(NutritionFormat.wholeNumber(proposal.servedTotals?.caloriesKcal ?? 0)) kcal")
                        .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                }
                if let note = proposal.warningNote {
                    Text(note).font(.hfBodySm).foregroundStyle(Theme.warn)
                }
            }
        }
    }

    private var actionRow: some View {
        HStack(spacing: 10) {
            Button("Discard") { saving = true /* vm.wrapped.discard() */; dismiss() }
                .buttonStyle(.bordered).frame(maxWidth: .infinity)
            Button(saving ? "Applying…" : "Apply") { saving = true /* vm.wrapped.apply() */; dismiss() }
                .buttonStyle(.borderedProminent).tint(Theme.accent).frame(maxWidth: .infinity)
                .disabled(saving || proposal == nil)
        }
    }
}
