package com.gte619n.healthfitness.persistence.medication;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugCategory;
import com.gte619n.healthfitness.core.medication.DrugForm;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldPath;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed drug catalog repository.
 * Documents live at drugs/{drugId}.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class DrugRepositoryImpl implements DrugRepository {

    private static final String COLLECTION = "drugs";

    private final Firestore firestore;

    public DrugRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    // Cache names must match CacheConfig.DRUG_BY_ID / DRUG_CATALOG in the app
    // module (persistence can't depend on app, so literals are duplicated).
    @Override
    @Cacheable(cacheNames = "drugById", key = "#drugId")
    public Optional<Drug> findById(String drugId) {
        DocumentSnapshot snapshot = await(collection().document(drugId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toDrug(snapshot));
    }

    /**
     * Batch-resolve drugs by id, returning a map keyed by drugId. Uses
     * Firestore's {@code documentId() whereIn} (chunked at 10, the whereIn
     * limit) so the common case of one chunk is a single read instead of N.
     * Not cached — callers that need single-id caching use {@link #findById}.
     */
    @Override
    public Map<String, Drug> findByIds(List<String> drugIds) {
        Map<String, Drug> result = new LinkedHashMap<>();
        if (drugIds == null || drugIds.isEmpty()) return result;

        // Distinct, non-null ids preserving first-seen order.
        Set<String> distinct = new LinkedHashSet<>();
        for (String id : drugIds) {
            if (id != null) distinct.add(id);
        }
        if (distinct.isEmpty()) return result;

        List<String> ids = new ArrayList<>(distinct);
        for (int i = 0; i < ids.size(); i += 10) {
            List<String> chunk = ids.subList(i, Math.min(i + 10, ids.size()));
            List<QueryDocumentSnapshot> docs = await(collection()
                .whereIn(FieldPath.documentId(), chunk)
                .get()).getDocuments();
            for (QueryDocumentSnapshot doc : docs) {
                result.put(doc.getId(), toDrug(doc));
            }
        }
        return result;
    }

    @Override
    @Cacheable(cacheNames = "drugCatalog", key = "'all'")
    public List<Drug> findAll() {
        List<QueryDocumentSnapshot> docs = await(collection()
            .orderBy("name", Query.Direction.ASCENDING)
            .limit(1000)
            .get()).getDocuments();
        return docs.stream()
            .map(this::toDrug)
            .filter(d -> d.aliasOfDrugId() == null)
            .toList();
    }

    @Override
    public List<Drug> search(String query) {
        // Firestore doesn't support full-text search, so we do a prefix match on name
        // For production, consider Algolia or Typesense
        String upperBound = query + "\uf8ff";
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereGreaterThanOrEqualTo("name", query)
            .whereLessThanOrEqualTo("name", upperBound)
            .limit(50)
            .get()).getDocuments();
        return docs.stream()
            .map(this::toDrug)
            .filter(d -> d.aliasOfDrugId() == null)
            .toList();
    }

    @Override
    public Optional<Drug> findByNameIgnoreCase(String name) {
        // Firestore doesn't support case-insensitive queries natively.
        // We store a lowercase version for matching.
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("nameLower", name.toLowerCase())
            .limit(1)
            .get()).getDocuments();
        if (docs.isEmpty()) return Optional.empty();
        return Optional.of(toDrug(docs.get(0)));
    }

    @Override
    @Caching(evict = {
        @CacheEvict(cacheNames = "drugById", key = "#drug.drugId()"),
        @CacheEvict(cacheNames = "drugCatalog", allEntries = true)
    })
    public void save(Drug drug) {
        DocumentReference docRef = collection().document(drug.drugId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(drug, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    @Caching(evict = {
        @CacheEvict(cacheNames = "drugById", key = "#drugId"),
        @CacheEvict(cacheNames = "drugCatalog", allEntries = true)
    })
    public void delete(String drugId) {
        await(collection().document(drugId).delete());
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    @SuppressWarnings("unchecked")
    private Drug toDrug(DocumentSnapshot snapshot) {
        String imageUrl = snapshot.getString("imageUrl");
        List<String> imageCandidates = (List<String>) snapshot.get("imageCandidates");
        // Back-compat: legacy drugs have no imageCandidates field. Seed the
        // gallery with the current active image so it shows as selectable.
        if (imageCandidates == null || imageCandidates.isEmpty()) {
            imageCandidates = imageUrl != null ? List.of(imageUrl) : List.of();
        }
        return new Drug(
            snapshot.getId(),
            snapshot.getString("name"),
            (List<String>) snapshot.get("aliases"),
            DrugCategory.valueOf(snapshot.getString("category")),
            DrugForm.valueOf(snapshot.getString("form")),
            snapshot.getString("defaultUnit"),
            (List<String>) snapshot.get("commonDoses"),
            imageUrl,
            imageCandidates,
            snapshot.getString("imageFallback"),
            (List<String>) snapshot.get("suggestedMarkers"),
            snapshot.getString("description"),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt")),
            snapshot.getString("aliasOfDrugId")
        );
    }

    private static Map<String, Object> toBody(Drug drug, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", drug.name());
        body.put("nameLower", drug.name().toLowerCase());
        body.put("aliases", drug.aliases());
        body.put("category", drug.category().name());
        body.put("form", drug.form().name());
        body.put("defaultUnit", drug.defaultUnit());
        body.put("commonDoses", drug.commonDoses());
        body.put("imageUrl", drug.imageUrl());
        body.put("imageCandidates", drug.imageCandidates());
        body.put("imageFallback", drug.imageFallback());
        body.put("suggestedMarkers", drug.suggestedMarkers());
        body.put("description", drug.description());
        body.put("aliasOfDrugId", drug.aliasOfDrugId());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

}
