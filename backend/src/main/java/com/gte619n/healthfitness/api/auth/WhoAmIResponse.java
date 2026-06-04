package com.gte619n.healthfitness.api.auth;

public record WhoAmIResponse(
    String userId,
    String email,
    String displayName,
    String photoUrl,
    Integer heightCm
) {}
