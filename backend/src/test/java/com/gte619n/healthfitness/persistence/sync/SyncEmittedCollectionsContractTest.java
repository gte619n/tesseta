package com.gte619n.healthfitness.persistence.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Binds the backend's delta-emitted collection set to the shared cross-client
 * contract fixture (docs/reference/sync-emitted-collections.txt). If the reader
 * starts (or stops) emitting a collection without the fixture being updated,
 * this fails — which in turn forces the Android registry test to prove the
 * client routes it. Closes the "backend emits a collection the client silently
 * drops" bug class from convention-only to enforced.
 */
class SyncEmittedCollectionsContractTest {

    @Test
    void emittedCollectionsMatchTheSharedFixture() {
        Set<String> fromReader = FirestoreSyncChangeReader.emittedCollectionNames();
        Set<String> fromFixture = SharedContractFixture.syncEmittedCollections();
        assertThat(fromReader)
            .as("FirestoreSyncChangeReader.emittedCollectionNames() must match "
                + "docs/reference/sync-emitted-collections.txt — update the fixture "
                + "AND the Android CollectionRegistry when the emitted set changes")
            .isEqualTo(fromFixture);
    }

    /** Reads the repo-shared fixture, locating it by walking up from the cwd. */
    static final class SharedContractFixture {
        static Set<String> syncEmittedCollections() {
            Path file = locate("docs/reference/sync-emitted-collections.txt");
            try {
                return Files.readAllLines(file).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .collect(Collectors.toCollection(TreeSet::new));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static Path locate(String relative) {
            Path dir = Path.of("").toAbsolutePath();
            for (int i = 0; i < 8 && dir != null; i++) {
                Path candidate = dir.resolve(relative);
                if (Files.exists(candidate)) {
                    return candidate;
                }
                dir = dir.getParent();
            }
            throw new IllegalStateException("could not locate " + relative + " from " + Path.of("").toAbsolutePath());
        }
    }
}
