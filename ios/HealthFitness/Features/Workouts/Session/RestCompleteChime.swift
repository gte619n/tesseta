import AVFoundation
import Foundation

/// IMPL-IOS-01 Phase 3 Wave D(ii) — the rest-complete beep (D10: "rest-complete
/// beep via AVAudioPlayer"). Parity with Android's `CompletionChime` /
/// `WhistleCue`. Played once when the SINGLE shared rest-timer source crosses to
/// zero (`RestTimerState.isFinished`) — the session view watches that one source
/// and calls `playRestComplete()`, so the beep can never fire out of sync with
/// the overlay or the Live Activity.
///
/// Uses a short bundled tone; if the asset is missing it falls back to a system
/// sound so the cue still fires. The audio session is configured `.ambient` +
/// `.mixWithOthers` so the beep ducks under, but never stops, the user's music.
@MainActor
final class RestCompleteChime {

    static let shared = RestCompleteChime()

    private var player: AVAudioPlayer?

    /// The bundled tone. Ship `rest_complete.caf` (a short ~0.4s beep) in the app
    /// bundle; until it's added the system-sound fallback plays.
    private static let soundName = "rest_complete"
    private static let soundExt = "caf"

    private init() {
        configureSession()
        preload()
    }

    private func configureSession() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.ambient, mode: .default, options: [.mixWithOthers])
    }

    private func preload() {
        guard let url = Bundle.main.url(forResource: Self.soundName, withExtension: Self.soundExt) else {
            return
        }
        player = try? AVAudioPlayer(contentsOf: url)
        player?.prepareToPlay()
    }

    /// Play the rest-complete beep exactly once. Idempotent per call; the caller
    /// (session view) guards against replay by watching the single timer source's
    /// finished-edge, not this method.
    func playRestComplete() {
        try? AVAudioSession.sharedInstance().setActive(true, options: [])
        if let player {
            player.currentTime = 0
            player.play()
        } else {
            // 1057 = a short system "Tock"-style tone; audible without a bundled asset.
            AudioServicesPlaySystemSound(SystemSoundID(1057))
        }
    }
}
