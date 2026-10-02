import SwiftUI

/// IMPL-IOS-01 Phase 3 Wave C — the nutrition day components (parity: Android
/// `MacroProgress.kt` / `NutritionTodayComponents.kt`). Pure functions of their
/// inputs; the numbers come straight from the shared `NutritionFormat` mirror so
/// the calorie ring, nutrient bars, and row summaries read identically on both
/// clients.

// MARK: - Macro progress header

struct MacroProgressHeader: View {
    let totals: Macros
    let target: Macros?

    var body: some View {
        SettingsCard(title: "Today") {
            HStack(alignment: .center, spacing: 16) {
                CalorieRing(consumed: totals.caloriesKcal, target: target?.caloriesKcal)
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(NutrientRow.allCases.filter { $0 != .calories }) { row in
                        NutrientBar(row: row,
                                    consumed: row.value(totals),
                                    target: target.flatMap(row.value))
                    }
                }
            }
        }
    }
}

/// The calorie progress ring (mirrors the Android donut). Turns "alert" when over.
struct CalorieRing: View {
    let consumed: Double?
    let target: Double?

    private var fraction: Double { NutritionFormat.progressFraction(consumed: consumed, target: target) ?? 0 }
    private var over: Bool { NutritionFormat.isOver(consumed: consumed, target: target) }

    var body: some View {
        ZStack {
            Circle().stroke(Theme.borderDefault, lineWidth: 8)
            Circle()
                .trim(from: 0, to: fraction)
                .stroke(over ? Theme.alert : Theme.accent,
                        style: StrokeStyle(lineWidth: 8, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text(NutritionFormat.wholeNumber(consumed ?? 0)).font(.hfDisplayMd)
                if let target {
                    Text("of \(NutritionFormat.wholeNumber(target))")
                        .font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                }
            }
        }
        .frame(width: 96, height: 96)
        .accessibilityLabel("Calories \(NutritionFormat.kcal(consumed))")
    }
}

/// One nutrient's progress bar + "consumed / target" label.
struct NutrientBar: View {
    let row: NutrientRow
    let consumed: Double?
    let target: Double?

    private var fraction: Double { NutritionFormat.progressFraction(consumed: consumed, target: target) ?? 0 }
    private var over: Bool { NutritionFormat.isOver(consumed: consumed, target: target) }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack {
                Text(row.rawValue).font(.hfCapsSm).foregroundStyle(Theme.textSecondary)
                Spacer()
                Text(target == nil ? row.format(consumed)
                     : "\(NutritionFormat.wholeNumber(consumed ?? 0))/\(row.format(target))")
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule().fill(Theme.borderSubtle)
                    Capsule().fill(over ? Theme.alert : Theme.accent)
                        .frame(width: geo.size.width * fraction)
                }
            }
            .frame(height: 5)
        }
    }
}

// MARK: - Meal section

struct MealSection: View {
    let group: MealGroup
    let onTapEntry: (Entry) -> Void
    let onReviewAdjust: (Entry) -> Void
    let onReviewLeftover: (Entry) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(group.meal.capitalized).font(.hfHeadingSm)
                Spacer()
                Text(NutritionFormat.kcal(group.subtotal.caloriesKcal))
                    .font(.hfMonoSm).foregroundStyle(Theme.textTertiary)
            }
            ForEach(group.entries) { entry in
                NutritionEntryRow(entry: entry,
                                  onTap: { onTapEntry(entry) },
                                  onReviewAdjust: { onReviewAdjust(entry) },
                                  onReviewLeftover: { onReviewLeftover(entry) })
            }
        }
    }
}

/// One logged-food row. Shows a working state for synthetic/analyzing rows, and
/// the review affordances for a pending adjust/leftover.
struct NutritionEntryRow: View {
    let entry: Entry
    let onTap: () -> Void
    let onReviewAdjust: () -> Void
    let onReviewLeftover: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                thumbnail
                VStack(alignment: .leading, spacing: 2) {
                    Text(entry.foodName).font(.hfBodyMd).foregroundStyle(Theme.textPrimary)
                    if entry.isAnalyzing || entry.isPendingSynthetic {
                        Text("Analyzing…").font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    } else if let s = entry.servingLabel {
                        Text(s).font(.hfBodySm).foregroundStyle(Theme.textTertiary)
                    }
                }
                Spacer()
                Text(NutritionFormat.kcal(entry.macros.caloriesKcal))
                    .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
            }
            .contentShape(Rectangle())
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
        .disabled(entry.isPendingSynthetic)
    }

    @ViewBuilder private var thumbnail: some View {
        if entry.isAnalyzing || entry.isPendingSynthetic {
            ProgressView().frame(width: 36, height: 36)
        } else {
            Image(systemName: entry.isComposite ? "photo" : "fork.knife")
                .foregroundStyle(Theme.textTertiary)
                .frame(width: 36, height: 36)
                .background(Theme.canvasMuted, in: RoundedRectangle(cornerRadius: 8))
        }
    }
}
