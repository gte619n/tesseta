import Testing
import Foundation
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave E1+E2 — pure formatting/charting helpers for the
/// Blood and Body-composition verticals. Plain Swift, no SharedCore dependency, so
/// these run today (no XCFramework gate) and pin the display rules that keep the
/// iOS UI at parity with the Android formatters.
@Suite("Blood + body-composition formatting")
struct BloodBodyCompFormatTests {

    // MARK: Blood

    @Test("marker value keeps a decimal for small values, rounds larger ones")
    func markerValue() {
        // Small values (HbA1c, hs-CRP) keep one decimal.
        #expect(BloodFormat.markerValue(5.7, unit: "%") == "5.7 %")
        #expect(BloodFormat.markerValue(0.8, unit: "mg/L") == "0.8 mg/L")
        // Larger values (cholesterol) round to whole numbers.
        #expect(BloodFormat.markerValue(180.4, unit: "mg/dL") == "180 mg/dL")
        #expect(BloodFormat.markerValue(199.6, unit: "mg/dL") == "200 mg/dL")
        // Nil → em dash; empty unit omits the suffix.
        #expect(BloodFormat.markerValue(nil, unit: "mg/dL") == "—")
        #expect(BloodFormat.markerValue(42, unit: "") == "42")
    }

    @Test("short date formats, nil reads as em dash")
    func shortDate() {
        var comps = DateComponents()
        comps.year = 2026; comps.month = 9; comps.day = 1
        let date = Calendar(identifier: .gregorian).date(from: comps)!
        #expect(BloodFormat.shortDate(date) == "Sep 1, 2026")
        #expect(BloodFormat.shortDate(nil) == "—")
    }

    @Test("marker display metadata matches the shared MarkerCatalog")
    func markerCatalog() {
        #expect(BloodMarker.apoB.displayName == "ApoB")
        #expect(BloodMarker.hsCrp.displayName == "hs-CRP")
        #expect(BloodMarker.testosterone.target == "300–1000 ng/dL (men)")
    }

    // MARK: Body composition

    @Test("weight converts kg → the user's unit with one decimal")
    func weightConversion() {
        #expect(BodyCompositionFormat.weight(80.0, unit: .kilograms) == "80.0 kg")
        // 80 kg → 176.4 lb.
        #expect(BodyCompositionFormat.weight(80.0, unit: .pounds) == "176.4 lb")
        #expect(BodyCompositionFormat.weight(nil, unit: .pounds) == "—")
    }

    @Test("delta carries an explicit sign and converts units")
    func deltaSign() {
        // A loss reads with a minus (U+2212), a gain with a plus.
        #expect(BodyCompositionFormat.delta(-1.0, unit: .kilograms) == "−1.0 kg")
        #expect(BodyCompositionFormat.delta(0.5, unit: .kilograms) == "+0.5 kg")
        #expect(BodyCompositionFormat.delta(0.0, unit: .kilograms) == "0.0 kg")
        #expect(BodyCompositionFormat.delta(nil, unit: .pounds) == "—")
    }

    @Test("percent and pounds helpers round to one decimal; nil → em dash")
    func percentAndPounds() {
        #expect(BodyCompositionFormat.percent(18.03) == "18.0%")
        #expect(BodyCompositionFormat.percent(nil) == "—")
        #expect(BodyCompositionFormat.pounds(180.2) == "180.2 lb")
        #expect(BodyCompositionFormat.pounds(nil) == "—")
    }

    @Test("kg→lb factor matches the shared Units constant")
    func kgToLbFactor() {
        #expect(BodyCompositionFormat.kgToLb == 2.20462)
    }
}
