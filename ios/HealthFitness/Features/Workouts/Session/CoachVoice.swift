import AVFoundation
import Foundation

/// IMPL-IOS-01 — the workout coach's spoken announcements (D-coach-audio parity
/// with Android's TTS coach voice). Gated by `CoachAudioSettings.voiceAnnouncements`;
/// the caller (the session screen) only speaks when the pref is on.
///
/// Uses `AVSpeechSynthesizer` on a `.playback` + `.duckOthers` audio session so a
/// cue is heard over music/podcasts (briefly lowering them) without stopping them.
/// Mirrors `RestCompleteChime`'s singleton/self-contained shape so the session view
/// drives it with plain text; the synthesizer owns delivery.
@MainActor
final class CoachVoice {

    static let shared = CoachVoice()

    private let synthesizer = AVSpeechSynthesizer()

    private init() {}

    /// Announce the exercise coming up, e.g. "Next up: Back Squat. 3 × 5–8 · 135 lb".
    /// [detail] is the row's one-line target summary (may be empty).
    func announceExercise(name: String, detail: String) {
        let text = detail.isEmpty ? "Next up: \(name)." : "Next up: \(name). \(detail)."
        speak(text)
    }

    /// Speak an arbitrary cue. Interrupts any in-flight utterance (cues are short and
    /// the latest is the relevant one).
    func speak(_ text: String) {
        activateSession()
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        let utterance = AVSpeechUtterance(string: text)
        utterance.rate = AVSpeechUtteranceDefaultSpeechRate
        utterance.voice = AVSpeechSynthesisVoice(language: "en-US")
        synthesizer.speak(utterance)
    }

    private func activateSession() {
        let session = AVAudioSession.sharedInstance()
        // .spokenAudio keeps the cue crisp; .duckOthers lowers music/podcast under it.
        try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? session.setActive(true, options: [])
    }
}
