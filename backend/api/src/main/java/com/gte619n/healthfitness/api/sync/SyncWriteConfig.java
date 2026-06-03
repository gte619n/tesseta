package com.gte619n.healthfitness.api.sync;

import com.gte619n.healthfitness.core.sync.IdempotencyStore;
import com.gte619n.healthfitness.core.sync.InMemoryIdempotencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Default wiring for the idempotent-write replay guard (IMPL-AND-20, D7).
 *
 * <p>Provides an in-memory {@link IdempotencyStore} only when no other bean is
 * present, so the Firestore-backed implementation in the persistence module
 * (durable across instances) takes precedence in deployed environments, while
 * tests and single-instance local dev fall back to the in-memory store.
 */
@Configuration
public class SyncWriteConfig {

    @Bean
    @ConditionalOnMissingBean(IdempotencyStore.class)
    IdempotencyStore inMemoryIdempotencyStore() {
        return new InMemoryIdempotencyStore();
    }
}
