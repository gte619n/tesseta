import Testing
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave A1 — the Today dashboard's pure formatting helpers.
/// These mirror Android's `TodayCard`/`TodayWorkoutCard` formatting (calories,
/// macro grams, consumed-vs-target fraction, recap duration); they are plain
/// Swift with no SharedCore dependency, so this runs today (no XCFramework gate).
@Suite("Today dashlet formatting")
struct TodayDashletFormattingTests {

    @Test("calories are group-separated and rounded")
    func caloriesFormat() {
        #expect(DashboardFormat.calories(1247.0) == "1,247")
        #expect(DashboardFormat.calories(0) == "0")
        #expect(DashboardFormat.calories(1999.6) == "2,000")
    }

    @Test("macro grams round; nil reads as 0")
    func macroGrams() {
        #expect(DashboardFormat.macroGrams(90.4) == "90")
        #expect(DashboardFormat.macroGrams(90.6) == "91")
        #expect(DashboardFormat.macroGrams(nil) == "0")
    }

    @Test("macro fraction clamps to 0...1 and treats no target as empty")
    func macroFraction() {
        #expect(DashboardFormat.macroFraction(consumed: 100, target: 200) == 0.5)
        // Over target clamps to 1.
        #expect(DashboardFormat.macroFraction(consumed: 300, target: 200) == 1)
        // No (positive) target → 0 rather than guessing / dividing by zero.
        #expect(DashboardFormat.macroFraction(consumed: 100, target: nil) == 0)
        #expect(DashboardFormat.macroFraction(consumed: 100, target: 0) == 0)
        // Missing consumed reads as 0 consumed.
        #expect(DashboardFormat.macroFraction(consumed: nil, target: 200) == 0)
    }

    @Test("workout recap duration formats minutes and hours")
    func durationLabel() {
        #expect(WorkoutDashlet.durationLabel(45 * 60) == "45m")
        #expect(WorkoutDashlet.durationLabel(65 * 60) == "1h 05m")
        #expect(WorkoutDashlet.durationLabel(nil) == "—")
        #expect(WorkoutDashlet.durationLabel(0) == "—")
    }
}
