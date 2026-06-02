package com.gte619n.healthfitness.testsupport.sync;

import com.gte619n.healthfitness.core.sync.SyncChange;
import com.gte619n.healthfitness.core.sync.SyncChangeReader;
import com.gte619n.healthfitness.core.sync.SyncCursor;
import com.gte619n.healthfitness.core.sync.SyncRecentWindow;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link SyncChangeReader} test double (IMPL-AND-20 Phase 1) wired by
 * {@code TestPersistenceConfig}. Tests seed it with created and archived
 * documents; it assigns a synthetic, strictly-increasing {@code lastUpdate} per
 * write (mirroring the Firestore server timestamp ordering) and surfaces both
 * live rows and tombstones in {@link SyncChange#CANONICAL_ORDER}, honoring the
 * cursor exactly like the Firestore impl.
 *
 * <p>Keyed by {@code (collection, id)} so re-archiving a previously-created row
 * overwrites it with a tombstone carrying a newer timestamp, exactly as a
 * soft-delete would.
 */
public class InMemorySyncChangeReader implements SyncChangeReader {

    private record Key(String userId, String collection, String id) {}

    private final Map<Key, SyncChange> changes = new ConcurrentHashMap<>();
    private final AtomicLong clock = new AtomicLong(1_000L);

    /** Seed (or overwrite) an ACTIVE change with the next synthetic timestamp. */
    public void put(String userId, String collection, String id, Object doc) {
        long ts = clock.incrementAndGet();
        changes.put(new Key(userId, collection, id),
            new SyncChange(collection, id, SyncStatus.ACTIVE, Instant.ofEpochMilli(ts), doc));
    }

    /** Tombstone a row (ARCHIVED, doc=null) with the next synthetic timestamp. */
    public void archive(String userId, String collection, String id) {
        long ts = clock.incrementAndGet();
        changes.put(new Key(userId, collection, id),
            new SyncChange(collection, id, SyncStatus.ARCHIVED, Instant.ofEpochMilli(ts), null));
    }

    public void clear() {
        changes.clear();
        clock.set(1_000L);
    }

    @Override
    public List<SyncChange> readChanges(
        String userId, SyncCursor since, int limit, SyncRecentWindow window) {
        List<SyncChange> all = new ArrayList<>();
        for (Map.Entry<Key, SyncChange> e : changes.entrySet()) {
            if (!e.getKey().userId().equals(userId)) {
                continue;
            }
            SyncChange c = e.getValue();
            if (since != null && !since.isBefore(c)) {
                continue;
            }
            // Recent-window bound (IMPL-AND-20 #37): heavy time-series are
            // filtered to docs on/after the window date; CRUD domains pass. The
            // effective date is read from the seeded doc map's date field.
            if (window != null && !window.includes(c.collection(), effectiveDate(c, window))) {
                continue;
            }
            all.add(c);
        }
        all.sort(SyncChange.CANONICAL_ORDER);
        if (all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /** Extract a heavy doc's effective date from its seeded field map, else null. */
    private static LocalDate effectiveDate(SyncChange c, SyncRecentWindow window) {
        if (!window.isHeavy(c.collection()) || !(c.doc() instanceof Map<?, ?> doc)) {
            return null;
        }
        String field = SyncRecentWindow.HEAVY_DATE_FIELDS.get(c.collection());
        Object raw = doc.get(field);
        if (raw instanceof LocalDate ld) {
            return ld;
        }
        if (raw instanceof Instant inst) {
            return inst.atZone(java.time.ZoneOffset.UTC).toLocalDate();
        }
        if (raw instanceof CharSequence s) {
            try {
                return LocalDate.parse(s.toString().substring(0, Math.min(10, s.length())));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }
}
