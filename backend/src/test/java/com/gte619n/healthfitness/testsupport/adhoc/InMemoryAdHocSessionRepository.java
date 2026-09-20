package com.gte619n.healthfitness.testsupport.adhoc;

import com.gte619n.healthfitness.core.adhoc.AdHocSessionRepository;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link AdHocSessionRepository} for unit tests. */
public class InMemoryAdHocSessionRepository implements AdHocSessionRepository {

    // userId -> adhocId -> sessionId -> run
    private final Map<String, Map<String, Map<String, ScheduledWorkout>>> store = new ConcurrentHashMap<>();

    private Map<String, ScheduledWorkout> runs(String userId, String adhocId) {
        return store.computeIfAbsent(userId, u -> new ConcurrentHashMap<>())
            .computeIfAbsent(adhocId, a -> new LinkedHashMap<>());
    }

    @Override
    public Optional<ScheduledWorkout> findById(String userId, String adhocId, String sessionId) {
        return Optional.ofNullable(runs(userId, adhocId).get(sessionId));
    }

    @Override
    public List<ScheduledWorkout> findByWorkout(String userId, String adhocId) {
        List<ScheduledWorkout> out = new ArrayList<>(runs(userId, adhocId).values());
        out.sort(Comparator.comparing(
            ScheduledWorkout::date, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    @Override
    public int countByWorkout(String userId, String adhocId) {
        return runs(userId, adhocId).size();
    }

    @Override
    public List<ScheduledWorkout> findCompletedByUser(String userId, LocalDate from, LocalDate to) {
        List<ScheduledWorkout> out = new ArrayList<>();
        Map<String, Map<String, ScheduledWorkout>> byWorkout = store.get(userId);
        if (byWorkout == null) {
            return out;
        }
        for (Map<String, ScheduledWorkout> sessions : byWorkout.values()) {
            for (ScheduledWorkout sw : sessions.values()) {
                if (sw.status() != ScheduledStatus.COMPLETED || sw.date() == null) {
                    continue;
                }
                if (from != null && sw.date().isBefore(from)) {
                    continue;
                }
                if (to != null && sw.date().isAfter(to)) {
                    continue;
                }
                out.add(sw);
            }
        }
        out.sort(Comparator.comparing(
            ScheduledWorkout::date, Comparator.nullsLast(Comparator.naturalOrder())).reversed());
        return out;
    }

    @Override
    public void save(String adhocId, ScheduledWorkout s) {
        runs(s.userId(), adhocId).put(s.scheduledId(), s);
    }
}
