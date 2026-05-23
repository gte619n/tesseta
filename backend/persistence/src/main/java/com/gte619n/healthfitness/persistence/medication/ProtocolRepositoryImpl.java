package com.gte619n.healthfitness.persistence.medication;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.medication.Protocol;
import com.gte619n.healthfitness.core.medication.ProtocolRepository;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed protocol repository.
 * Documents live at users/{userId}/protocols/{protocolId}.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class ProtocolRepositoryImpl implements ProtocolRepository {

    private static final String SUBCOLLECTION = "protocols";

    private final Firestore firestore;

    public ProtocolRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Protocol> findById(String userId, String protocolId) {
        DocumentSnapshot snapshot = await(collection(userId).document(protocolId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toProtocol(userId, snapshot));
    }

    @Override
    public List<Protocol> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("name", Query.Direction.ASCENDING)
            .limit(100)
            .get()).getDocuments();
        return docs.stream().map(d -> toProtocol(userId, d)).toList();
    }

    @Override
    public void save(Protocol protocol) {
        DocumentReference docRef = collection(protocol.userId()).document(protocol.protocolId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(protocol, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String protocolId) {
        await(collection(userId).document(protocolId).delete());
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    @SuppressWarnings("unchecked")
    private Protocol toProtocol(String userId, DocumentSnapshot snapshot) {
        return new Protocol(
            userId,
            snapshot.getId(),
            snapshot.getString("name"),
            snapshot.getString("description"),
            (List<String>) snapshot.get("medicationIds"),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static Map<String, Object> toBody(Protocol p, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", p.name());
        body.put("description", p.description());
        body.put("medicationIds", p.medicationIds());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
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
