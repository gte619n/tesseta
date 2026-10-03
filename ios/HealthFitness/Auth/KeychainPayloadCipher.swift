import CryptoKit
import Foundation
import Security
import SharedCore

/// Keychain-backed AES-GCM payload cipher (IMPL-IOS-01 Phase F, D5).
///
/// Conforms to the shared KMP `PayloadCipher` protocol so `SqlDelightMirrorStore`
/// / `SqlDelightOutboxStore` can encrypt the PHI-bearing columns
/// (`mirrorRow.payloadCipher`, `outboxOp.docCipher`) at the application layer
/// before they touch SQLite. SQLDelight on Kotlin/Native has no turnkey SQLCipher
/// driver, so this is how PHI stays ciphertext at rest (parity with Android's
/// `DbKeystore` envelope — see android/core-data/.../db/DbKeystore.kt).
///
/// Crypto: a 256-bit `SymmetricKey` is generated on first use and persisted in the
/// Keychain (raw 32 bytes, same service/accessibility conventions as
/// `KeychainTokenStore` — `kSecAttrAccessibleAfterFirstUnlock` so background sync
/// can open the mirror while the device is locked, D6). Each `encrypt` produces a
/// fresh random nonce; the stored token is Base64 of `AES.GCM.SealedBox.combined`
/// (nonce ‖ ciphertext ‖ tag), so decrypt is self-describing.
///
/// **Fail-closed:** the Kotlin `PayloadCipher.decrypt` signature is non-throwing
/// (returns a non-null `String`), so a wrong/garbled token cannot surface as an
/// error to Kotlin. We instead return the empty-string sentinel and log. Empty is
/// not valid JSON, so every downstream `Json.decodeFromString` throws rather than
/// handing the caller plaintext garbage — PHI never leaks from a bad decrypt.
/// (Decision recorded in IMPL-IOS-01-OFFLINE-SYNC.md §"Decisions for review".)
///
/// NSObject subclass for Kotlin/Native ObjC-protocol conformance (same pattern as
/// `KeychainTokenProvider`).
final class KeychainPayloadCipher: NSObject, SharedCore.PayloadCipher {
    /// Sentinel returned on an undecryptable token (fail-closed). Not valid JSON,
    /// so it poisons any downstream decode instead of leaking plaintext.
    static let decryptFailureSentinel = ""

    private let service: String
    private let account: String

    /// Cache the resolved key so we don't hit the Keychain on every column.
    private var cachedKey: SymmetricKey?

    init(
        service: String = "com.gte619n.healthfitness.mirror",
        account: String = "payloadKey"
    ) {
        self.service = service
        self.account = account
    }

    // MARK: - PayloadCipher

    func encrypt(plaintext: String) -> String {
        do {
            let box = try AES.GCM.seal(Data(plaintext.utf8), using: key())
            // `combined` is nonce(12) ‖ ciphertext ‖ tag(16); nil only for a
            // non-12-byte nonce, which the default seal never produces.
            guard let combined = box.combined else {
                NSLog("[KeychainPayloadCipher] seal produced no combined box")
                return Self.decryptFailureSentinel
            }
            return combined.base64EncodedString()
        } catch {
            NSLog("[KeychainPayloadCipher] encrypt failed: \(error)")
            return Self.decryptFailureSentinel
        }
    }

    func decrypt(token: String) -> String {
        guard let data = Data(base64Encoded: token) else {
            NSLog("[KeychainPayloadCipher] decrypt: token is not valid Base64")
            return Self.decryptFailureSentinel
        }
        do {
            let box = try AES.GCM.SealedBox(combined: data)
            let plaintext = try AES.GCM.open(box, using: key())
            guard let string = String(data: plaintext, encoding: .utf8) else {
                NSLog("[KeychainPayloadCipher] decrypt: plaintext is not UTF-8")
                return Self.decryptFailureSentinel
            }
            return string
        } catch {
            // Wrong key / tampered ciphertext / truncated blob — fail closed.
            NSLog("[KeychainPayloadCipher] decrypt failed (fail-closed): \(error)")
            return Self.decryptFailureSentinel
        }
    }

    // MARK: - Lifecycle

    /// Drops the Keychain key. Called by the sign-out wipe (Phase E) so the next
    /// user's mirror is re-keyed and the prior user's ciphertext becomes garbage.
    func wipeKey() {
        cachedKey = nil
        delete()
    }

    // MARK: - Key management

    /// The 256-bit symmetric key, generated + persisted on first use.
    private func key() throws -> SymmetricKey {
        if let cachedKey { return cachedKey }
        if let existing = readKeyData() {
            let resolved = SymmetricKey(data: existing)
            cachedKey = resolved
            return resolved
        }
        let fresh = SymmetricKey(size: .bits256)
        let raw = fresh.withUnsafeBytes { Data($0) }
        writeKeyData(raw)
        cachedKey = fresh
        return fresh
    }

    // MARK: - Security framework wrappers (mirror KeychainTokenStore)

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func readKeyData() -> Data? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        return data
    }

    private func writeKeyData(_ data: Data) {
        // Upsert: delete any existing item, then add with the D6 accessibility.
        delete()
        var query = baseQuery()
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    private func delete() {
        SecItemDelete(baseQuery() as CFDictionary)
    }
}
