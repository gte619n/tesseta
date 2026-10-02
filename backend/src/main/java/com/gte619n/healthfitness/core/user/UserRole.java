package com.gte619n.healthfitness.core.user;

/**
 * Application role of a user (IMPL-MULTIUSER-01 P1.1). Stored as a set on the
 * {@link User} record so roles can grow without a second schema migration
 * (decision D18). {@code USER} is the default every account has; {@code ADMIN}
 * grants access to the admin console and {@code @AdminOnly} endpoints.
 */
public enum UserRole {
    USER,
    ADMIN
}
