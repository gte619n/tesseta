package com.gte619n.healthfitness.testsupport.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryScheduledWorkoutRepository implements ScheduledWorkoutRepository {

    private final Map<String, ScheduledWorkout> store = new ConcurrentHashMap<>();

    private String key(ScheduledWorkout s) {
        return s.userId() + "/" + s.programId() + "/" + s.scheduledId();
    }

    @Override
    public List<ScheduledWorkout> findByProgram(String userId, String programId, LocalDate from, LocalDate to) {
        return store.values().stream()
            .filter(s -> userId.equals(s.userId()) && programId.equals(s.programId()))
            .filter(s -> !s.date().isBefore(from) && !s.date().isAfter(to))
            .sorted(Comparator.comparing(ScheduledWorkout::date))
            .toList();
    }

    @Override
    public void save(ScheduledWorkout scheduled) {
        store.put(key(scheduled), scheduled);
    }

    @Override
    public void deletePlannedFrom(String userId, String programId, LocalDate from) {
        List<String> toRemove = new ArrayList<>();
        store.forEach((k, s) -> {
            if (userId.equals(s.userId()) && programId.equals(s.programId())
                && s.status() == ScheduledStatus.PLANNED && !s.date().isBefore(from)) {
                toRemove.add(k);
            }
        });
        toRemove.forEach(store::remove);
    }

    public void clear() {
        store.clear();
    }
}
