import Testing
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave C — the Swift macro/portion formatting mirror. These
/// track the shared `NutritionFormat` (KMP `NutritionFormat.kt`) and the shared
/// `Macros.forPortion` math verbatim, so the iOS preview + rows show the SAME
/// numbers the backend/Android compute. Nutrition math is the XPLAT divergence
/// surface — these lock it.
@Suite("Nutrition formatting & portion math")
struct NutritionFormatTests {

    private let per100 = Macros(
        caloriesKcal: 200, proteinGrams: 10, carbsGrams: 20,
        fatGrams: 5, fiberGrams: 3, sugarGrams: 8)

    @Test("forPortion scales by grams × quantity / 100")
    func forPortionScales() {
        let out = per100.forPortion(servingGrams: 240, quantity: 1)
        #expect(out.caloriesKcal == 480)
        #expect(out.proteinGrams == 24)
        #expect(out.carbsGrams == 48)
        #expect(out.fatGrams == 12)
        // fiber/sugar are non-terminating binary fractions (3 × 2.4 = 7.1999…);
        // forPortion keeps full IEEE-754 precision to match Android's math, so
        // compare with a tolerance (display formatting is what rounds).
        #expect(abs((out.fiberGrams ?? .nan) - 7.2) < 0.0001)
        #expect(abs((out.sugarGrams ?? .nan) - 19.2) < 0.0001)
    }

    @Test("forPortion respects the 0.5× quantity step")
    func forPortionHalf() {
        let out = per100.forPortion(servingGrams: 100, quantity: 0.5)
        #expect(out.caloriesKcal == 100)
        #expect(out.proteinGrams == 5)
    }

    @Test("forPortion keeps nil nutrients nil")
    func forPortionNulls() {
        let sparse = Macros(caloriesKcal: 50)
        let out = sparse.forPortion(servingGrams: 200, quantity: 2)
        #expect(out.caloriesKcal == 200)
        #expect(out.proteinGrams == nil)
        #expect(out.fatGrams == nil)
    }

    @Test("grams/kcal round, add unit, and em-dash on nil")
    func formatUnits() {
        #expect(NutritionFormat.grams(23.6) == "24 g")
        #expect(NutritionFormat.grams(nil) == "—")
        #expect(NutritionFormat.kcal(480.4) == "480 kcal")
        #expect(NutritionFormat.kcal(nil) == "—")
    }

    @Test("large values are grouped with commas")
    func grouping() {
        #expect(NutritionFormat.kcal(2450) == "2,450 kcal")
        #expect(NutritionFormat.grams(1200) == "1,200 g")
        #expect(NutritionFormat.wholeNumber(1_000_000) == "1,000,000")
    }

    @Test("progressFraction clamps and handles missing target")
    func progress() {
        #expect(NutritionFormat.progressFraction(consumed: 100, target: 200) == 0.5)
        #expect(NutritionFormat.progressFraction(consumed: 300, target: 200) == 1)
        #expect(NutritionFormat.progressFraction(consumed: 100, target: nil) == nil)
        #expect(NutritionFormat.progressFraction(consumed: 100, target: 0) == nil)
    }

    @Test("remaining never goes negative")
    func remaining() {
        #expect(NutritionFormat.remaining(consumed: 150, target: 200) == 50)
        #expect(NutritionFormat.remaining(consumed: 300, target: 200) == 0)
        #expect(NutritionFormat.remaining(consumed: 100, target: nil) == nil)
    }

    @Test("isOver only true when consumed exceeds a positive target")
    func over() {
        #expect(NutritionFormat.isOver(consumed: 250, target: 200))
        #expect(!NutritionFormat.isOver(consumed: 150, target: 200))
        #expect(!NutritionFormat.isOver(consumed: 100, target: nil))
        #expect(!NutritionFormat.isOver(consumed: 100, target: 0))
    }

    @Test("nutrient rows read the right field and format with the right unit")
    func nutrientRows() {
        #expect(NutrientRow.calories.value(per100) == 200)
        #expect(NutrientRow.protein.value(per100) == 10)
        #expect(NutrientRow.protein.format(10) == "10 g")
        #expect(NutrientRow.calories.format(200) == "200 kcal")
    }

    @Test("Meal.forHour buckets the clock like Android")
    func mealForHour() {
        #expect(Meal.forHour(7) == .breakfast)
        #expect(Meal.forHour(12) == .lunch)
        #expect(Meal.forHour(19) == .dinner)
        #expect(Meal.forHour(23) == .snack)
        #expect(Meal.forHour(2) == .snack)
    }

    @Test("op lifecycle maps adjust/leftover carriers like the shared enum")
    func opLifecycle() {
        let adjusting = MealAdjustment(status: .adjusting)
        #expect(adjusting.op == .pending)
        let ready = MealAdjustment(status: .pendingReview, proposal: AdjustProposal(mealName: "Bowl"))
        #expect(ready.op == .pendingReview)
        let noProposal = MealAdjustment(status: .pendingReview, proposal: nil)
        #expect(noProposal.op == .pending)
        let rejected = Leftover(status: .rejected)
        #expect(rejected.op == .pendingRetake)
        let applied = Leftover(status: .applied)
        #expect(applied.op == .applied)
    }
}
