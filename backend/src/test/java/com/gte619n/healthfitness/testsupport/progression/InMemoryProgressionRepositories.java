package com.gte619n.healthfitness.testsupport.progression;

import com.gte619n.healthfitness.core.progression.BlockParameters;
import com.gte619n.healthfitness.core.progression.BlockParametersRepository;
import com.gte619n.healthfitness.core.progression.ExerciseLoadingProfile;
import com.gte619n.healthfitness.core.progression.ExerciseLoadingProfileRepository;
import com.gte619n.healthfitness.core.progression.PredictionLog;
import com.gte619n.healthfitness.core.progression.PredictionLogRepository;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.progression.ProgressionStateRepository;
import com.gte619n.healthfitness.core.progression.SetObservation;
import com.gte619n.healthfitness.core.progression.SetObservationRepository;
import com.gte619n.healthfitness.core.progression.WeekParameters;
import com.gte619n.healthfitness.core.progression.WeekParametersRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory fakes for the progression repositories, for pure engine tests. */
public final class InMemoryProgressionRepositories {

    private InMemoryProgressionRepositories() {}

    public static final class State implements ProgressionStateRepository {
        private final Map<String, ProgressionState> store = new ConcurrentHashMap<>();
        private static String k(String u, String e) { return u + "/" + e; }
        @Override public Optional<ProgressionState> find(String userId, String exerciseId) {
            return Optional.ofNullable(store.get(k(userId, exerciseId)));
        }
        @Override public List<ProgressionState> findAll(String userId) {
            return store.values().stream().filter(s -> userId.equals(s.userId())).toList();
        }
        @Override public void save(ProgressionState state) { store.put(k(state.userId(), state.exerciseId()), state); }
        @Override public void delete(String userId, String exerciseId) { store.remove(k(userId, exerciseId)); }
    }

    public static final class Observations implements SetObservationRepository {
        private final List<SetObservation> store = new ArrayList<>();
        @Override public synchronized void save(SetObservation o) { store.add(o); }
        @Override public synchronized void saveAll(List<SetObservation> os) { store.addAll(os); }
        @Override public synchronized List<SetObservation> findByExercise(String userId, String exerciseId) {
            return store.stream()
                .filter(o -> userId.equals(o.userId()) && exerciseId.equals(o.exerciseId()))
                .sorted(Comparator.comparing(o -> o.completedAt() == null ? Instant.EPOCH : o.completedAt()))
                .toList();
        }
        @Override public synchronized List<SetObservation> findByUserSince(String userId, Instant from) {
            return store.stream()
                .filter(o -> userId.equals(o.userId()))
                .filter(o -> o.completedAt() != null && !o.completedAt().isBefore(from))
                .sorted(Comparator.comparing(SetObservation::completedAt))
                .toList();
        }
        public synchronized List<SetObservation> all() { return List.copyOf(store); }
    }

    public static final class Predictions implements PredictionLogRepository {
        private final List<PredictionLog> store = new ArrayList<>();
        @Override public synchronized void save(PredictionLog log) { store.add(log); }
        @Override public synchronized List<PredictionLog> findByUser(String userId) {
            return store.stream().filter(p -> userId.equals(p.userId())).toList();
        }
        @Override public synchronized List<PredictionLog> findByModel(String userId, String model) {
            return store.stream().filter(p -> userId.equals(p.userId()) && model.equals(p.model())).toList();
        }
    }

    public static final class Profiles implements ExerciseLoadingProfileRepository {
        private final Map<String, ExerciseLoadingProfile> store = new ConcurrentHashMap<>();
        private static String k(String u, String e) { return u + "/" + e; }
        @Override public Optional<ExerciseLoadingProfile> find(String userId, String exerciseId) {
            return Optional.ofNullable(store.get(k(userId, exerciseId)));
        }
        @Override public void save(ExerciseLoadingProfile p) { store.put(k(p.userId(), p.exerciseId()), p); }
    }

    public static final class Block implements BlockParametersRepository {
        private final Map<String, BlockParameters> store = new ConcurrentHashMap<>();
        @Override public Optional<BlockParameters> find(String userId) { return Optional.ofNullable(store.get(userId)); }
        @Override public void save(BlockParameters p) { store.put(p.userId(), p); }
    }

    public static final class Week implements WeekParametersRepository {
        private final Map<String, WeekParameters> store = new ConcurrentHashMap<>();
        @Override public Optional<WeekParameters> find(String userId) { return Optional.ofNullable(store.get(userId)); }
        @Override public void save(WeekParameters p) { store.put(p.userId(), p); }
    }
}
