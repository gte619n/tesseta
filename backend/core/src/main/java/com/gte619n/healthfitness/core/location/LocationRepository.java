package com.gte619n.healthfitness.core.location;

import java.util.List;
import java.util.Optional;

public interface LocationRepository {
    Optional<Location> findById(String userId, String locationId);
    List<Location> findByUser(String userId, boolean includeInactive);
    void save(Location location);
    void delete(String userId, String locationId);
    void setDefault(String userId, String locationId);
}
