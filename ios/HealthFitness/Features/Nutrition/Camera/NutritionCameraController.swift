import AVFoundation
import CoreImage
import SwiftUI
import UIKit

/// IMPL-IOS-01 Phase 3 Wave C (D11) — the AVFoundation capture stack that
/// replaces Android's CameraX. Owns the `AVCaptureSession`: a video preview + a
/// video-data output driving continuous Vision analysis (barcode/label auto-
/// detect), and a photo output for the shutter (meal/leftover/label still).
///
/// The controller is UI-agnostic — it emits `VisionResult`s and captured JPEGs
/// through closures the SwiftUI layer wires to the shared VM's intents
/// (`onBarcodeDetected`, `analyzeLabel`, `analyzeMeal`/`analyzeLeftover`). The
/// preview is surfaced via `CameraPreview` (a `UIViewRepresentable`).
@MainActor
final class NutritionCameraController: NSObject, ObservableObject {

    let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "nutrition.camera.session")
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let ciContext = CIContext()

    /// Fired for every analyzed live frame that yields a barcode/label signal.
    /// Throttled to avoid hammering the VM; the VM itself no-ops once it leaves
    /// the Scanning stage.
    var onLiveResult: ((NutritionVisionRecognizer.VisionResult) -> Void)?
    /// Fired with the captured JPEG once the shutter photo is processed.
    var onPhotoCaptured: ((Data) -> Void)?

    /// Continuous auto-detect is paused once the VM resolves something, so a
    /// second barcode/label frame doesn't re-fire mid-navigation.
    var isLiveAnalysisEnabled = true

    private var lastAnalysis = Date.distantPast
    private let analysisInterval: TimeInterval = 0.4

    // MARK: Lifecycle

    func configure() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = .high

            if let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
               let input = try? AVCaptureDeviceInput(device: device),
               self.session.canAddInput(input) {
                self.session.addInput(input)
            }

            self.videoOutput.setSampleBufferDelegate(self, queue: self.sessionQueue)
            self.videoOutput.alwaysDiscardsLateVideoFrames = true
            if self.session.canAddOutput(self.videoOutput) { self.session.addOutput(self.videoOutput) }
            if self.session.canAddOutput(self.photoOutput) { self.session.addOutput(self.photoOutput) }

            self.session.commitConfiguration()
        }
    }

    func start() {
        sessionQueue.async { [weak self] in
            guard let self, !self.session.isRunning else { return }
            self.session.startRunning()
        }
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    /// Request camera authorization (the screen shows a "needs access" state
    /// otherwise). Returns the resolved status on the main actor.
    func requestAccess() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: return true
        case .notDetermined: return await AVCaptureDevice.requestAccess(for: .video)
        default: return false
        }
    }

    // MARK: Shutter

    /// Capture a still for the meal / leftover / label flows. The delegate
    /// callback resolves `onPhotoCaptured` with the JPEG the VM parks on the
    /// durable op rail.
    func capturePhoto() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            let settings = AVCapturePhotoSettings(format: [AVVideoCodecKey: AVVideoCodecType.jpeg])
            self.photoOutput.capturePhoto(with: settings, delegate: self)
        }
    }
}

// MARK: - Live-frame analysis

extension NutritionCameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(_ output: AVCaptureOutput,
                                   didOutput sampleBuffer: CMSampleBuffer,
                                   from connection: AVCaptureConnection) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        // Throttle + gate on the main actor's flag via a hop.
        let result = NutritionVisionRecognizer.analyze(pixelBuffer: pixelBuffer)
        guard result.barcode != nil || result.isNutritionLabel else { return }
        Task { @MainActor [weak self] in
            guard let self, self.isLiveAnalysisEnabled else { return }
            let now = Date()
            guard now.timeIntervalSince(self.lastAnalysis) > self.analysisInterval else { return }
            self.lastAnalysis = now
            self.onLiveResult?(result)
        }
    }
}

// MARK: - Photo capture

extension NutritionCameraController: AVCapturePhotoCaptureDelegate {
    nonisolated func photoOutput(_ output: AVCapturePhotoOutput,
                                 didFinishProcessingPhoto photo: AVCapturePhoto,
                                 error: Error?) {
        guard error == nil, let data = photo.fileDataRepresentation() else { return }
        Task { @MainActor [weak self] in self?.onPhotoCaptured?(data) }
    }
}

// MARK: - Preview layer

/// A `UIViewRepresentable` wrapping an `AVCaptureVideoPreviewLayer`.
struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.videoPreviewLayer.session = session
        view.videoPreviewLayer.videoGravity = .resizeAspectFill
        return view
    }
    func updateUIView(_ uiView: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var videoPreviewLayer: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
    }
}
