package com.gte619n.healthfitness.persistence.platform;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.platform.AuthorizationCode;
import com.gte619n.healthfitness.core.platform.AuthorizationCodeStore;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Top-level `oauthCodes/{codeHash}` (ADR-0020). Single-use authorization codes.
// The document id is the SHA-256 hash of the code, so the code itself is never
// stored. `consume` reads-and-deletes in a transaction for a hard single-use
// guarantee.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreAuthorizationCodeStore implements AuthorizationCodeStore {

    private static final String COLLECTION = "oauthCodes";

    private final Firestore firestore;

    public FirestoreAuthorizationCodeStore(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void save(AuthorizationCode code) {
        Map<String, Object> body = new HashMap<>();
        body.put("clientId", code.clientId());
        body.put("userId", code.userId());
        body.put("userEmail", code.userEmail());
        body.put("userName", code.userName());
        body.put("redirectUri", code.redirectUri());
        body.put("scopes", new ArrayList<>(code.scopes()));
        body.put("codeChallenge", code.codeChallenge());
        body.put("codeChallengeMethod", code.codeChallengeMethod());
        body.put("expiresAt", Timestamp.of(Date.from(code.expiresAt())));
        body.put("createdAt", serverTimestamp());
        await(firestore.collection(COLLECTION).document(code.codeHash()).set(body));
    }

    @Override
    public Optional<AuthorizationCode> consume(String codeHash) {
        DocumentReference ref = firestore.collection(COLLECTION).document(codeHash);
        return await(firestore.runTransaction(txn -> {
            DocumentSnapshot snap = txn.get(ref).get();
            if (!snap.exists()) {
                return Optional.<AuthorizationCode>empty();
            }
            txn.delete(ref);
            return Optional.of(map(snap));
        }));
    }

    @SuppressWarnings("unchecked")
    private static AuthorizationCode map(DocumentSnapshot snap) {
        List<String> scopes = (List<String>) snap.get("scopes");
        return new AuthorizationCode(
            snap.getId(),
            snap.getString("clientId"),
            snap.getString("userId"),
            snap.getString("userEmail"),
            snap.getString("userName"),
            snap.getString("redirectUri"),
            scopes == null ? new LinkedHashSet<>() : new LinkedHashSet<>(scopes),
            snap.getString("codeChallenge"),
            snap.getString("codeChallengeMethod"),
            toInstant(snap.get("createdAt")),
            toInstant(snap.get("expiresAt"))
        );
    }
}
