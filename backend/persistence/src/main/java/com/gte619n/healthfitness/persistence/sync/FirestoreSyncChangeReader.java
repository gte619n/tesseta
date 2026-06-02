package com.gte619n.healthfitness.persistence.sync;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.statusOf;

import com.gte619n.healthfitness.core.sync.SyncChange;
import com.gte619n.healthfitness.core.sync.SyncChangeReader;
import com.gte619n.healthfitness.core.sync.SyncCursor;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.Blob;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Firestore-backed {@link SyncChangeReader} for the unified delta-read API
 * (IMPL-AND-20 Phase 1). Enumerates every in-scope per-user collection (and
 * subcollection) for documents whose {@code updatedAt} server timestamp is
 * strictly after the cursor — or all of them on a full initial sync — ordered
 * by {@code updatedAt} ascending, <b>including archived tombstones</b>.
 *
 * <h2>Emitted {@code collection} → Android Room mirror table</h2>
 *
 * Top-level collections emit their Firestore subcollection name verbatim;
 * subcollections emit {@code "parent/child"} and prefix their {@code id} with
 * the parent id(s):
 *
 * <pre>
 *  bloodReadings              users/{u}/bloodReadings/{id}
 *  bloodTestReports           users/{u}/bloodTestReports/{id}
 *  bodyComposition            users/{u}/bodyComposition/{id}
 *  medications                users/{u}/medications/{id}
 *  medications/adherence      users/{u}/medications/{m}/adherence/{id}  → id="{m}/{id}"
 *  medications/history        users/{u}/medications/{m}/history/{id}    → id="{m}/{id}"
 *  protocols                  users/{u}/protocols/{id}
 *  goals                      users/{u}/goals/{id}
 *  goals/phases               users/{u}/goals/{g}/phases/{id}           → id="{g}/{id}"
 *  goals/phases/steps         users/{u}/goals/{g}/phases/{p}/steps/{id} → id="{g}/{p}/{id}"
 *  goalChatThreads            users/{u}/goalChatThreads/{id}
 *  goalChatThreads/messages   users/{u}/goalChatThreads/{t}/messages/{id} → id="{t}/{id}"
 *  nutritionDailyLogs         users/{u}/nutritionDailyLogs/{id}
 *  nutritionDays/entries      users/{u}/nutritionDays/{date}/entries/{id} → id="{date}/{id}"
 *  nutritionTargets           users/{u}/nutritionTargets/{id}
 *  locations                  users/{u}/locations/{id}
 *  dailyMetrics               users/{u}/dailyMetrics/{id}
 *  deviceSyncs                users/{u}/deviceSyncs/{id}
 *  dexaScans                  users/{u}/dexaScans/{id}
 *  weeklyWorkoutAggregates    users/{u}/weeklyWorkoutAggregates/{id}
 *  users                      users/{u}                                  → id="{u}"
 * </pre>
 *
 * <p>The {@code doc} payload is the document's persisted field map, sanitized
 * for transport: the internal {@code syncStatus} key is dropped (its value is
 * surfaced as the change {@code status}), Firestore {@link Timestamp}s become
 * ISO-8601 strings, and binary {@link Blob}s are dropped (the user profile's
 * encrypted Google-Health refresh token never leaves the backend).
 */
@Component
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreSyncChangeReader implements SyncChangeReader {

    private static final String USERS = "users";
    private static final String UPDATED_AT = "updatedAt";

    /** Top-level per-user collections emitted under their own name. */
    private static final List<String> TOP_LEVEL = List.of(
        "bloodReadings",
        "bloodTestReports",
        "bodyComposition",
        "medications",
        "protocols",
        "goals",
        "goalChatThreads",
        "nutritionDailyLogs",
        "nutritionTargets",
        "locations",
        "dailyMetrics",
        "deviceSyncs",
        "dexaScans",
        "weeklyWorkoutAggregates"
    );

    /**
     * Subcollections, keyed by the Firestore collection-id leaf used by a
     * {@code collectionGroup} query, mapped to the emitted {@code collection}
     * routing name. The emitted {@code id} embeds the parent id segments parsed
     * from the document path.
     */
    private static final Map<String, String> SUBCOLLECTIONS = Map.of(
        "adherence", "medications/adherence",
        "history", "medications/history",
        "phases", "goals/phases",
        "steps", "goals/phases/steps",
        "messages", "goalChatThreads/messages",
        "entries", "nutritionDays/entries"
    );

    /** Field keys never forwarded to clients in {@code doc}. */
    private static final Set<String> STRIPPED_KEYS = Set.of(SYNC_STATUS_KEY);

    private final Firestore firestore;

    public FirestoreSyncChangeReader(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public List<SyncChange> readChanges(String userId, SyncCursor since, int limit) {
        List<SyncChange> all = new ArrayList<>();

        // Top-level collections: a bounded ascending scan per collection. We
        // fetch up to `limit` from each then merge + truncate, which guarantees
        // a correct globally-ordered prefix of size `limit`.
        for (String name : TOP_LEVEL) {
            CollectionReference ref = firestore.collection(USERS).document(userId).collection(name);
            for (QueryDocumentSnapshot doc : scan(ref, since, name, limit)) {
                all.add(toChange(name, doc.getId(), doc));
            }
        }

        // Subcollections via collectionGroup, scoped to this user by document
        // path prefix (a collectionGroup query spans all users, so we filter to
        // those whose path starts at users/{userId}).
        for (Map.Entry<String, String> entry : SUBCOLLECTIONS.entrySet()) {
            String leaf = entry.getKey();
            String emittedCollection = entry.getValue();
            for (QueryDocumentSnapshot doc : scanGroup(leaf, since, emittedCollection, userId, limit)) {
                if (!ownedBy(doc.getReference(), userId)) {
                    continue;
                }
                String id = subcollectionId(doc.getReference(), userId);
                all.add(toChange(emittedCollection, id, doc));
            }
        }

        // The user profile document (auth-critical: never archive-filtered, but
        // emitted in the delta so clients mirror it). Sanitized to drop the
        // encrypted googleHealth blob.
        DocumentSnapshot userDoc = await(firestore.collection(USERS).document(userId).get());
        if (userDoc.exists()) {
            SyncChange change = toChange(USERS, userId, userDoc);
            if (since == null || since.isBefore(change)) {
                all.add(change);
            }
        }

        all.sort(SyncChange.CANONICAL_ORDER);
        if (all.size() > limit) {
            return new ArrayList<>(all.subList(0, limit));
        }
        return all;
    }

    /**
     * Ascending {@code updatedAt} scan of a concrete collection, resuming after
     * the cursor. We use a server-side {@code updatedAt >= cursorMillis} range
     * (cheap, indexed) and finish the tiebreak in-app via
     * {@link SyncCursor#isBefore}, so docs sharing the cursor's timestamp are
     * neither skipped nor duplicated.
     */
    private List<QueryDocumentSnapshot> scan(
        CollectionReference ref, SyncCursor since, String emittedCollection, int limit) {
        Query q = ref.orderBy(UPDATED_AT, Query.Direction.ASCENDING);
        if (since != null) {
            q = q.whereGreaterThanOrEqualTo(UPDATED_AT, Timestamp.ofTimeMicroseconds(since.lastUpdateMillis() * 1000L));
        }
        List<QueryDocumentSnapshot> docs = await(q.limit(limit + 1).get()).getDocuments();
        return filterAfterCursor(docs, since, emittedCollection, /*subcollection*/ false, null);
    }

    private List<QueryDocumentSnapshot> scanGroup(
        String leaf, SyncCursor since, String emittedCollection, String userId, int limit) {
        Query q = firestore.collectionGroup(leaf).orderBy(UPDATED_AT, Query.Direction.ASCENDING);
        if (since != null) {
            q = q.whereGreaterThanOrEqualTo(UPDATED_AT, Timestamp.ofTimeMicroseconds(since.lastUpdateMillis() * 1000L));
        }
        // Over-fetch: a collectionGroup spans all users, so after user-filtering
        // we may have fewer than `limit`; the merge/truncate in readChanges
        // bounds the final page. Cap the scan to avoid unbounded reads.
        List<QueryDocumentSnapshot> docs = await(q.limit((limit + 1) * 4).get()).getDocuments();
        return filterAfterCursor(docs, since, emittedCollection, /*subcollection*/ true, userId);
    }

    /** Drop docs at-or-before the cursor in canonical order (tiebreak in-app). */
    private List<QueryDocumentSnapshot> filterAfterCursor(
        List<QueryDocumentSnapshot> docs, SyncCursor since,
        String emittedCollection, boolean subcollection, String userId) {
        if (since == null) {
            return docs;
        }
        List<QueryDocumentSnapshot> kept = new ArrayList<>();
        for (QueryDocumentSnapshot doc : docs) {
            String id = subcollection
                ? subcollectionId(doc.getReference(), userId)
                : doc.getId();
            SyncChange probe = new SyncChange(
                emittedCollection, id, statusOf(doc), updatedAt(doc), null);
            if (since.isBefore(probe)) {
                kept.add(doc);
            }
        }
        return kept;
    }

    private SyncChange toChange(String collection, String id, DocumentSnapshot doc) {
        SyncStatus status = statusOf(doc);
        Instant lastUpdate = updatedAt(doc);
        Object payload = status == SyncStatus.ARCHIVED ? null : sanitize(doc.getData(), collection);
        return new SyncChange(collection, id, status, lastUpdate, payload);
    }

    private static Instant updatedAt(DocumentSnapshot doc) {
        Object raw = doc.get(UPDATED_AT);
        return raw instanceof Timestamp ts ? ts.toDate().toInstant() : Instant.EPOCH;
    }

    /**
     * Render the persisted field map for transport: strip the internal
     * {@code syncStatus} key, convert Firestore {@link Timestamp}s to ISO
     * strings, and drop binary {@link Blob}s (so the user profile's encrypted
     * Google-Health refresh token never leaves the backend).
     */
    private static Map<String, Object> sanitize(Map<String, Object> data, String collection) {
        if (data == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (STRIPPED_KEYS.contains(e.getKey())) {
                continue;
            }
            Object v = sanitizeValue(e.getValue());
            if (v != null) {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toDate().toInstant().toString();
        }
        if (value instanceof Blob) {
            // Encrypted/binary payloads are backend-only — never forwarded.
            return null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object v = sanitizeValue(e.getValue());
                if (v != null) {
                    out.put(String.valueOf(e.getKey()), v);
                }
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                Object v = sanitizeValue(item);
                if (v != null) {
                    out.add(v);
                }
            }
            return out;
        }
        return value;
    }

    /**
     * Encode a subcollection document's identity as the parent id segment(s)
     * joined with '/' followed by the doc id, mirroring its Firestore path
     * below {@code users/{userId}}. E.g. {@code users/u/goals/g/phases/p/steps/s}
     * → {@code "g/p/s"}.
     */
    private static String subcollectionId(DocumentReference ref, String userId) {
        List<String> segments = pathSegmentsBelowUser(ref, userId);
        // segments alternate collection,id,collection,id,... ; keep the ids only.
        List<String> ids = new ArrayList<>();
        for (int i = 1; i < segments.size(); i += 2) {
            ids.add(segments.get(i));
        }
        return String.join("/", ids);
    }

    private static boolean ownedBy(DocumentReference ref, String userId) {
        String path = ref.getPath();
        return path.startsWith(USERS + "/" + userId + "/");
    }

    /** Path segments after {@code users/{userId}/}. */
    private static List<String> pathSegmentsBelowUser(DocumentReference ref, String userId) {
        String prefix = USERS + "/" + userId + "/";
        String path = ref.getPath();
        String rest = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        return List.of(rest.split("/"));
    }

    private static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Firestore call interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Firestore call failed", e.getCause());
        }
    }
}
