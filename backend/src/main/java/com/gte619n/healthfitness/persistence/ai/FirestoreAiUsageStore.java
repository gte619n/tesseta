package com.gte619n.healthfitness.persistence.ai;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.ai.AiFeature;
import com.gte619n.healthfitness.core.ai.AiUsageEvent;
import com.gte619n.healthfitness.core.ai.AiUsageStore;
import com.gte619n.healthfitness.core.ai.AiUsageSummary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed {@link AiUsageStore} (IMPL-MULTIUSER-01 Pillar 2).
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code aiUsageEvents/{eventId}} — the raw event (90-day retention; a
 *       TTL policy is an ops concern, see the orchestrator report).</li>
 *   <li>{@code users/{uid}/aiUsageMonthly/{yyyy-MM}} — per-user monthly rollup.</li>
 *   <li>{@code aiUsageMonthlyGlobal/{yyyy-MM}} — global monthly rollup.</li>
 * </ul>
 *
 * <p>Rollups are maintained with {@link FieldValue#increment} on dotted field
 * paths (totals + a per-feature nested map), so concurrent calls never lose an
 * update. Each {@code recordEvent} issues three writes (event + two rollups);
 * they are independent (no cross-doc transaction) because the rollups are pure
 * counters — eventual convergence is the correctness bar, matching the
 * project's other increment-based rollups.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreAiUsageStore implements AiUsageStore {

    private static final Logger log = LoggerFactory.getLogger(FirestoreAiUsageStore.class);

    private static final String EVENTS = "aiUsageEvents";
    private static final String USER_MONTHLY_SUB = "aiUsageMonthly";
    private static final String GLOBAL_MONTHLY = "aiUsageMonthlyGlobal";
    private static final String USERS = "users";

    // Rollup field keys.
    private static final String F_SCOPE = "scope";
    private static final String F_MONTH = "yearMonth";
    private static final String F_CALLS = "totalCalls";
    private static final String F_IN = "totalInputTokens";
    private static final String F_OUT = "totalOutputTokens";
    private static final String F_IMAGES = "totalImages";
    private static final String F_COST = "totalCostUsd";
    private static final String F_FEATURES = "features";

    private final Firestore firestore;

    public FirestoreAiUsageStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void recordEvent(AiUsageEvent event) {
        if (event == null) {
            return;
        }
        String month = monthOf(event);
        writeEvent(event);
        incrementRollup(userMonthlyRef(event.userId(), month), event, event.userId(), month);
        incrementRollup(globalMonthlyRef(month), event, AiUsageSummary.GLOBAL, month);
    }

    private void writeEvent(AiUsageEvent event) {
        Map<String, Object> body = new HashMap<>();
        body.put("userId", event.userId());
        body.put("feature", event.feature() == null ? null : event.feature().name());
        body.put("model", event.model());
        body.put("inputTokens", event.inputTokens());
        body.put("outputTokens", event.outputTokens());
        body.put("images", event.images());
        body.put("estimatedCostUsd", event.estimatedCostUsd());
        body.put("status", event.status() == null ? null : event.status().name());
        body.put("streaming", event.streaming());
        body.put("requestId", event.requestId());
        body.put("yearMonth", monthOf(event));
        if (event.startedAt() != null) {
            body.put("startedAtMillis", event.startedAt().toEpochMilli());
        }
        // serverTimestamp drives any future TTL/retention policy on the raw doc.
        body.put("timestamp", serverTimestamp());
        String id = event.eventId() != null ? event.eventId()
            : firestore.collection(EVENTS).document().getId();
        await(firestore.collection(EVENTS).document(id).set(body));
    }

    /**
     * Fold one event into a rollup doc using atomic increments. {@code scope}
     * and {@code yearMonth} are set (merge) so a freshly-created rollup carries
     * its identity; all counters use {@link FieldValue#increment}.
     */
    private void incrementRollup(
        DocumentReference ref, AiUsageEvent event, String scope, String month) {
        String featureKey = (event.feature() == null ? AiFeature.UNKNOWN : event.feature()).name();
        Map<String, Object> body = new HashMap<>();
        body.put(F_SCOPE, scope);
        body.put(F_MONTH, month);
        body.put(F_CALLS, FieldValue.increment(1L));
        body.put(F_IN, FieldValue.increment(event.inputTokens()));
        body.put(F_OUT, FieldValue.increment(event.outputTokens()));
        body.put(F_IMAGES, FieldValue.increment(event.images()));
        body.put(F_COST, FieldValue.increment(event.estimatedCostUsd()));
        body.put("updatedAt", serverTimestamp());

        // Per-feature nested counters, addressed by dotted field paths so a
        // merge touches only this feature's slice.
        Map<String, Object> perFeature = new HashMap<>();
        perFeature.put("calls", FieldValue.increment(1L));
        perFeature.put("inputTokens", FieldValue.increment(event.inputTokens()));
        perFeature.put("outputTokens", FieldValue.increment(event.outputTokens()));
        perFeature.put("images", FieldValue.increment(event.images()));
        perFeature.put("costUsd", FieldValue.increment(event.estimatedCostUsd()));
        Map<String, Object> features = new HashMap<>();
        features.put(featureKey, perFeature);
        body.put(F_FEATURES, features);

        await(ref.set(body, SetOptions.merge()));
    }

    @Override
    public Optional<AiUsageSummary> monthlyForUser(String userId, String yearMonth) {
        if (userId == null || yearMonth == null) {
            return Optional.empty();
        }
        DocumentSnapshot snap = await(userMonthlyRef(userId, yearMonth).get());
        return snap.exists() ? Optional.of(toSummary(snap, userId, yearMonth)) : Optional.empty();
    }

    @Override
    public Optional<AiUsageSummary> monthlyGlobal(String yearMonth) {
        if (yearMonth == null) {
            return Optional.empty();
        }
        DocumentSnapshot snap = await(globalMonthlyRef(yearMonth).get());
        return snap.exists()
            ? Optional.of(toSummary(snap, AiUsageSummary.GLOBAL, yearMonth))
            : Optional.empty();
    }

    @Override
    public List<AiUsageSummary> topSpenders(String yearMonth, int limit) {
        List<AiUsageSummary> result = new ArrayList<>();
        if (yearMonth == null || limit <= 0) {
            return result;
        }
        // Collection-group query across every user's aiUsageMonthly subcollection,
        // scoped to the requested month, ordered by cost descending.
        Query query = firestore.collectionGroup(USER_MONTHLY_SUB)
            .whereEqualTo(F_MONTH, yearMonth)
            .orderBy(F_COST, Query.Direction.DESCENDING)
            .limit(limit);
        List<QueryDocumentSnapshot> docs = await(query.get()).getDocuments();
        for (QueryDocumentSnapshot doc : docs) {
            String scope = doc.getString(F_SCOPE);
            result.add(toSummary(doc, scope != null ? scope : doc.getReference().getParent()
                .getParent().getId(), yearMonth));
        }
        return result;
    }

    // ---- refs ----

    private DocumentReference userMonthlyRef(String userId, String month) {
        return firestore.collection(USERS).document(userId)
            .collection(USER_MONTHLY_SUB).document(month);
    }

    private DocumentReference globalMonthlyRef(String month) {
        return firestore.collection(GLOBAL_MONTHLY).document(month);
    }

    private static String monthOf(AiUsageEvent event) {
        // yyyy-MM from the recording timestamp (UTC), stable doc id for the month.
        java.time.Instant ts = event.timestamp() != null
            ? event.timestamp() : java.time.Instant.now();
        return ts.atZone(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    // ---- mapping ----

    @SuppressWarnings("unchecked")
    private static AiUsageSummary toSummary(DocumentSnapshot snap, String scope, String month) {
        Map<AiFeature, AiUsageSummary.FeatureUsage> byFeature = new LinkedHashMap<>();
        Object rawFeatures = snap.get(F_FEATURES);
        if (rawFeatures instanceof Map<?, ?> fm) {
            for (Map.Entry<?, ?> e : fm.entrySet()) {
                AiFeature feature = parseFeature(String.valueOf(e.getKey()));
                if (feature == null || !(e.getValue() instanceof Map<?, ?> vm)) {
                    continue;
                }
                Map<String, Object> v = (Map<String, Object>) vm;
                byFeature.put(feature, new AiUsageSummary.FeatureUsage(
                    asLong(v.get("calls")),
                    asLong(v.get("inputTokens")),
                    asLong(v.get("outputTokens")),
                    asLong(v.get("images")),
                    asDouble(v.get("costUsd"))
                ));
            }
        }
        return new AiUsageSummary(
            scope,
            month,
            asLong(snap.get(F_CALLS)),
            asLong(snap.get(F_IN)),
            asLong(snap.get(F_OUT)),
            asLong(snap.get(F_IMAGES)),
            asDouble(snap.get(F_COST)),
            byFeature
        );
    }

    private static AiFeature parseFeature(String name) {
        try {
            return AiFeature.valueOf(name);
        } catch (IllegalArgumentException e) {
            log.debug("Unknown AiFeature in rollup: {}", name);
            return null;
        }
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0.0;
    }
}
