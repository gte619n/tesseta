import Testing
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave A2 — the Settings surface's pure formatting +
/// unit-conversion helpers. Plain Swift (no SharedCore dependency), so this runs
/// today. The `ftIn`/`cm` conversions are the display-side parity of Android's
/// `HeightMetric`; `trimNumber` mirrors the shared Kotlin helper.
@Suite("Settings formatting")
struct SettingsFormatTests {

    @Test("trimNumber drops a trailing .0 but keeps real fractions")
    func trimNumber() {
        #expect(SettingsFormat.trimNumber(5) == "5")
        #expect(SettingsFormat.trimNumber(5.0) == "5")
        #expect(SettingsFormat.trimNumber(5.5) == "5.5")
        #expect(SettingsFormat.trimNumber(40) == "40")
    }

    @Test("height label honors the unit preference; nil reads as Not set")
    func heightLabel() {
        #expect(SettingsFormat.heightLabel(cm: 178, useCentimeters: true) == "178 cm")
        #expect(SettingsFormat.heightLabel(cm: 178, useCentimeters: false) == "5′ 10″")
        #expect(SettingsFormat.heightLabel(cm: nil, useCentimeters: false) == "Not set")
    }

    @Test("cm → ft/in rounds and normalizes inches to 0…11 (72in ⇒ 6′0″)")
    func cmToFtIn() {
        // 183 cm ≈ 72.05 in ⇒ 6′0″ (normalized, not 5′12″).
        #expect(SettingsFormat.ftIn(fromCm: 183) == SettingsFormat.FtIn(feet: 6, inches: 0))
        #expect(SettingsFormat.ftIn(fromCm: 178) == SettingsFormat.FtIn(feet: 5, inches: 10))
    }

    @Test("ft/in → cm matches Android's ftInToCm (6′2″ ⇒ 188)")
    func ftInToCm() {
        #expect(SettingsFormat.cm(fromFeet: 6, inches: 2) == 188)
        #expect(SettingsFormat.cm(fromFeet: 5, inches: 10) == 178)
    }

    @Test("standard-drinks readout keeps one decimal; nil is empty")
    func standardDrinks() {
        #expect(SettingsFormat.standardDrinks(1.4) == "1.4 std")
        #expect(SettingsFormat.standardDrinks(2) == "2.0 std")
        #expect(SettingsFormat.standardDrinks(nil) == "")
    }

    @Test("biological-sex label maps the wire value")
    func biologicalSex() {
        #expect(SettingsFormat.biologicalSexLabel("MALE") == "Male")
        #expect(SettingsFormat.biologicalSexLabel("FEMALE") == "Female")
        #expect(SettingsFormat.biologicalSexLabel(nil) == "Not set")
    }
}
