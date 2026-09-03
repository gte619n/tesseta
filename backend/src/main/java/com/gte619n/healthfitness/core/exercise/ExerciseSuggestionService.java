package com.gte619n.healthfitness.core.exercise;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Ranks the gym-executable catalog for the in-workout swap picker (#4): the
 * movements a user could do in place of the prescribed one, ordered so the ones
 * that train the same muscles / movement land first.
 *
 * <p>Composes {@link ExerciseAvailabilityService#executableAt} for the
 * gym-scoped candidate set (which also enforces gym ownership) and scores each
 * candidate against a reference exercise. Ranking lives here, not on the client:
 * the embedded {@code ExerciseSummary} carries only {@code primaryMuscles}, so
 * the phone/web can't score locally — they consume this pre-ranked list.
 */
@Service
public class ExerciseSuggestionService {

    /** Shared-primary is the strongest same-muscle signal. */
    static final int PRIMARY_MATCH = 10;
    /** A primary that is the reference's secondary (or vice-versa) — partial overlap. */
    static final int CROSS_MATCH = 3;
    /** Both list it only as secondary — weak overlap. */
    static final int SECONDARY_MATCH = 1;
    /** Same movement pattern (squat/hinge/push…) — a strong "trains the same way" cue. */
    static final int SAME_PATTERN = 5;
    /** Same mechanic (compound vs isolation) — a mild tiebreak. */
    static final int SAME_MECHANIC = 2;

    private final ExerciseAvailabilityService availability;
    private final ExerciseRepository exercises;

    public ExerciseSuggestionService(
        ExerciseAvailabilityService availability,
        ExerciseRepository exercises
    ) {
        this.availability = availability;
        this.exercises = exercises;
    }

    /**
     * The gym's executable exercises, ranked by similarity to {@code similarTo}
     * (best first) and optionally narrowed by a name/alias {@code search}. The
     * reference exercise itself is excluded. With no {@code similarTo} the list
     * falls back to alphabetical (score 0 everywhere).
     */
    public List<Exercise> rankedFor(String userId, String locationId, String similarTo, String search) {
        List<Exercise> candidates = availability.executableAt(userId, locationId);
        Exercise ref = (similarTo == null || similarTo.isBlank())
            ? null
            : exercises.findById(similarTo).orElse(null);
        String q = (search == null || search.isBlank()) ? null : search.toLowerCase(Locale.ROOT);

        return candidates.stream()
            .filter(e -> ref == null || !e.exerciseId().equals(ref.exerciseId()))
            .filter(e -> q == null || matchesSearch(e, q))
            .sorted(Comparator.comparingInt((Exercise e) -> -score(e, ref))
                .thenComparing(e -> e.name() == null ? "" : e.name(), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    /** Name-or-alias substring match, mirroring {@code FirestoreExerciseRepository.findPublished}. */
    static boolean matchesSearch(Exercise e, String queryLower) {
        if (e.nameLower() != null && e.nameLower().contains(queryLower)) {
            return true;
        }
        return e.aliases() != null
            && e.aliases().stream().anyMatch(a -> a != null && a.toLowerCase(Locale.ROOT).contains(queryLower));
    }

    /** Muscle/pattern similarity of {@code e} to {@code ref}; 0 when there's no reference. */
    static int score(Exercise e, Exercise ref) {
        if (ref == null) {
            return 0;
        }
        Set<String> ePrimary = lower(e.primaryMuscles());
        Set<String> eSecondary = lower(e.secondaryMuscles());
        Set<String> rPrimary = lower(ref.primaryMuscles());
        Set<String> rSecondary = lower(ref.secondaryMuscles());

        int s = 0;
        s += PRIMARY_MATCH * intersect(ePrimary, rPrimary);
        s += CROSS_MATCH * (intersect(ePrimary, rSecondary) + intersect(eSecondary, rPrimary));
        s += SECONDARY_MATCH * intersect(eSecondary, rSecondary);
        if (e.movementPattern() != null && e.movementPattern() == ref.movementPattern()) {
            s += SAME_PATTERN;
        }
        if (e.mechanic() != null && e.mechanic() == ref.mechanic()) {
            s += SAME_MECHANIC;
        }
        return s;
    }

    private static int intersect(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        return (int) a.stream().filter(b::contains).count();
    }

    private static Set<String> lower(List<String> muscles) {
        if (muscles == null) {
            return Set.of();
        }
        return muscles.stream()
            .filter(m -> m != null && !m.isBlank())
            .map(m -> m.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
    }
}
