import SwiftUI
// import SharedCore  // NutritionCaptureViewModel, CaptureStage, CaptureEvent — Phase 0D

/// IMPL-IOS-01 Phase 3 Wave C (D11) — the camera capture screen. Parity target
/// (Android): `NutritionCaptureScreen` + `NutritionCaptureViewModel`.
///
/// Drives the AVFoundation `NutritionCameraController` and routes its Vision
/// output to the SHARED `NutritionCaptureViewModel`:
///   - live `VisionResult` with a barcode  → `onBarcodeDetected(code)` (logs the
///     hit + pops back, or offers the label fallback on a miss)
///   - live `VisionResult` that is a label → auto-capture a still → `analyzeLabel`
///   - shutter (meal / leftover)           → `analyzeMeal` / `analyzeLeftover`
///     (durable op, pops back before the upload starts)
///
/// The `CaptureStage` state machine (mirror below) drives the overlay: scanning,
/// working, a barcode-food confirm, a barcode-miss fallback, an editable meal-
/// items list, a label draft, or a terminal Done.
struct NutritionCaptureView: View {

    let date: String
    var leftoverEntryId: String?
    var isLeftoverMode: Bool { leftoverEntryId != nil }

    /// Local mirror of the shared `CaptureStage`.
    enum Stage: Equatable {
        case scanning, working
        case barcodeFood(Food), barcodeMiss(String)
        case mealItems([MealCaptureItem])
        case labelDraft(LabelCaptureFood)
        case done(String)
    }

    @StateObject private var camera = NutritionCameraController()
    @Environment(\.dismiss) private var dismiss
    @State private var stage: Stage = .scanning
    @State private var error: String?
    @State private var cameraAuthorized = true

    var body: some View {
        ZStack {
            if cameraAuthorized {
                CameraPreview(session: camera.session).ignoresSafeArea()
            } else {
                ContentUnavailableView("Camera access needed", systemImage: "camera",
                                       description: Text("Enable camera access in Settings to log food by photo."))
            }
            overlay
        }
        .navigationTitle(isLeftoverMode ? "Leftovers" : "Capture")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            cameraAuthorized = await camera.requestAccess()
            guard cameraAuthorized else { return }
            wireCamera()
            camera.configure()
            camera.start()
        }
        .onDisappear { camera.stop() }
    }

    // MARK: overlay per stage

    @ViewBuilder
    private var overlay: some View {
        VStack {
            Spacer()
            switch stage {
            case .scanning:
                scanningControls
            case .working:
                ProgressView().tint(.white).scaleEffect(1.4).padding(24)
                    .background(.black.opacity(0.4), in: Circle())
            case .barcodeFood(let food):
                ServingConfirmCard(food: food) { idx, qty in confirmBarcodeFood(food, idx, qty) }
            case .barcodeMiss:
                fallbackCard
            case .mealItems(let items):
                MealItemsConfirmCard(items: items) { confirmMealItems($0) }
            case .labelDraft(let draft):
                LabelDraftCard(draft: draft) { idx, qty in confirmLabel(draft, idx, qty) }
            case .done(let message):
                doneCard(message)
            }
        }
        .padding()
        .animation(.default, value: stage)
    }

    private var scanningControls: some View {
        VStack(spacing: 12) {
            Text(isLeftoverMode ? "Photograph what's left on the plate"
                 : "Point at a barcode or nutrition label, or snap your meal")
                .font(.hfBodySm).foregroundStyle(.white)
                .padding(8).background(.black.opacity(0.4), in: Capsule())
            Button {
                camera.isLiveAnalysisEnabled = false
                stage = .working
                camera.capturePhoto()
            } label: {
                Image(systemName: "camera.circle.fill").resizable().frame(width: 68, height: 68)
                    .foregroundStyle(.white)
            }
        }
    }

    private var fallbackCard: some View {
        SheetCard {
            Text("No match for that barcode").font(.hfHeadingSm)
            Text("Point the camera at the nutrition label instead.")
                .font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            Button("Scan the label") {
                stage = .scanning
                camera.isLiveAnalysisEnabled = true
                // Post-0D: vm.wrapped.fallbackToLabel()
            }
            .buttonStyle(.borderedProminent).tint(Theme.accent)
        }
    }

    private func doneCard(_ message: String) -> some View {
        SheetCard {
            Label(message, systemImage: "checkmark.circle.fill").foregroundStyle(Theme.good)
            Button("Done") { dismiss() }.buttonStyle(.borderedProminent).tint(Theme.accent)
        }
    }

    // MARK: camera → shared VM wiring

    private func wireCamera() {
        camera.onLiveResult = { result in
            // Post-0D these delegate straight to the shared VM. Locally we mirror
            // the same routing so the overlay reacts identically.
            if let code = result.barcode {
                camera.isLiveAnalysisEnabled = false
                onBarcodeDetected(code)              // vm.wrapped.onBarcodeDetected(code)
            } else if result.isNutritionLabel {
                camera.isLiveAnalysisEnabled = false
                stage = .working
                camera.capturePhoto()                // capture a full-res still for analyzeLabel
            }
        }
        camera.onPhotoCaptured = { jpeg in
            if isLeftoverMode {
                // vm.wrapped.analyzeLeftover(jpeg) → durable REMOVE_LEFTOVERS op
                dismiss()
            } else if case .working = stage, lastCaptureWasLabel {
                // vm.wrapped.analyzeLabel(jpeg) → LabelDraft stage
                lastCaptureWasLabel = false
            } else {
                // vm.wrapped.analyzeMeal(jpeg) → durable CAPTURE_PHOTO op, pop back
                dismiss()
            }
        }
    }

    @State private var lastCaptureWasLabel = false

    private func onBarcodeDetected(_ code: String) {
        // Post-0D: vm.wrapped.onBarcodeDetected(code); the VM resolves to
        // barcodeFood / barcodeMiss / navigate-back. Local placeholder no-ops the
        // resolution (the concrete repo lands with the XCFramework).
        stage = .working
    }
    private func confirmBarcodeFood(_ food: Food, _ idx: Int, _ qty: Double) {
        // vm.wrapped.confirmBarcodeFood(food, idx, qty)
        stage = .done("\(food.name) logged.")
    }
    private func confirmMealItems(_ items: [MealCaptureItem]) {
        // vm.wrapped.confirmMealItems(items) → durable CONFIRM_MEAL_ITEMS op
        stage = .done("\(items.count) item(s) logged.")
    }
    private func confirmLabel(_ draft: LabelCaptureFood, _ idx: Int, _ qty: Double) {
        // vm.wrapped.confirmLabelDraft(draft, idx, qty) → durable CONFIRM_LABEL op
        stage = .done("\(draft.name) logged.")
    }
}

// MARK: - Small cards used by the capture overlay

/// A bottom card container matching the app surface/border.
struct SheetCard<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12, content: content)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Theme.surface, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Confirm serving + quantity for a scanned/label food.
struct ServingConfirmCard: View {
    let food: Food
    let onConfirm: (Int, Double) -> Void
    @State private var servingIndex = 0
    @State private var quantity = 1.0

    var body: some View {
        SheetCard {
            Text(food.name).font(.hfHeadingSm)
            if !food.servingSizes.isEmpty {
                Picker("Serving", selection: $servingIndex) {
                    ForEach(Array(food.servingSizes.enumerated()), id: \.offset) { i, s in
                        Text(s.label).tag(i)
                    }
                }
                .pickerStyle(.menu)
            }
            Stepper("Quantity: \(quantity, specifier: "%.1f")×", value: $quantity, in: 0.5...10, step: 0.5)
            Button("Log") { onConfirm(servingIndex, quantity) }
                .buttonStyle(.borderedProminent).tint(Theme.accent)
        }
        .onAppear { servingIndex = min(food.defaultServingIndex, max(food.servingSizes.count - 1, 0)) }
    }
}

/// Editable AI-itemized meal proposal.
struct MealItemsConfirmCard: View {
    @State var items: [MealCaptureItem]
    let onConfirm: ([MealCaptureItem]) -> Void

    var body: some View {
        SheetCard {
            Text("Confirm items").font(.hfHeadingSm)
            ForEach(items) { item in
                HStack {
                    Text(item.name).font(.hfBodyMd)
                    Spacer()
                    Text(NutritionFormat.kcal(item.macrosForPortion.caloriesKcal))
                        .font(.hfMonoSm).foregroundStyle(Theme.textSecondary)
                }
            }
            Button("Log \(items.count) item(s)") { onConfirm(items) }
                .buttonStyle(.borderedProminent).tint(Theme.accent)
        }
    }
}

/// A parsed nutrition-label draft; confirm serving + qty to create + log.
struct LabelDraftCard: View {
    let draft: LabelCaptureFood
    let onConfirm: (Int, Double) -> Void
    @State private var servingIndex = 0
    @State private var quantity = 1.0

    var body: some View {
        SheetCard {
            Text(draft.name).font(.hfHeadingSm)
            if let brand = draft.brand {
                Text(brand).font(.hfBodySm).foregroundStyle(Theme.textSecondary)
            }
            HStack {
                Text("Per 100 g").font(.hfCapsSm).foregroundStyle(Theme.textTertiary)
                Spacer()
                Text(NutritionFormat.kcal(draft.macrosPer100g.caloriesKcal)).font(.hfMonoSm)
            }
            if !draft.servingSizes.isEmpty {
                Picker("Serving", selection: $servingIndex) {
                    ForEach(Array(draft.servingSizes.enumerated()), id: \.offset) { i, s in
                        Text(s.label).tag(i)
                    }
                }.pickerStyle(.menu)
            }
            Stepper("Quantity: \(quantity, specifier: "%.1f")×", value: $quantity, in: 0.5...10, step: 0.5)
            Button("Create & log") { onConfirm(servingIndex, quantity) }
                .buttonStyle(.borderedProminent).tint(Theme.accent)
        }
        .onAppear { servingIndex = min(draft.defaultServingIndex, max(draft.servingSizes.count - 1, 0)) }
    }
}
