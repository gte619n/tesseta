package com.gte619n.healthfitness.core.sync;

import java.util.Optional;

/**
 * Short-TTL replay guard for idempotent writes (IMPL-AND-20, D7).
 *
 * <p>A mutating endpoint that receives an {@code Idempotency-Key} header records
 * the key (scoped to the user and the logical operation) on first success; a
 * later request bearing the same key is a no-op that returns the prior result
 * instead of mutating again. Keys are retained for roughly seven days — long
 * enough to cover an offline client's outbox replay window, short enough to
 * bound storage.
 *
 * <p>The stored {@code resultId} is the id of the document the original write
 * created/affected, so the controller can re-serialize the current state of
 * that document for the replayed call (the actual record is the source of
 * truth; the store only remembers <em>which</em> record a key produced).
 */
public interface IdempotencyStore {

    /** Approximate retention window for recorded keys (D7). */
    java.time.Duration TTL = java.time.Duration.ofDays(7);

    /**
     * The id produced by a previously-seen {@code (userId, scope, key)}, or
     * empty if this key has not been recorded (or has expired). A present value
     * means the write already happened and must not be repeated.
     *
     * @param scope a stable discriminator for the operation, e.g.
     *              {@code "bloodReadings:create"}, so the same key reused across
     *              unrelated endpoints never collides
     */
    Optional<String> findResult(String userId, String scope, String key);

    /**
     * Record that {@code (userId, scope, key)} produced {@code resultId}. Called
     * after a successful first write. Implementations should be a no-op (or
     * overwrite with the same value) if the key already exists.
     */
    void record(String userId, String scope, String key, String resultId);
}
