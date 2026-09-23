import Foundation
// import SharedCore  // BodyCompositionSnapshot, DexaScan(Summary), DexaRegion, WeightUnit — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave E2 — LOCAL mirrors of the shared body-composition
/// domain types + the unit preference. Post-0D these are deleted and the views
/// bind to the SKIE-bridged Kotlin types. Field names/units track
/// shared/.../domain/bodycomposition/Models.kt 1:1 so the swap is mechanical.

/// Mirrors `SharedCore.WeightUnit`.
enum WeightUnit { case pounds, kilograms }

/// A weight point for the 90-day trend chart — mirrors `BodyCompositionPoint`
/// (metric == WEIGHT_KG), value carried already unit-projected by the view.
struct WeightPoint: Identifiable, Hashable {
    let date: Date
    let valueKg: Double
    var id: Date { date }
}

/// Overview snapshot — mirrors `BodyCompositionSnapshot`. Values are in kg (the
/// shared canonical unit); the view projects to the user's `WeightUnit`.
struct BodyCompositionSnapshot: Hashable {
    let latestWeightKg: Double?
    let latestBodyFatPercent: Double?
    let latestLeanMassKg: Double?
    let latestBmi: Double?
    let sevenDayDeltaKg: Double?
    let ninetyDayDeltaKg: Double?
    let series90d: [WeightPoint]
}

/// A DEXA scan grid summary — mirrors `DexaScanSummary`.
struct DexaScanSummary: Identifiable, Hashable {
    let scanId: String
    let measuredOn: Date?
    let sourceFacility: String?
    let totalMassLb: Double?
    let totalBodyFatPercent: Double?
    var id: String { scanId }
}

/// A per-region DEXA breakdown — mirrors `DexaRegion`.
struct DexaRegion: Hashable {
    let totalMassLb: Double?
    let leanTissueLb: Double?
    let fatTissueLb: Double?
    let regionFatPercent: Double?
}

/// The nine DEXA regions — mirrors `DexaRegionKey` (with the backend path keys).
enum DexaRegionKey: String, CaseIterable, Identifiable {
    case trunk, android, gynoid
    case armsTotal, armsRight, armsLeft
    case legsTotal, legsRight, legsLeft
    var id: String { rawValue }

    var label: String {
        switch self {
        case .trunk: return "Trunk"
        case .android: return "Android"
        case .gynoid: return "Gynoid"
        case .armsTotal: return "Arms (total)"
        case .armsRight: return "Right arm"
        case .armsLeft: return "Left arm"
        case .legsTotal: return "Legs (total)"
        case .legsRight: return "Right leg"
        case .legsLeft: return "Left leg"
        }
    }
}

/// Full DEXA scan detail — mirrors `DexaScan`.
struct DexaScan: Hashable {
    let scanId: String
    let measuredOn: Date?
    let sourceFacility: String?
    let totalMassLb: Double?
    let leanTissueLb: Double?
    let fatTissueLb: Double?
    let totalBodyFatPercent: Double?
    let visceralFatLb: Double?
    let androidGynoidRatio: Double?
    let bmdTScore: Double?
    let bmdZScore: Double?
    let regions: [DexaRegionKey: DexaRegion]
}

/// Pure formatting/conversion helpers for the body-comp UI. Free of SharedCore so
/// the Swift Testing suite exercises them today (see BodyCompositionFormatTests).
enum BodyCompositionFormat {
    static let kgToLb = 2.20462

    /// Weight in the user's unit, one decimal + suffix; "—" when nil.
    static func weight(_ kg: Double?, unit: WeightUnit) -> String {
        guard let kg else { return "—" }
        switch unit {
        case .pounds: return String(format: "%.1f lb", kg * kgToLb)
        case .kilograms: return String(format: "%.1f kg", kg)
        }
    }

    /// A signed delta with an explicit +/− and unit; "—" when nil. Zero reads "0.0".
    static func delta(_ kg: Double?, unit: WeightUnit) -> String {
        guard let kg else { return "—" }
        let converted = unit == .pounds ? kg * kgToLb : kg
        let suffix = unit == .pounds ? "lb" : "kg"
        let sign = converted > 0 ? "+" : (converted < 0 ? "−" : "")
        return "\(sign)\(String(format: "%.1f", abs(converted))) \(suffix)"
    }

    /// A percent value, one decimal + "%"; "—" when nil.
    static func percent(_ value: Double?) -> String {
        guard let value else { return "—" }
        return String(format: "%.1f%%", value)
    }

    /// A pounds value, one decimal + "lb"; "—" when nil. (DEXA is authored in lb.)
    static func pounds(_ lb: Double?) -> String {
        guard let lb else { return "—" }
        return String(format: "%.1f lb", lb)
    }
}
