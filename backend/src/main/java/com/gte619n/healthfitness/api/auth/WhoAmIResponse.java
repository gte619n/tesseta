package com.gte619n.healthfitness.api.auth;

import java.util.List;

public record WhoAmIResponse(
    String userId,
    String email,
    String displayName,
    String photoUrl,
    Integer heightCm,
    String biologicalSex,   // IMPL-PROG-01 M3: "MALE" | "FEMALE" | null
    String dateOfBirth,     // IMPL-PROG-01 M3: ISO-8601 date | null
    // biometrics: metric keys the user has hidden from the dashboard (empty = all shown).
    List<String> hiddenBiometrics,
    // IMPL-MULTIUSER-01 P1.8: single source of truth for "show admin affordances"
    // on web + Android, replacing client-side owner-email hardcodes.
    boolean isAdmin
) {
    /** Normalize hiddenBiometrics to a non-null list. */
    public WhoAmIResponse {
        hiddenBiometrics = hiddenBiometrics == null ? List.of() : List.copyOf(hiddenBiometrics);
    }

    /** Pre-isAdmin signature; delegates with isAdmin=false. */
    public WhoAmIResponse(String userId, String email, String displayName, String photoUrl,
                          Integer heightCm, String biologicalSex, String dateOfBirth,
                          List<String> hiddenBiometrics) {
        this(userId, email, displayName, photoUrl, heightCm, biologicalSex, dateOfBirth,
            hiddenBiometrics, false);
    }

    /** Pre-biometrics signature; delegates with no hidden metrics. */
    public WhoAmIResponse(String userId, String email, String displayName, String photoUrl,
                          Integer heightCm, String biologicalSex, String dateOfBirth) {
        this(userId, email, displayName, photoUrl, heightCm, biologicalSex, dateOfBirth, List.of());
    }

    /** Pre-M3 signature; delegates with null demographics and no hidden metrics. */
    public WhoAmIResponse(String userId, String email, String displayName, String photoUrl, Integer heightCm) {
        this(userId, email, displayName, photoUrl, heightCm, null, null, List.of());
    }
}
