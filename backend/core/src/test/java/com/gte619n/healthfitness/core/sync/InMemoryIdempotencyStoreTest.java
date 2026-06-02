package com.gte619n.healthfitness.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InMemoryIdempotencyStoreTest {

    @Test
    void unseenKeyReturnsEmpty() {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        assertThat(store.findResult("u", "bloodReadings:create", "k1")).isEmpty();
    }

    @Test
    void recordedKeyReturnsResult() {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        store.record("u", "bloodReadings:create", "k1", "doc-1");
        assertThat(store.findResult("u", "bloodReadings:create", "k1")).contains("doc-1");
    }

    @Test
    void keysAreScopedByUserAndScope() {
        IdempotencyStore store = new InMemoryIdempotencyStore();
        store.record("u", "bloodReadings:create", "k1", "doc-1");
        // Same key, different user / scope must not collide.
        assertThat(store.findResult("other", "bloodReadings:create", "k1")).isEmpty();
        assertThat(store.findResult("u", "medications:create", "k1")).isEmpty();
    }

    @Test
    void expiredKeyIsEvicted() {
        Instant t0 = Instant.parse("2026-06-02T00:00:00Z");
        MutableClock clock = new MutableClock(t0);
        IdempotencyStore store = new InMemoryIdempotencyStore(clock);
        store.record("u", "s", "k1", "doc-1");
        assertThat(store.findResult("u", "s", "k1")).contains("doc-1");

        clock.advance(IdempotencyStore.TTL.plusSeconds(1));
        assertThat(store.findResult("u", "s", "k1")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
