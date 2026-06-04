package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

public interface MacroTargetRepository {

    /** The active target: greatest {@code effectiveFrom <= today}, if any. */
    Optional<MacroTarget> findActive(String userId);

    void save(MacroTarget target);

    List<MacroTarget> findAll(String userId);
}
