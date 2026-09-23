import Testing
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave C (D11) — the Vision result mapping. Exercises the
/// pure `NutritionVisionRecognizer.map(barcodePayloads:ocrLines:)` fold (barcode →
/// GTIN preference, OCR → label draft gate) with no camera dependency, plus the
/// GTIN plausibility check. This is the seam between Vision and the shared capture
/// VM: a barcode result drives `onBarcodeDetected`, a label result drives
/// `analyzeLabel`.
@Suite("Nutrition Vision mapping")
struct NutritionVisionMappingTests {

    @Test("a plausible GTIN barcode is surfaced for food lookup")
    func barcodeMapping() {
        let result = NutritionVisionRecognizer.map(barcodePayloads: ["012345678905"], ocrLines: [])
        #expect(result.barcode == "012345678905")
        #expect(result.isNutritionLabel == false)
        #expect(result.ocrText == nil)
    }

    @Test("a real GTIN is preferred over a stray Code128 payload")
    func prefersGTIN() {
        let result = NutritionVisionRecognizer.map(
            barcodePayloads: ["SHIP-4471-XZ", "0123456789012"], ocrLines: [])
        #expect(result.barcode == "0123456789012")
    }

    @Test("falls back to the first payload when none is a clean GTIN")
    func fallbackPayload() {
        let result = NutritionVisionRecognizer.map(barcodePayloads: ["ABC123"], ocrLines: [])
        #expect(result.barcode == "ABC123")
    }

    @Test("GTIN plausibility accepts 8/12/13/14-digit numeric codes only")
    func gtinCheck() {
        #expect(NutritionVisionRecognizer.isPlausibleGTIN("01234567"))       // EAN-8
        #expect(NutritionVisionRecognizer.isPlausibleGTIN("012345678905"))   // UPC-A (12)
        #expect(NutritionVisionRecognizer.isPlausibleGTIN("0123456789012"))  // EAN-13
        #expect(!NutritionVisionRecognizer.isPlausibleGTIN("12345"))         // too short
        #expect(!NutritionVisionRecognizer.isPlausibleGTIN("0123ABC89012"))  // non-numeric
    }

    @Test("OCR text that is a nutrition label draft is flagged for analyzeLabel")
    func ocrLabelMapping() {
        let lines = ["Nutrition Facts", "Serving size 30g", "Calories 120", "Protein 8g"]
        let result = NutritionVisionRecognizer.map(barcodePayloads: [], ocrLines: lines)
        #expect(result.barcode == nil)
        #expect(result.isNutritionLabel == true)
        #expect(result.ocrText?.contains("Nutrition Facts") == true)
    }

    @Test("arbitrary OCR text is not treated as a label")
    func ocrNonLabel() {
        let result = NutritionVisionRecognizer.map(
            barcodePayloads: [], ocrLines: ["Grocery receipt", "Total $42.10"])
        #expect(result.isNutritionLabel == false)
        #expect(result.ocrText != nil)
    }

    @Test("three panel markers (no header) still gate as a label")
    func threeMarkerGate() {
        let result = NutritionVisionRecognizer.map(
            barcodePayloads: [], ocrLines: ["Serving size 1 cup", "Total Fat 3g", "Sodium 210mg"])
        #expect(result.isNutritionLabel == true)
    }
}
