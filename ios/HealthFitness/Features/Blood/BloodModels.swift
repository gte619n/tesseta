import Foundation
// import SharedCore  // BloodMarker, LatestMarker, BloodTestReport, MarkerHistoryPoint — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E1 — LOCAL mirrors of the shared blood domain +
/// presentation types. Post-0D these are deleted and the views bind directly to
/// the SKIE-bridged Kotlin types (`SharedCore.BloodMarker`,
/// `SharedCore.LatestMarker`, the sealed `UiState` enums, etc.). Mirrored here so
/// the vertical compiles and renders pre-XCFramework — same shape/field names as
/// shared/.../domain/blood/BloodModels.kt so the eventual swap is mechanical.

/// Mirrors `SharedCore.BloodMarker`.
enum BloodMarker: String, CaseIterable, Identifiable, Hashable {
    case testosterone = "TESTOSTERONE"
    case totalCholesterol = "TOTAL_CHOLESTEROL"
    case ldl = "LDL"
    case hdl = "HDL"
    case triglycerides = "TRIGLYCERIDES"
    case apoB = "APO_B"
    case hba1c = "HBA1C"
    case fastingGlucose = "FASTING_GLUCOSE"
    case hsCrp = "HS_CRP"

    var id: String { rawValue }

    /// Mirrors `MarkerCatalog.displayName` (shared/.../domain/blood/MarkerCatalog.kt).
    var displayName: String {
        switch self {
        case .totalCholesterol: return "Total cholesterol"
        case .ldl: return "LDL"
        case .hdl: return "HDL"
        case .triglycerides: return "Triglycerides"
        case .apoB: return "ApoB"
        case .hba1c: return "HbA1c"
        case .fastingGlucose: return "Fasting glucose"
        case .hsCrp: return "hs-CRP"
        case .testosterone: return "Testosterone"
        }
    }

    /// Mirrors `MarkerCatalog.target`.
    var target: String {
        switch self {
        case .totalCholesterol: return "Below 200 mg/dL"
        case .ldl: return "Below 100 mg/dL (lower if high-risk)"
        case .hdl: return "Above 40 mg/dL (men) / 50 mg/dL (women)"
        case .triglycerides: return "Below 150 mg/dL"
        case .apoB: return "Below 90 mg/dL"
        case .hba1c: return "Below 5.7%"
        case .fastingGlucose: return "70–99 mg/dL"
        case .hsCrp: return "Below 1.0 mg/L"
        case .testosterone: return "300–1000 ng/dL (men)"
        }
    }

    /// Mirrors `MarkerCatalog.description`.
    var info: String {
        switch self {
        case .totalCholesterol: return "Sum of all cholesterol in your blood; a broad lipid-panel screen."
        case .ldl: return "\"Bad\" cholesterol; the primary driver of atherosclerotic plaque."
        case .hdl: return "\"Good\" cholesterol; helps clear LDL from the bloodstream."
        case .triglycerides: return "Blood fats reflecting recent carbohydrate and alcohol intake."
        case .apoB: return "Count of atherogenic particles; a sharper cardiovascular risk marker than LDL."
        case .hba1c: return "Average blood glucose over the past ~3 months."
        case .fastingGlucose: return "Blood sugar after an overnight fast; an early diabetes screen."
        case .hsCrp: return "High-sensitivity inflammation marker linked to cardiovascular risk."
        case .testosterone: return "Primary male sex hormone. Affects muscle mass, bone density, and energy levels."
        }
    }
}

/// One point in a marker's 12-month history — mirrors `MarkerHistoryPoint`.
struct MarkerHistoryPoint: Identifiable, Hashable {
    let date: Date
    let value: Double
    let isLab: Bool
    var id: Date { date }
}

/// A row in the marker readings table — mirrors `MarkerDetailViewModel.HistoryRow`.
struct MarkerHistoryRow: Identifiable, Hashable {
    let date: Date
    let value: Double
    let unit: String
    let sourceLabel: String
    var id: Date { date }
}

/// The combined per-marker latest view — mirrors `LatestMarker`.
struct LatestMarker: Identifiable, Hashable {
    enum Source { case manual, lab, none }
    let marker: BloodMarker
    let value: Double?
    let unit: String
    let sampleDate: Date?
    let source: Source
    let history: [MarkerHistoryPoint]
    var id: String { marker.rawValue }
}

/// An extracted lab-report marker row — mirrors `ExtractedMarker`.
struct ExtractedMarker: Identifiable, Hashable {
    enum Flag: String { case high = "H", low = "L" }
    let name: String
    let value: Double?
    let unit: String?
    let flag: Flag?
    var id: String { name }
}

/// A lab report — mirrors `BloodTestReport`.
struct BloodTestReport: Identifiable, Hashable {
    let reportId: String
    let sampleDate: Date?
    let labSource: String
    let markers: [ExtractedMarker]
    let pdfDownloadPath: String
    var id: String { reportId }
}

/// Pure formatting helpers for the blood UI. Kept free of SharedCore so the Swift
/// Testing suite exercises them today (see BloodFormatTests).
enum BloodFormat {
    /// A marker value rounded to a sensible precision with its unit; "—" when nil.
    /// Small values (< 10, e.g. HbA1c 5.7 or hs-CRP 0.8) keep one decimal;
    /// larger values (cholesterol 180) render as whole numbers.
    static func markerValue(_ value: Double?, unit: String) -> String {
        guard let value else { return "—" }
        let number: String
        if abs(value) < 10 {
            number = String(format: "%.1f", value)
        } else {
            number = String(Int(value.rounded()))
        }
        return unit.isEmpty ? number : "\(number) \(unit)"
    }

    /// Short medium date, e.g. "Sep 1, 2026"; "—" when nil.
    static func shortDate(_ date: Date?) -> String {
        guard let date else { return "—" }
        let fmt = DateFormatter()
        fmt.dateFormat = "MMM d, yyyy"
        return fmt.string(from: date)
    }
}
