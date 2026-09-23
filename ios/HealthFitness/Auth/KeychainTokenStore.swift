import Foundation
import Security

/// Keychain-backed token cache (IMPL-IOS-01 Phase 2A, D6).
///
/// Parity: replaces Android's DataStore token cache. Items are stored with
/// `kSecAttrAccessibleAfterFirstUnlock` **on purpose** (D6): background sync
/// (BGAppRefreshTask / silent-push pull, D7/D8) runs while the device is locked,
/// so the tokens must be readable after the first post-boot unlock. We do NOT
/// use `...ThisDeviceOnly`-WhenUnlocked here because that would starve
/// background sync of credentials.
///
/// Real Security-framework calls — this is not a stub. Access/refresh tokens are
/// stored as two generic-password items under one service.
final class KeychainTokenStore {
    private let service: String
    private let accessAccount = "accessToken"
    private let refreshAccount = "refreshToken"

    init(service: String = "com.gte619n.healthfitness.tokens") {
        self.service = service
    }

    /// True if an access token is present (used by the cached-session launch).
    var hasSession: Bool { accessToken != nil }

    var accessToken: String? { read(account: accessAccount) }
    var refreshToken: String? { read(account: refreshAccount) }

    func save(accessToken: String, refreshToken: String) {
        write(account: accessAccount, value: accessToken)
        write(account: refreshAccount, value: refreshToken)
    }

    func clear() {
        delete(account: accessAccount)
        delete(account: refreshAccount)
    }

    // MARK: - Security framework wrappers

    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func read(account: String) -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess,
              let data = item as? Data,
              let value = String(data: data, encoding: .utf8) else {
            return nil
        }
        return value
    }

    private func write(account: String, value: String) {
        let data = Data(value.utf8)
        // Upsert: delete any existing item, then add with the D6 accessibility.
        delete(account: account)
        var query = baseQuery(account: account)
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        SecItemAdd(query as CFDictionary, nil)
    }

    private func delete(account: String) {
        SecItemDelete(baseQuery(account: account) as CFDictionary)
    }
}
