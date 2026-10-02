package com.gte619n.healthfitness.core.user;

/**
 * Account lifecycle status (IMPL-MULTIUSER-01 P1.1/P1.3/P1.4). Enforced at the
 * auth boundary so a non-{@link #ACTIVE} user is locked out on web and Android
 * alike.
 *
 * <ul>
 *   <li>{@link #PENDING_APPROVAL} — authenticated with Google but not yet on the
 *       invite allowlist; sees a "request received" screen, no app data.</li>
 *   <li>{@link #ACTIVE} — normal access.</li>
 *   <li>{@link #SUSPENDED} — reversible lockout (admin action).</li>
 *   <li>{@link #DISABLED} — offboarded/terminal lockout; refresh-token family is
 *       burned immediately (decision D15).</li>
 * </ul>
 */
public enum UserStatus {
    PENDING_APPROVAL,
    ACTIVE,
    SUSPENDED,
    DISABLED;

    public boolean isActive() {
        return this == ACTIVE;
    }

    /**
     * Stable problem-type token a client uses to route the lockout (e.g. the web
     * app sends a suspended user to a suspended page, a pending user to the
     * "pending approval" screen). Mirrored by the Android/web clients.
     */
    public String problemType() {
        return switch (this) {
            case PENDING_APPROVAL -> "account-pending";
            case SUSPENDED -> "account-suspended";
            case DISABLED -> "account-disabled";
            case ACTIVE -> "account-active";
        };
    }
}
