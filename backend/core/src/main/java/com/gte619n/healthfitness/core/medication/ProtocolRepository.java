package com.gte619n.healthfitness.core.medication;

import java.util.List;
import java.util.Optional;

/**
 * Repository for medication protocols.
 */
public interface ProtocolRepository {
    Optional<Protocol> findById(String userId, String protocolId);
    List<Protocol> findByUser(String userId);
    void save(Protocol protocol);
    void delete(String userId, String protocolId);
}
