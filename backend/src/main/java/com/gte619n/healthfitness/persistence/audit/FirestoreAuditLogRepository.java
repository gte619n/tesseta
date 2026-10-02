package com.gte619n.healthfitness.persistence.audit;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.audit.AuditEntry;
import com.gte619n.healthfitness.core.audit.AuditLogRepository;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed {@link AuditLogRepository} — append-only {@code auditLog/{id}}
 * top-level collection (IMPL-MULTIUSER-01 P1.6).
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreAuditLogRepository implements AuditLogRepository {

    private static final String COLLECTION = "auditLog";

    private final Firestore firestore;

    public FirestoreAuditLogRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void append(AuditEntry entry) {
        Map<String, Object> body = new HashMap<>();
        body.put("adminId", entry.adminId());
        body.put("targetUserId", entry.targetUserId());
        body.put("action", entry.action());
        body.put("resourcePath", entry.resourcePath());
        // Stored as a server timestamp so ordering is authoritative.
        body.put("at", serverTimestamp());
        await(collection().document(entry.id()).set(body));
    }

    @Override
    public List<AuditEntry> recent(int limit) {
        List<QueryDocumentSnapshot> docs = await(collection()
            .orderBy("at", Query.Direction.DESCENDING)
            .limit(Math.max(1, limit))
            .get()).getDocuments();
        List<AuditEntry> out = new ArrayList<>();
        for (QueryDocumentSnapshot doc : docs) {
            out.add(new AuditEntry(
                doc.getId(),
                doc.getString("adminId"),
                doc.getString("targetUserId"),
                doc.getString("action"),
                doc.getString("resourcePath"),
                toInstant(doc.get("at"))
            ));
        }
        return out;
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }
}
