package com.gte619n.healthfitness.persistence.access;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.access.EmailAllowlistEntry;
import com.gte619n.healthfitness.core.access.EmailAllowlistRepository;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed invite allowlist (IMPL-MULTIUSER-01 P1.3). One doc per
 * normalized email under {@code allowlist/{emailLower}} — the doc id is the key,
 * so {@link #contains} is a single point read.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreEmailAllowlistRepository implements EmailAllowlistRepository {

    private static final String COLLECTION = "allowlist";

    private final Firestore firestore;

    public FirestoreEmailAllowlistRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public boolean contains(String emailLower) {
        if (emailLower == null || emailLower.isEmpty()) return false;
        DocumentSnapshot snapshot = await(firestore.collection(COLLECTION).document(emailLower).get());
        return snapshot.exists();
    }

    @Override
    public void add(EmailAllowlistEntry entry) {
        Map<String, Object> body = new HashMap<>();
        body.put("emailLower", entry.emailLower());
        body.put("addedBy", entry.addedBy());
        body.put("addedAt", serverTimestamp());
        await(firestore.collection(COLLECTION).document(entry.emailLower()).set(body, SetOptions.merge()));
    }

    @Override
    public void remove(String emailLower) {
        if (emailLower == null || emailLower.isEmpty()) return;
        await(firestore.collection(COLLECTION).document(emailLower).delete());
    }

    @Override
    public List<EmailAllowlistEntry> list() {
        List<EmailAllowlistEntry> out = new ArrayList<>();
        for (QueryDocumentSnapshot doc : await(firestore.collection(COLLECTION).get()).getDocuments()) {
            out.add(new EmailAllowlistEntry(
                doc.getString("emailLower") == null ? doc.getId() : doc.getString("emailLower"),
                doc.getString("addedBy"),
                toInstant(doc.get("addedAt"))
            ));
        }
        return out;
    }
}
