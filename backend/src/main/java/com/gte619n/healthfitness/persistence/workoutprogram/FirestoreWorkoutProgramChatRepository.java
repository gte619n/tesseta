package com.gte619n.healthfitness.persistence.workoutprogram;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import com.gte619n.healthfitness.core.goals.chat.ChatRole;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatMessage;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatRepository;
import com.gte619n.healthfitness.core.workoutprogram.chat.WorkoutProgramChatThread;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Threads at {@code users/{userId}/workoutProgramChatThreads/{threadId}};
 * messages at {@code .../messages/{messageId}}. Mirrors the Goals chat repo.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWorkoutProgramChatRepository implements WorkoutProgramChatRepository {

    private static final String THREADS = "workoutProgramChatThreads";
    private static final String MESSAGES = "messages";
    /** Firestore commits at most 500 writes per batch. */
    private static final int MAX_BATCH = 500;

    private final Firestore firestore;

    public FirestoreWorkoutProgramChatRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference threads(String userId) {
        return firestore.collection("users").document(userId).collection(THREADS);
    }

    private CollectionReference messages(String userId, String threadId) {
        return threads(userId).document(threadId).collection(MESSAGES);
    }

    @Override
    public void createThread(WorkoutProgramChatThread thread) {
        DocumentReference ref = threads(thread.userId()).document(thread.threadId());
        boolean isNew = !await(ref.get()).exists();
        Map<String, Object> body = new HashMap<>();
        body.put("title", thread.title());
        body.put("goalId", thread.goalId());
        body.put("programId", thread.programId());
        body.put("schedule", scheduleToWire(thread.schedule()));
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        await(ref.set(body, SetOptions.merge()));
    }

    @Override
    public Optional<WorkoutProgramChatThread> findThread(String userId, String threadId) {
        DocumentSnapshot snap = await(threads(userId).document(threadId).get());
        return snap.exists() ? Optional.of(toThread(userId, snap)) : Optional.empty();
    }

    @Override
    public List<WorkoutProgramChatThread> listThreads(String userId) {
        List<QueryDocumentSnapshot> docs = await(threads(userId)
            .orderBy("updatedAt", Query.Direction.DESCENDING).limit(100).get()).getDocuments();
        return docs.stream().map(d -> toThread(userId, d)).toList();
    }

    @Override
    public void appendMessage(String userId, WorkoutProgramChatMessage m) {
        DocumentReference ref = messages(userId, m.threadId()).document(m.messageId());
        Map<String, Object> body = new HashMap<>();
        body.put("role", m.role() == null ? null : m.role().name());
        body.put("content", m.content());
        body.put("proposalJson", m.proposalJson());
        body.put("createdAt", serverTimestamp());
        await(ref.set(body, SetOptions.merge()));
        // Touch the thread's updatedAt so listThreads stays ordered by activity.
        await(threads(userId).document(m.threadId())
            .set(Map.of("updatedAt", serverTimestamp()), SetOptions.merge()));
    }

    @Override
    public List<WorkoutProgramChatMessage> listMessages(String userId, String threadId) {
        List<QueryDocumentSnapshot> docs = await(messages(userId, threadId)
            .orderBy("createdAt", Query.Direction.ASCENDING).get()).getDocuments();
        List<WorkoutProgramChatMessage> out = new ArrayList<>();
        for (QueryDocumentSnapshot d : docs) {
            String role = d.getString("role");
            out.add(new WorkoutProgramChatMessage(
                threadId, d.getId(),
                role == null ? ChatRole.ASSISTANT : ChatRole.valueOf(role),
                d.getString("content"), d.getString("proposalJson"),
                toInstant(d.get("createdAt"))));
        }
        return out;
    }

    @Override
    public void deleteThread(String userId, String threadId) {
        // Firestore doesn't cascade subcollections: delete every message in a
        // batched commit (≤500/commit), then the thread doc itself.
        List<QueryDocumentSnapshot> msgs = await(messages(userId, threadId).get()).getDocuments();
        for (int start = 0; start < msgs.size(); start += MAX_BATCH) {
            WriteBatch batch = firestore.batch();
            for (QueryDocumentSnapshot d : msgs.subList(start, Math.min(start + MAX_BATCH, msgs.size()))) {
                batch.delete(d.getReference());
            }
            await(batch.commit());
        }
        await(threads(userId).document(threadId).delete());
    }

    private static Object scheduleToWire(ProgramSchedule s) {
        if (s == null) return null;
        Map<String, Object> m = new HashMap<>();
        m.put("trainingDays", s.trainingDays() == null ? List.of()
            : s.trainingDays().stream().map(Enum::name).toList());
        Map<String, Object> locs = new HashMap<>();
        if (s.dayLocations() != null) {
            s.dayLocations().forEach((k, v) -> locs.put(k.name(), v));
        }
        m.put("dayLocations", locs);
        return m;
    }

    @SuppressWarnings("unchecked")
    private WorkoutProgramChatThread toThread(String userId, DocumentSnapshot s) {
        ProgramSchedule schedule = null;
        if (s.get("schedule") instanceof Map<?, ?> sm0) {
            Map<String, Object> sm = (Map<String, Object>) sm0;
            List<DayOfWeek> days = new ArrayList<>();
            if (sm.get("trainingDays") instanceof List<?> list) {
                for (Object o : list) {
                    try { days.add(DayOfWeek.valueOf(String.valueOf(o))); } catch (IllegalArgumentException ignore) { }
                }
            }
            Map<DayOfWeek, String> locs = new EnumMap<>(DayOfWeek.class);
            if (sm.get("dayLocations") instanceof Map<?, ?> dl) {
                ((Map<String, Object>) dl).forEach((k, v) -> {
                    try { locs.put(DayOfWeek.valueOf(k), String.valueOf(v)); } catch (IllegalArgumentException ignore) { }
                });
            }
            schedule = new ProgramSchedule(days, locs);
        }
        return new WorkoutProgramChatThread(
            userId, s.getId(), s.getString("title"), schedule, s.getString("goalId"),
            toInstant(s.get("createdAt")), toInstant(s.get("updatedAt")), s.getString("programId"));
    }

}
