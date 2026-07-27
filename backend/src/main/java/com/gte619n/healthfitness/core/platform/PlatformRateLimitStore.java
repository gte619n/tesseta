package com.gte619n.healthfitness.core.platform;

// Backing store for the /v1 fixed-window rate limiter (ADR-0020, decision D18).
// A distributed (Firestore-backed) implementation gives exact cross-instance
// limits; an in-memory implementation is the per-instance fallback when
// Firestore is absent (tests / local).
public interface PlatformRateLimitStore {

    // Atomically increment the request counter for this (key, window) pair and
    // return the NEW count. `key` identifies the (client, user) principal;
    // `windowStartEpochSeconds` buckets the fixed window. Implementations must
    // make the increment atomic so concurrent requests can't both read a stale
    // count.
    long incrementAndGet(String key, long windowStartEpochSeconds);
}
