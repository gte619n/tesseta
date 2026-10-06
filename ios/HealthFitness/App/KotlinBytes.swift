import Foundation
import SharedCore

extension KotlinByteArray {
    /// Bridge a Kotlin `ByteArray` to Swift `Data` (the download counterpart of
    /// `Data.toKotlinByteArray()`): used for PDF bytes from the shared repos
    /// (lab-report / DEXA) that the SwiftUI side writes to a temp URL for QuickLook.
    func toData() -> Data {
        let count = Int(size)
        var bytes = [UInt8](repeating: 0, count: count)
        for i in 0..<count {
            bytes[i] = UInt8(bitPattern: get(index: Int32(i)))
        }
        return Data(bytes)
    }
}

enum TempPdf {
    /// Write PDF bytes to a uniquely-named temp file and return the URL for
    /// `.quickLookPreview`. Returns nil on write failure.
    static func write(_ data: Data, name: String) -> URL? {
        let safe = name.isEmpty ? "document" : name
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(safe)
            .appendingPathExtension("pdf")
        do {
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            return nil
        }
    }
}
