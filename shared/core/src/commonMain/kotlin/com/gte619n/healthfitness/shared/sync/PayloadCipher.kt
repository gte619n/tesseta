package com.gte619n.healthfitness.shared.sync

/**
 * IMPL-IOS-01 Phase 1C (D5) — app-layer encryption seam for the on-device mirror.
 *
 * SQLDelight on Kotlin/Native has no turnkey SQLCipher driver, so rather than link
 * a cipher-capable SQLite into the K/N binary we encrypt the PHI-bearing columns
 * (mirror `payloadCipher`, outbox `docCipher`) at the application layer before they
 * touch SQLite. Only non-PHI metadata (collection, id, timestamps, status) is
 * stored in the clear, which keeps those columns queryable for LWW + cursoring.
 *
 * The concrete implementation is platform-supplied and keyed from secure storage:
 *   - iOS: AES-GCM via CryptoKit, key wrapped in the Keychain (same store login
 *     already uses). See the iOS composition root.
 *   - JVM (tests): a deterministic/no-op cipher is injected so the engine's logic
 *     is exercised without a real KMS.
 *
 * [encrypt]/[decrypt] round-trip a UTF-8 string ⇄ an opaque, storage-safe token
 * (e.g. Base64 of nonce‖ciphertext‖tag). Implementations MUST be deterministic in
 * their decrypt side and tolerant of their own prior outputs across app launches.
 */
interface PayloadCipher {
    fun encrypt(plaintext: String): String
    fun decrypt(token: String): String
}

/**
 * Identity cipher — stores payloads verbatim. ONLY for JVM tests / bring-up; never
 * wired on device (iOS always injects the Keychain-backed AES-GCM implementation).
 */
object NoopPayloadCipher : PayloadCipher {
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(token: String): String = token
}
