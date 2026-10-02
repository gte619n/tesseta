import Testing
import Foundation
@testable import HealthFitness

/// IMPL-IOS-01 Phase 3 Wave E3 (Goals) — the streaming chat's markdown helper.
/// The load-bearing property is *streaming tolerance*: every partial token frame
/// must render something (never throw / never drop the trailing text), and the
/// final frame must render real inline markdown. Plain Swift, no SharedCore.
@Suite("Chat markdown rendering")
struct ChatMarkdownTests {

    /// The rendered characters of an AttributedString, ignoring styling — this is
    /// what the user actually reads in the bubble.
    private func plainText(_ s: AttributedString) -> String {
        String(s.characters)
    }

    @Test("bold markdown renders as text without the ** delimiters")
    func rendersBold() {
        let out = ChatMarkdown.attributed("Here is **your** plan")
        #expect(plainText(out) == "Here is your plan")
    }

    @Test("a mid-stream unterminated span still renders (no throw, no dropped text)")
    func tolerantOfPartialSpan() {
        // A live token frame often ends mid-emphasis, e.g. "…plan: **Phase".
        let out = ChatMarkdown.attributed("Your plan: **Phase")
        let text = plainText(out)
        // The trailing text must survive — the exact delimiter handling is up to
        // the parser, but "Phase" must be present and nothing may be thrown.
        #expect(text.contains("Your plan:"))
        #expect(text.contains("Phase"))
    }

    @Test("plain text with no markdown round-trips unchanged")
    func plainRoundTrips() {
        let out = ChatMarkdown.attributed("Just some plain text.")
        #expect(plainText(out) == "Just some plain text.")
    }

    @Test("whitespace / newlines are preserved for multi-line assistant replies")
    func preservesWhitespace() {
        let out = ChatMarkdown.attributed("Line one\nLine two")
        #expect(plainText(out).contains("Line one"))
        #expect(plainText(out).contains("Line two"))
    }

    @Test("empty input yields an empty string, never a crash")
    func emptyInput() {
        #expect(plainText(ChatMarkdown.attributed("")) == "")
    }
}
