package com.gte619n.healthfitness.platform;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

// The read-only scopes a third-party app can request (ADR-0020). Each maps a
// wire token (`domain:read`) to a human sentence shown on the consent screen.
// v1 is read-only; the `:write` half of the namespace is intentionally reserved
// (not enumerated here) so adding it later never renames an existing scope.
//
// labs:read is deliberately separate from metrics/vitals-adjacent data so a user
// can share training without exposing clinical results. offline_access is not a
// data scope — it is the standard signal that the app wants a refresh token for
// background monitoring.
public enum PlatformScope {
    PROFILE_READ("profile:read", "Your name and height"),
    WORKOUTS_READ("workouts:read",
        "Your training programs, scheduled and completed workouts, and logged sets"),
    NUTRITION_READ("nutrition:read",
        "Your food log, macros, and daily nutrition totals"),
    MEDICATIONS_READ("medications:read",
        "Your medications, schedules, doses, and adherence"),
    LABS_READ("labs:read",
        "Your blood readings, DEXA scans, body composition, and daily health metrics"),
    OFFLINE_ACCESS("offline_access",
        "Keep monitoring your data in the background when you're not using the app");

    private final String wire;
    private final String consentDescription;

    PlatformScope(String wire, String consentDescription) {
        this.wire = wire;
        this.consentDescription = consentDescription;
    }

    public String wire() {
        return wire;
    }

    public String consentDescription() {
        return consentDescription;
    }

    public static Optional<PlatformScope> fromWire(String wire) {
        return Arrays.stream(values()).filter(s -> s.wire.equals(wire)).findFirst();
    }

    public static boolean isKnown(String wire) {
        return fromWire(wire).isPresent();
    }

    // Parse a space-delimited OAuth scope string into the known scopes,
    // preserving order and rejecting unknown tokens (the caller maps a false
    // return / empty to invalid_scope). Returns empty when any token is
    // unrecognized so we never silently drop a scope the client asked for.
    public static Optional<Set<String>> parse(String scopeParam) {
        if (scopeParam == null || scopeParam.isBlank()) {
            return Optional.empty();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String raw : scopeParam.trim().split("\\s+")) {
            if (!isKnown(raw)) {
                return Optional.empty();
            }
            out.add(raw);
        }
        return out.isEmpty() ? Optional.empty() : Optional.of(out);
    }

    public static String join(Set<String> scopes) {
        return String.join(" ", scopes);
    }
}
