package com.gte619n.healthfitness.auth;

import com.gte619n.healthfitness.core.user.UserStatus;

/**
 * Thrown from the token issue/refresh path when the account is not
 * {@link UserStatus#ACTIVE} (IMPL-MULTIUSER-01 P1.4). {@link com.gte619n.healthfitness.api.auth.AuthController}
 * maps it to 403 with a stable problem type so clients can route the lockout
 * (pending-approval screen vs. suspended page) rather than loop on sign-in.
 */
public class AccountNotActiveException extends RuntimeException {

    private final UserStatus status;

    public AccountNotActiveException(UserStatus status) {
        super("account not active: " + status);
        this.status = status;
    }

    public UserStatus status() {
        return status;
    }

    public String problemType() {
        return status.problemType();
    }
}
