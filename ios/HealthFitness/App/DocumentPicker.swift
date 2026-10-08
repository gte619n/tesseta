import SwiftUI
import UniformTypeIdentifiers

/// A thin `UIDocumentPickerViewController` wrapper for picking a single PDF.
/// Reads the picked file's bytes off the security-scoped URL and hands back
/// `(fileName, Data)` — the shared upload VMs take `(fileName, bytes)`.
struct DocumentPicker: UIViewControllerRepresentable {
    var contentTypes: [UTType] = [.pdf]
    let onPick: (_ fileName: String, _ data: Data) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: contentTypes, asCopy: true)
        picker.delegate = context.coordinator
        picker.allowsMultipleSelection = false
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let onPick: (_ fileName: String, _ data: Data) -> Void
        init(onPick: @escaping (_ fileName: String, _ data: Data) -> Void) { self.onPick = onPick }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            guard let url = urls.first else { return }
            // asCopy:true gives us a readable temp copy — no need for
            // startAccessingSecurityScopedResource here.
            guard let data = try? Data(contentsOf: url) else { return }
            onPick(url.lastPathComponent, data)
        }
    }
}
