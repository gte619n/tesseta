package com.gte619n.healthfitness.persistence.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.auth.RefreshTokenStore.StoredRefreshToken;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase-1 correctness: single-use refresh-token rotation must be atomic. Several
 * threads race to rotate ONE live token; exactly one may win. A plain
 * read-check-set (the previous {@code markRotated}) lets multiple callers observe
 * {@code revoked==false} and all rotate — minting parallel successor chains from a
 * single token and evading theft detection. Runs against the real Firestore
 * emulator so the transaction semantics are actually exercised.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class RefreshTokenRotationConcurrencyTest {

    @Test
    void concurrentRotationsOfOneLiveTokenProduceExactlyOneWinner(Firestore firestore)
        throws Exception {
        FirestoreRefreshTokenStore store = new FirestoreRefreshTokenStore(firestore);
        Instant now = Instant.now();
        store.save(new StoredRefreshToken(
            "tok-1", "user-1", "hash-1", now, now.plus(1, ChronoUnit.HOURS), false, null, null));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CyclicBarrier startLine = new CyclicBarrier(threads); // maximise the race
        AtomicInteger winners = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                startLine.await();
                if (store.tryMarkRotated("tok-1", Instant.now(), "succ-" + Thread.currentThread().threadId())) {
                    winners.incrementAndGet();
                }
                return null;
            }));
        }
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(winners.get())
            .as("exactly one concurrent refresh may rotate a single-use token")
            .isEqualTo(1);
        StoredRefreshToken after = store.findById("tok-1").orElseThrow();
        assertThat(after.revoked()).isTrue();
        assertThat(after.rotatedAt()).isNotNull();
    }
}
