package com.gte619n.healthfitness.core.auth;

// The identity of the caller for a single request, resolved from a validated
// Google ID token (or the dev-mode header in tests). `userId` is the JWT `sub`
// claim and is the canonical user ID across the system; `email`,
// `displayName`, and `photoUrl` are best-effort and may be null for dev-mode
// requests. `photoUrl` is the Google `picture` claim — Google hosts the image
// and the URL can change, so it's read fresh from the token, never persisted.
public record CurrentUser(String userId, String email, String displayName, String photoUrl) {}
