import Foundation

/// Stable per-install device identifier (IMPL-IOS-01 Phase E-core).
///
/// Minted once and persisted in `UserDefaults`, then passed to
/// `IosComposition.configure(deviceId:)`. The shared `KtorSyncApi` sends it as
/// `X-HF-Origin-Device` so the backend can suppress echoing a device's own writes
/// back to it (parity with Android's installation id).
///
/// UserDefaults (not Keychain) on purpose: this is a non-secret correlation id,
/// and we WANT it to reset on reinstall (a fresh install is a fresh mirror). A
/// Keychain item can survive app deletion, which would wrongly alias a reinstall
/// to the prior install's sync origin.
enum DeviceIdentity {
    private static let key = "com.gte619n.healthfitness.deviceId"

    /// The persisted device id, minting + storing a new UUID on first access.
    static var current: String {
        let defaults = UserDefaults.standard
        if let existing = defaults.string(forKey: key), !existing.isEmpty {
            return existing
        }
        let minted = UUID().uuidString
        defaults.set(minted, forKey: key)
        return minted
    }
}
