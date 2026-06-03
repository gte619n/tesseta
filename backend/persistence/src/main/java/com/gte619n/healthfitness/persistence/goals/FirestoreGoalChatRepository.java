package com.gte619n.healthfitness.persistence.goals;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.goals.chat.ChatRole;
import com.gte619n.healthfitness.core.goals.chat.GoalChatMessage;
import com.gte619n.healthfitness.core.goals.chat.GoalChatRepository;
import com.gte619n.healthfitness.core.goals.chat.GoalChatThread;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed goal chat repository.
// Threads live at  users/{userId}/goalChatThreads/{threadId}.
// Messages live at  users/{userId}/goalChatThreads/{threadId}/messages/{messageId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreGoalChatRepository implements GoalChatRepository {

    private static final String THREADS = "goalChatThreads";
    private static final String MESSAGES = "messages";
    /** Firestore commits at most 500 writes per batch. */
    private static final int MAX_BATCH = 500;

    private final Firestore firestore;

    public FirestoreGoalChatRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public void createThread(GoalChatThread thread) {
        DocumentReference docRef = threads(thread.userId()).document(thread.threadId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toThreadBody(thread, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public Optional<GoalChatThread> findThread(String userId, String threadId) {
        DocumentSnapshot snapshot = await(threads(userId).document(threadId).get());
        if (!snapshot.exists() || isArchived(snapshot)) return Optional.empty();
        return Optional.of(toThread(userId, snapshot));
    }

    @Override
    public List<GoalChatThread> listThreads(String userId) {
        List<QueryDocumentSnapshot> docs = await(threads(userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .limit(200)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toThread(userId, d))
            .toList();
    }

    @Override
    public void appendMessage(String userId, GoalChatMessage message) {
        DocumentReference msgRef = messages(userId, message.threadId()).document(message.messageId());
        await(msgRef.set(toMessageBody(message), SetOptions.merge()));
        // Touch the parent thread's updatedAt so listThreads stays ordered
        // by most-recent activity.
        Map<String, Object> touch = new HashMap<>();
        touch.put("updatedAt", serverTimestamp());
        await(threads(userId).document(message.threadId()).set(touch, SetOptions.merge()));
    }

    @Override
    public List<GoalChatMessage> listMessages(String userId, String threadId) {
        List<QueryDocumentSnapshot> docs = await(messages(userId, threadId)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(500)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toMessage(threadId, d))
            .toList();
    }

    @Override
    public void deleteThread(String userId, String threadId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2: archive every message
        // doc and the thread doc itself instead of hard-deleting, so offline
        // clients get tombstones via the delta API.
        Map<String, Object> tombstone = new HashMap<>();
        tombstone.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        tombstone.put("updatedAt", serverTimestamp());

        // Adopt main's batched writes (MAX_BATCH per commit) but keep the
        // IMPL-AND-20 D2 soft-delete: batch tombstone merges, not hard deletes.
        List<QueryDocumentSnapshot> msgs = await(messages(userId, threadId).get()).getDocuments();
        for (int start = 0; start < msgs.size(); start += MAX_BATCH) {
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot msg : msgs.subList(start, Math.min(start + MAX_BATCH, msgs.size()))) {
                batch.set(msg.getReference(), tombstone, SetOptions.merge());
            }
            await(batch.commit());
        }
        await(threads(userId).document(threadId).set(tombstone, SetOptions.merge()));
    }

    private CollectionReference threads(String userId) {
        return firestore.collection("users").document(userId).collection(THREADS);
    }

    private CollectionReference messages(String userId, String threadId) {
        return threads(userId).document(threadId).collection(MESSAGES);
    }

    private static Map<String, Object> toThreadBody(GoalChatThread t, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", t.title());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static GoalChatThread toThread(String userId, DocumentSnapshot snapshot) {
        return new GoalChatThread(
            userId,
            snapshot.getId(),
            snapshot.getString("title"),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static Map<String, Object> toMessageBody(GoalChatMessage m) {
        Map<String, Object> body = new HashMap<>();
        body.put("role", m.role() != null ? m.role().name() : null);
        body.put("content", m.content());
        body.put("proposalJson", m.proposalJson());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        // Persist a real timestamp the first time; serverTimestamp keeps
        // ordering monotonic even when two messages land in the same ms.
        body.put("createdAt", serverTimestamp());
        return body;
    }

    private static GoalChatMessage toMessage(String threadId, DocumentSnapshot snapshot) {
        String role = snapshot.getString("role");
        return new GoalChatMessage(
            threadId,
            snapshot.getId(),
            role != null ? ChatRole.valueOf(role) : null,
            snapshot.getString("content"),
            snapshot.getString("proposalJson"),
            toInstant(snapshot.get("createdAt"))
        );
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
