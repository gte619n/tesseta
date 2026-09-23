import Foundation
import SwiftUI

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals) — the markdown-rendering helper for the
/// streaming chat bubbles. Parity target: Android's `Markdown(...)` composable in
/// `core-chat/ChatThread.kt`. On iOS we use the platform's native
/// `AttributedString(markdown:)` — no third-party markdown lib.
///
/// The one wrinkle streaming introduces: mid-stream text often contains an
/// *incomplete* markdown span (a lone `**`, an unclosed `[link`), and strict
/// parsing (`.full`) can throw or drop the trailing text on a partial token.
/// So we parse with `.inlineOnlyPreservingWhitespace` and fall back to plain
/// text on failure, which is exactly what a live token stream needs: every
/// partial frame renders *something*, and the final frame renders full markdown.
enum ChatMarkdown {

    /// Render `raw` markdown to an `AttributedString`, tolerant of the partial
    /// spans a live SSE token stream produces. Never throws — worst case it
    /// returns the raw text verbatim.
    static func attributed(_ raw: String) -> AttributedString {
        // Inline-only + whitespace-preserving is the right mode for chat: we
        // keep the streamed spacing/newlines and only style inline emphasis,
        // which won't blow up on an unterminated block.
        var options = AttributedString.MarkdownParsingOptions()
        options.interpretedSyntax = .inlineOnlyPreservingWhitespace
        options.failurePolicy = .returnPartiallyParsedIfPossible

        if let parsed = try? AttributedString(markdown: raw, options: options) {
            return parsed
        }
        return AttributedString(raw)
    }
}
