import Foundation

/// IMPL-IOS-01 Phase 3 Wave A2 — the Settings surface's pure formatting helpers.
/// Plain Swift with no SharedCore dependency (runs today, no XCFramework gate) —
/// the display-side analog of the shared `trimNumber` + `HeightMetric`
/// conversions, kept here so the SwiftUI views stay declarative.
enum SettingsFormat {

    /// Format a Double without a trailing ".0" — mirror of the shared Kotlin
    /// `trimNumber` used across the drink editor's numeric readouts.
    static func trimNumber(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(value)
    }

    /// Height label honoring the user's unit preference. cm ⇒ "178 cm";
    /// ft/in ⇒ "5′ 10″". Mirrors Android's HeightMetric display + the ft/in
    /// normalization (inches always 0…11). nil ⇒ "Not set".
    static func heightLabel(cm: Int?, useCentimeters: Bool) -> String {
        guard let cm else { return "Not set" }
        if useCentimeters { return "\(cm) cm" }
        let ftIn = ftIn(fromCm: cm)
        return "\(ftIn.feet)′ \(ftIn.inches)″"
    }

    /// Standard-drinks readout (one decimal), e.g. 1.4 ⇒ "1.4 std". nil ⇒ "".
    static func standardDrinks(_ value: Double?) -> String {
        guard let value else { return "" }
        return String(format: "%.1f std", value)
    }

    /// Biological-sex display label from the wire value ("MALE"/"FEMALE"/nil).
    static func biologicalSexLabel(_ value: String?) -> String {
        switch value {
        case "MALE": return "Male"
        case "FEMALE": return "Female"
        default: return "Not set"
        }
    }

    // MARK: HeightMetric parity (android/core-domain/.../profile/HeightMetric.kt)

    static let cmPerInch = 2.54
    static let inchesPerFoot = 12

    struct FtIn: Equatable { let feet: Int; let inches: Int }

    /// cm → ft/in, rounding to the nearest whole inch then normalizing so inches
    /// is always 0…11 (e.g. 72in ⇒ 6′0″, not 5′12″). 1:1 with Android's cmToFtIn.
    static func ftIn(fromCm cm: Int) -> FtIn {
        let totalInches = Int((Double(cm) / cmPerInch).rounded())
        return FtIn(feet: totalInches / inchesPerFoot, inches: totalInches % inchesPerFoot)
    }

    /// ft/in → cm, rounded to the nearest whole cm. 1:1 with Android's ftInToCm.
    static func cm(fromFeet feet: Int, inches: Int) -> Int {
        let totalInches = feet * inchesPerFoot + inches
        return Int((Double(totalInches) * cmPerInch).rounded())
    }
}
