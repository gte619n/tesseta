package com.gte619n.healthfitness.api.sync;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

/**
 * Response envelope (IMPL-AND-20, #11) that flattens any in-scope write
 * response {@code body} to top-level JSON while adding a uniform top-level
 * {@code lastUpdate} the Android outbox adopts as the server's authoritative
 * post-write timestamp (falling back to wall-clock only when absent).
 *
 * <p>The {@code body} is emitted via {@link JsonUnwrapped}, so the wire shape is
 * the existing DTO's fields verbatim <em>plus</em> a sibling {@code lastUpdate}.
 * Existing read-path consumers that read the DTO's own fields are unaffected;
 * the sync engine reads the new {@code lastUpdate} sibling.
 *
 * <pre>
 * { "readingId": "…", "marker": "HDL", …, "lastUpdate": "2026-06-02T18:04:11.482Z" }
 * </pre>
 *
 * <p>{@code lastUpdate} maps to the entity's server {@code updatedAt} where the
 * record carries one; for records without a sync timestamp (e.g. goal phases /
 * steps, food entries, adherence logs) it is the {@link Instant} the controller
 * stamped at write time — the same value the persistence layer records.
 *
 * @param <T> the wrapped response DTO type.
 */
public record WriteResult<T>(
    @JsonUnwrapped @JsonInclude(JsonInclude.Include.NON_NULL) T body,
    Instant lastUpdate
) {

    /** Wrap {@code body} with the authoritative post-write {@code lastUpdate}. */
    public static <T> WriteResult<T> of(T body, Instant lastUpdate) {
        return new WriteResult<>(body, lastUpdate);
    }
}
