package com.gte619n.healthfitness.api.auth;

public record WhoAmIResponse(
    String userId,
    String email,
    String displayName,
    String photoUrl,
    Integer heightCm,
    String biologicalSex,   // IMPL-PROG-01 M3: "MALE" | "FEMALE" | null
    String dateOfBirth      // IMPL-PROG-01 M3: ISO-8601 date | null
) {
    /** Pre-M3 signature; delegates with null demographics so existing callers compile. */
    public WhoAmIResponse(String userId, String email, String displayName, String photoUrl, Integer heightCm) {
        this(userId, email, displayName, photoUrl, heightCm, null, null);
    }
}
