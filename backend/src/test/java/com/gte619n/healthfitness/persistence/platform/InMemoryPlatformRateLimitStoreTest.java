package com.gte619n.healthfitness.persistence.platform;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InMemoryPlatformRateLimitStoreTest {

    private final InMemoryPlatformRateLimitStore store = new InMemoryPlatformRateLimitStore();

    @Test
    void countsIncreaseWithinTheSameWindow() {
        assertThat(store.incrementAndGet("client:user", 1000)).isEqualTo(1);
        assertThat(store.incrementAndGet("client:user", 1000)).isEqualTo(2);
        assertThat(store.incrementAndGet("client:user", 1000)).isEqualTo(3);
    }

    @Test
    void differentWindowResetsTheCount() {
        store.incrementAndGet("client:user", 1000);
        assertThat(store.incrementAndGet("client:user", 2000)).isEqualTo(1);
    }

    @Test
    void differentPrincipalsCountedSeparately() {
        assertThat(store.incrementAndGet("a:1", 1000)).isEqualTo(1);
        assertThat(store.incrementAndGet("b:2", 1000)).isEqualTo(1);
    }
}
