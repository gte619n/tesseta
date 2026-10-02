import Testing
import Foundation
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave D(iii) — pins the iOS render-side progression
/// formatting to the SHARED `ProgressionConsoleViewModel` math. Plain Swift, no
/// SharedCore dependency, so it runs today (no XCFramework gate). The
/// authoritative logic is the Kotlin VM; these expectations must match its
/// `ProgressionConsoleViewModelTest` (per-hand ×2, load-trend units).
@Suite("Progression console formatting")
struct ProgressionFormatTests {

    @Test("a dumbbell lift reports the total lifted, not the per-hand belief")
    func perHandDumbbellDoublesToTotal() {
        #expect(ProgressionFormat.isPerHand(name: "Dumbbell Bench Press"))
        #expect(ProgressionFormat.displayLoad(perHandE1rm: 45, name: "Dumbbell Bench Press") == "90 lb")
        #expect(ProgressionFormat.perHandCaption(perHandE1rm: 45, name: "Dumbbell Bench Press") == "2 × 45 lb / hand")
    }

    @Test("a dual-cable lift is per-hand too")
    func dualCableIsPerHand() {
        #expect(ProgressionFormat.isPerHand(name: "Dual Cable Fly"))
        #expect(ProgressionFormat.displayLoad(perHandE1rm: 30, name: "Dual Cable Fly") == "60 lb")
    }

    @Test("a barbell lift is not per-hand and reports its estimate as-is")
    func barbellIsNotPerHand() {
        #expect(!ProgressionFormat.isPerHand(name: "Barbell Back Squat"))
        #expect(ProgressionFormat.displayLoad(perHandE1rm: 315, name: "Barbell Back Squat") == "315 lb")
        #expect(ProgressionFormat.perHandCaption(perHandE1rm: 315, name: "Barbell Back Squat") == nil)
    }

    @Test("load trend is signed, per-week, and reads 'Holding' near zero")
    func loadTrendFormatting() {
        #expect(ProgressionFormat.loadTrend(driftPerDay: 0.1) == "+0.7 lb / week")
        #expect(ProgressionFormat.loadTrend(driftPerDay: -0.2) == "−1.4 lb / week")
        #expect(ProgressionFormat.loadTrend(driftPerDay: 0.0) == "Holding — no planned change")
    }
}
