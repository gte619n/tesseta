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
    List<String> hiddenBiometrics
) {
    /** Normalize hiddenBiometrics to a non-null list. */
    public WhoAmIResponse {
        hiddenBiometrics = hiddenBiometrics == null ? List.of() : List.copyOf(hiddenBiometrics);
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
