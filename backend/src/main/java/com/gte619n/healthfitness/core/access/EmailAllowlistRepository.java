package com.gte619n.healthfitness.core.access;

import java.util.List;

/**
 * Persistence port for the invite allowlist (IMPL-MULTIUSER-01 P1.3). Keys are
 * normalized lowercase emails. The Firestore implementation stores one doc per
 * email under {@code allowlist/{emailLower}}.
 */
public interface EmailAllowlistRepository {

    boolean contains(String emailLower);

    void add(EmailAllowlistEntry entry);

    void remove(String emailLower);

    List<EmailAllowlistEntry> list();
}
