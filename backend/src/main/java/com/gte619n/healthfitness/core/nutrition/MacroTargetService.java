package com.gte619n.healthfitness.core.nutrition;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads and writes a user's daily macro target (with history). */
@Service
public class MacroTargetService {

    private final MacroTargetRepository repository;
    private final MetricChangedPublisher metricChangedPublisher;

    public MacroTargetService(
        MacroTargetRepository repository,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.repository = repository;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    public Optional<MacroTarget> getActive(String userId) {
        requireUser(userId);
        return repository.findActive(userId);
    }

    /**
     * Create a new target effective today and make it the active one. Targets
     * are append-only history; the active one is resolved by {@code effectiveFrom}.
     */
    public MacroTarget setTarget(String userId, Macros macros) {
        requireUser(userId);
        if (macros == null) {
            throw new IllegalArgumentException("macros is required");
        }
        MacroTarget target = new MacroTarget(
            userId,
            UUID.randomUUID().toString(),
            macros,
            LocalDate.now(),
            null,
            null
        );
        repository.save(target);
        // Publish after the save so a failed save never fires events. Changing
        // the target redefines which past days "met target", so bound COUNT
        // Steps must re-evaluate. The avg keys don't depend on the target.
        metricChangedPublisher.publish(userId, MetricKey.NUTRITION_TARGET_MET_DAYS);
        return target;
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
    }
}
