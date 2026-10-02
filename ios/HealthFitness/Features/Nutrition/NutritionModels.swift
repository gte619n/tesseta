import Foundation
// import SharedCore  // Macros, Entry, NutritionDay, Food, MealAdjustment, Leftover, … — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C — local Swift mirror of the shared KMP nutrition
/// types + pure helpers (shared/.../domain/nutrition/Nutrition.kt +
/// data/NutritionRepositories.kt + presentation/nutrition/NutritionFormat.kt).
///
/// `import SharedCore` is commented until the XCFramework is built (Phase 0D);
/// these mirror the Kotlin field names/logic 1:1 so the views compile and the
/// Swift Testing suite runs today. Post-0D these are deleted and the views bind
/// the SKIE-bridged Kotlin types directly. Nutrition math (`forPortion`,
/// formatting, the label heuristic) is the XPLAT divergence surface — kept
/// identical to the shared source, verified by NutritionFormatTests.

// MARK: - Domain

struct Macros: Equatable {
    var caloriesKcal: Double?
    var proteinGrams: Double?
    var carbsGrams: Double?
    var fatGrams: Double?
    var fiberGrams: Double?
    var sugarGrams: Double?

    init(caloriesKcal: Double? = nil, proteinGrams: Double? = nil, carbsGrams: Double? = nil,
         fatGrams: Double? = nil, fiberGrams: Double? = nil, sugarGrams: Double? = nil) {
        self.caloriesKcal = caloriesKcal; self.proteinGrams = proteinGrams
        self.carbsGrams = carbsGrams; self.fatGrams = fatGrams
        self.fiberGrams = fiberGrams; self.sugarGrams = sugarGrams
    }

    static let empty = Macros()

    /// macros = macrosPer100g × (servingGrams × quantity) / 100 — verbatim from
    /// the shared `Macros.forPortion`.
    func forPortion(servingGrams: Double, quantity: Double) -> Macros {
        let factor = (servingGrams * quantity) / 100.0
        func scale(_ v: Double?) -> Double? { v.map { $0 * factor } }
        return Macros(
            caloriesKcal: scale(caloriesKcal), proteinGrams: scale(proteinGrams),
            carbsGrams: scale(carbsGrams), fatGrams: scale(fatGrams),
            fiberGrams: scale(fiberGrams), sugarGrams: scale(sugarGrams)
        )
    }
}

struct ServingSize: Equatable { let label: String; let grams: Double }

struct EntryIngredient: Equatable, Identifiable {
    let name: String
    var foodId: String?
    var servingLabel: String?
    var servingGrams: Double?
    var quantity: Double?
    var macros: Macros
    var id: String { name }
}

struct Entry: Equatable, Identifiable {
    let entryId: String
    let meal: String
    var foodId: String?
    let foodName: String
    var servingLabel: String?
    var servingGrams: Double?
    var quantity: Double
    var macros: Macros
    var source: String
    var imageUrl: String?
    var imageStatus: String = "NONE"
    var analysisStatus: String = "NONE"
    var ingredients: [EntryIngredient]?

    var id: String { entryId }
    var isComposite: Bool { !(ingredients?.isEmpty ?? true) }
    var isAnalyzing: Bool { analysisStatus == "ANALYZING" }
    var isPendingSynthetic: Bool { entryId.hasPrefix(NutritionOp.pendingCapturePrefix) }
}

struct MealGroup: Equatable, Identifiable {
    let meal: String
    let subtotal: Macros
    var entries: [Entry] = []
    var id: String { meal }
}

struct NutritionDay: Equatable {
    let date: String
    let totals: Macros
    var target: Macros?
    var meals: [MealGroup] = []
}

enum Meal: String, CaseIterable {
    case breakfast = "BREAKFAST", lunch = "LUNCH", dinner = "DINNER", snack = "SNACK"
    var label: String {
        switch self {
        case .breakfast: return "Breakfast"; case .lunch: return "Lunch"
        case .dinner: return "Dinner"; case .snack: return "Snack"
        }
    }
    /// Meal.forHour — verbatim from the shared enum.
    static func forHour(_ hour: Int) -> Meal {
        switch hour {
        case 4...10: return .breakfast
        case 11...15: return .lunch
        case 16...21: return .dinner
        default: return .snack
        }
    }
}

// MARK: - Catalog / capture

struct Food: Equatable, Identifiable {
    let foodId: String
    let name: String
    var brand: String?
    var barcode: String?
    var macrosPer100g: Macros
    var servingSizes: [ServingSize] = []
    var defaultServingIndex: Int = 0
    var imageUrl: String?
    var id: String { foodId }
}

struct LabelCaptureFood: Equatable {
    var name: String
    var brand: String?
    var barcode: String?
    var macrosPer100g: Macros
    var servingSizes: [ServingSize] = []
    var defaultServingIndex: Int = 0
    var source: String = "LABEL_OCR"
}

struct MealCaptureItem: Equatable, Identifiable {
    let name: String
    var estimatedPortionGrams: Double
    var suggestedServingLabel: String
    var macrosPer100g: Macros
    var macrosForPortion: Macros
    var confidence: Double
    var matchedFoodId: String?
    var id: String { name }
}

struct MealSearchResult: Equatable, Identifiable {
    let mealId: String
    let name: String
    let macros: Macros
    var imageUrl: String?
    var imageStatus: String = "NONE"
    var id: String { mealId }
}

// MARK: - Adjust / leftover carriers + op lifecycle

enum AdjustStatus { case adjusting, pendingReview, rejected }
enum LeftoverStatus { case analyzing, pendingReview, rejected, applied }

struct AdjustItem: Equatable, Identifiable {
    let name: String
    var servingGrams: Double?
    var macros: Macros?
    var id: String { name }
}

struct AdjustProposal: Equatable {
    var mealName: String
    var packagedProduct: Bool = false
    var items: [AdjustItem] = []
    var newTotals: Macros = .empty
    var oldTotals: Macros = .empty
}

struct MealAdjustment: Equatable {
    var status: AdjustStatus?
    var instruction: String?
    var saveAsMeal: Bool = false
    var proposal: AdjustProposal?
}

struct LeftoverProposalItem: Equatable, Identifiable {
    let name: String
    var servedGrams: Double?
    var consumedGrams: Double?
    var id: String { name }
}

struct LeftoverProposal: Equatable {
    var items: [LeftoverProposalItem] = []
    var servedTotals: Macros?
    var consumedTotals: Macros?
    var warningNote: String?
}

struct Leftover: Equatable {
    var status: LeftoverStatus?
    var servedMacros: Macros?
    var proposal: LeftoverProposal?
}

/// The shared four-state op-rail lifecycle (mirrors PendingNutritionOp). The
/// notification deep links target `.pendingReview` / `.pendingRetake`.
enum PendingNutritionOp { case pending, pendingReview, pendingRetake, rejected, applied }

extension MealAdjustment {
    var op: PendingNutritionOp {
        switch status {
        case .adjusting: return .pending
        case .pendingReview: return proposal != nil ? .pendingReview : .pending
        case .rejected: return .pendingRetake
        case nil: return .pending
        }
    }
}

extension Leftover {
    var op: PendingNutritionOp {
        switch status {
        case .analyzing: return .pending
        case .pendingReview: return proposal != nil ? .pendingReview : .pending
        case .rejected: return .pendingRetake
        case .applied: return .applied
        case nil: return .pending
        }
    }
}

// MARK: - Durable op (synthetic-row projection)

enum NutritionOpType { case capturePhoto, describeAsync, logSavedMeal, confirmMealItems, confirmLabel, removeLeftovers, adjustMeal }

struct NutritionOp: Identifiable {
    static let pendingCapturePrefix = "pending-capture-"
    let id: String
    let type: NutritionOpType
    let date: String
    let mealWire: String
    let label: String
    var targetEntryId: String?
}

// MARK: - Pure formatting (mirror of NutritionFormat.kt)

enum NutritionFormat {
    static func wholeNumber(_ value: Double) -> String {
        let f = NumberFormatter()
        f.numberStyle = .decimal
        f.maximumFractionDigits = 0
        return f.string(from: NSNumber(value: value.rounded())) ?? "\(Int(value.rounded()))"
    }
    static func grams(_ value: Double?) -> String { value == nil ? "—" : "\(wholeNumber(value!)) g" }
    static func kcal(_ value: Double?) -> String { value == nil ? "—" : "\(wholeNumber(value!)) kcal" }

    static func progressFraction(consumed: Double?, target: Double?) -> Double? {
        guard let target, target > 0 else { return nil }
        return min(max((consumed ?? 0) / target, 0), 1)
    }
    static func remaining(consumed: Double?, target: Double?) -> Double? {
        guard let target else { return nil }
        return max(target - (consumed ?? 0), 0)
    }
    static func isOver(consumed: Double?, target: Double?) -> Bool {
        guard let target, target > 0 else { return false }
        return (consumed ?? 0) > target
    }

    /// OCR label gate — verbatim from the shared `looksLikeNutritionLabel`.
    static func looksLikeNutritionLabel(_ ocrText: String) -> Bool {
        let text = ocrText.lowercased()
        if text.contains("nutrition facts") { return true }
        let markers = [
            "serving size", "servings per", "amount per serving", "calories",
            "total fat", "saturated fat", "trans fat", "cholesterol", "sodium",
            "total carbohydrate", "dietary fiber", "total sugars", "added sugars",
            "protein", "% daily value", "vitamin",
        ]
        let hits = markers.filter { text.contains($0) }.count
        return hits >= 3
    }
}

/// The six nutrient rows, in display order (mirror of NutrientRow).
enum NutrientRow: String, CaseIterable, Identifiable {
    case calories = "Calories", protein = "Protein", carbs = "Carbs"
    case fat = "Fat", fiber = "Fiber", sugar = "Sugar"
    var id: String { rawValue }
    var isGrams: Bool { self != .calories }
    func value(_ m: Macros?) -> Double? {
        switch self {
        case .calories: return m?.caloriesKcal
        case .protein: return m?.proteinGrams
        case .carbs: return m?.carbsGrams
        case .fat: return m?.fatGrams
        case .fiber: return m?.fiberGrams
        case .sugar: return m?.sugarGrams
        }
    }
    func format(_ value: Double?) -> String { isGrams ? NutritionFormat.grams(value) : NutritionFormat.kcal(value) }
}
