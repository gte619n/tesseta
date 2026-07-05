package com.gte619n.healthfitness.testsupport.firestore;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.auth.RefreshTokenStore.StoredRefreshToken;
import com.gte619n.healthfitness.persistence.auth.FirestoreRefreshTokenStore;
import com.google.cloud.firestore.Firestore;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Phase-0 smoke test: proves the {@link FirestoreEmulator} harness boots, a real
 * Firestore repository reads/writes against it, and data is cleared between
 * tests. Uses {@link FirestoreRefreshTokenStore} because it wires from a bare
 * {@code Firestore} with no other collaborators — the cleanest exercise of the
 * emulator round-trip. The concurrency behaviour of this store is hardened in
 * Phase 1.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class FirestoreEmulatorSmokeTest {

    private static StoredRefreshToken liveToken(String tokenId) {
        Instant now = Instant.now();
        return new StoredRefreshToken(
            tokenId, "user-1", "hash-1",
            now, now.plus(1, ChronoUnit.HOURS),
            false, null, null);
    }

    @Test
    void savesAndReadsBackARefreshToken(Firestore firestore) {
        FirestoreRefreshTokenStore store = new FirestoreRefreshTokenStore(firestore);

        store.save(liveToken("tok-1"));

        Optional<StoredRefreshToken> found = store.findById("tok-1");
        assertThat(found).isPresent();
        assertThat(found.get().userId()).isEqualTo("user-1");
        assertThat(found.get().tokenHash()).isEqualTo("hash-1");
        assertThat(found.get().revoked()).isFalse();
        assertThat(found.get().rotatedAt()).isNull();
    }

    @Test
    void markRotatedRevokesAndStampsRotatedAt(Firestore firestore) {
        FirestoreRefreshTokenStore store = new FirestoreRefreshTokenStore(firestore);
        store.save(liveToken("tok-2"));

        Instant rotatedAt = Instant.now();
        assertThat(store.tryMarkRotated("tok-2", rotatedAt, "tok-2-successor")).isTrue();

        StoredRefreshToken rotated = store.findById("tok-2").orElseThrow();
        assertThat(rotated.revoked()).isTrue();
        assertThat(rotated.rotatedAt()).isNotNull();
        assertThat(rotated.replacedBy()).isEqualTo("tok-2-successor");
    }

    @Test
    void dataIsClearedBetweenTests(Firestore firestore) {
        // If beforeEach clearing works, no token from the prior tests survives.
        FirestoreRefreshTokenStore store = new FirestoreRefreshTokenStore(firestore);
        assertThat(store.findById("tok-1")).isEmpty();
        assertThat(store.findById("tok-2")).isEmpty();
    }
}
