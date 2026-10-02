import Foundation
import Vision
import CoreImage

/// IMPL-IOS-01 Phase 3 Wave C (D11) — the Vision layer that replaces Android's
/// ML Kit. Two requests run per analyzed frame:
///   - `VNDetectBarcodesRequest` → a GTIN (EAN/UPC) for the catalog barcode lookup.
///   - `VNRecognizeTextRequest`  → OCR text, gated by `looksLikeNutritionLabel`
///     (the shared heuristic) so we only treat a frame as a label when it really
///     is a Nutrition-Facts panel.
///
/// The recognizer is pure/stateless: it maps a `CVPixelBuffer` (live frame) or a
/// `CGImage` (still) to a `VisionResult`; the ViewModel decides what to do with
/// it (barcode → food lookup, label OCR → AI label draft). This split is what
/// makes the result mapping unit-testable (see NutritionVisionMappingTests) —
/// `map(barcodes:ocrText:)` is a plain function with no camera dependency.
enum NutritionVisionRecognizer {

    struct VisionResult: Equatable {
        /// The best decoded barcode payload (a GTIN), if any.
        var barcode: String?
        /// The joined OCR text, if any recognized.
        var ocrText: String?
        /// True when the OCR text passes the shared nutrition-label heuristic.
        var isNutritionLabel: Bool
    }

    /// The barcode symbologies we accept — the retail GTINs Open Food Facts keys on.
    static let barcodeSymbologies: [VNBarcodeSymbology] = [.ean13, .ean8, .upce, .code128]

    // MARK: Pure mapping (unit-tested)

    /// Fold raw Vision observations into a `VisionResult`. Separated from the
    /// request plumbing so it can be tested without a camera: barcode → GTIN,
    /// OCR strings → joined text + the label gate.
    static func map(barcodePayloads: [String], ocrLines: [String]) -> VisionResult {
        let barcode = barcodePayloads.first { isPlausibleGTIN($0) } ?? barcodePayloads.first
        let text = ocrLines.isEmpty ? nil : ocrLines.joined(separator: "\n")
        let isLabel = text.map { NutritionFormat.looksLikeNutritionLabel($0) } ?? false
        return VisionResult(barcode: barcode, ocrText: text, isNutritionLabel: isLabel)
    }

    /// A GTIN is 8/12/13/14 digits; used to prefer a real product code over a
    /// stray Code128 payload (mirrors the Android analyzer's digit check).
    static func isPlausibleGTIN(_ payload: String) -> Bool {
        let digits = payload.allSatisfy { $0.isNumber }
        return digits && [8, 12, 13, 14].contains(payload.count)
    }

    // MARK: Live-frame + still analysis

    /// Analyze one live camera frame. Runs the barcode + text requests on the
    /// pixel buffer and returns the folded result on the calling queue.
    static func analyze(pixelBuffer: CVPixelBuffer, orientation: CGImagePropertyOrientation = .right) -> VisionResult {
        let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: orientation, options: [:])
        return perform(with: handler)
    }

    /// Analyze a captured still (the shutter path — a full-res label OCR pass).
    static func analyze(cgImage: CGImage, orientation: CGImagePropertyOrientation = .up) -> VisionResult {
        let handler = VNImageRequestHandler(cgImage: cgImage, orientation: orientation, options: [:])
        return perform(with: handler)
    }

    private static func perform(with handler: VNImageRequestHandler) -> VisionResult {
        let barcodeRequest = VNDetectBarcodesRequest()
        barcodeRequest.symbologies = barcodeSymbologies

        let textRequest = VNRecognizeTextRequest()
        textRequest.recognitionLevel = .accurate
        textRequest.usesLanguageCorrection = false   // labels are numeric/terse

        try? handler.perform([barcodeRequest, textRequest])

        let payloads: [String] = (barcodeRequest.results ?? [])
            .compactMap { $0.payloadStringValue }
        let lines: [String] = (textRequest.results ?? [])
            .compactMap { $0.topCandidates(1).first?.string }

        return map(barcodePayloads: payloads, ocrLines: lines)
    }
}
