package com.gte619n.healthfitness.core.access;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Invite allowlist gate (IMPL-MULTIUSER-01 P1.3). Normalizes email case so the
 * gate is case-insensitive, and is the single place signup eligibility is
 * decided. Admin CRUD flows through here from {@code AdminUserController}.
 */
@Service
public class EmailAllowlistService {

    private final EmailAllowlistRepository repository;

    public EmailAllowlistService(EmailAllowlistRepository repository) {
        this.repository = repository;
    }

    static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }

    public boolean isAllowed(String email) {
        String norm = normalize(email);
        return norm != null && !norm.isEmpty() && repository.contains(norm);
    }

    public void allow(String email, String addedBy) {
        String norm = normalize(email);
        if (norm == null || norm.isEmpty()) {
            throw new IllegalArgumentException("email required");
        }
        repository.add(new EmailAllowlistEntry(norm, addedBy, Instant.now()));
    }

    public void revoke(String email) {
        String norm = normalize(email);
        if (norm != null && !norm.isEmpty()) {
            repository.remove(norm);
        }
    }

    public List<EmailAllowlistEntry> list() {
        return repository.list();
    }
}
