package com.gte619n.healthfitness.platform.webhook;

import com.gte619n.healthfitness.platform.PlatformScope;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

// The webhook event catalogue (ADR-0020, D15) and the scope each event requires.
// A subscription only receives an event type if the client both subscribed to it
// AND holds the matching read scope for the affected user — so webhooks can never
// widen what a grant already allows.
public enum WebhookEventType {
    MEDICATION_CHANGED("medication.changed", "medications:read"),
    DOSE_LOGGED("dose.logged", "medications:read"),
    WORKOUT_COMPLETED("workout.completed", "workouts:read"),
    NUTRITION_DAY_UPDATED("nutrition.day.updated", "nutrition:read"),
    LAB_ADDED("lab.added", "labs:read"),
    DEXA_ADDED("dexa.added", "labs:read"),
    DAILY_METRIC_UPDATED("daily-metric.updated", "labs:read");

    private final String wire;
    private final String requiredScope;

    WebhookEventType(String wire, String requiredScope) {
        this.wire = wire;
        this.requiredScope = requiredScope;
    }

    public String wire() {
        return wire;
    }

    public String requiredScope() {
        return requiredScope;
    }

    public boolean isKnown(Set<String> subscribed, Set<String> grantedScopes) {
        return subscribed.contains(wire) && grantedScopes.contains(requiredScope);
    }

    public static boolean isKnownWire(String wire) {
        return Arrays.stream(values()).anyMatch(t -> t.wire.equals(wire));
    }

    public static Set<String> allWire() {
        return Arrays.stream(values()).map(WebhookEventType::wire).collect(Collectors.toSet());
    }

    // All event types the client is eligible for: subscribed AND scope-granted.
    public static Set<WebhookEventType> eligible(Set<String> subscribed, Set<String> grantedScopes) {
        return Arrays.stream(values())
            .filter(t -> subscribed.contains(t.wire) && grantedScopes.contains(t.requiredScope))
            .collect(Collectors.toSet());
    }

    // Guard used by the admin API: every requested event type must be real.
    public static void validateAll(Set<String> requested) {
        for (String t : requested) {
            if (!isKnownWire(t)) {
                throw new IllegalArgumentException("unknown webhook event type: " + t);
            }
        }
    }

    static {
        // Fail fast if a required scope stops being a real PlatformScope.
        for (WebhookEventType t : values()) {
            if (!PlatformScope.isKnown(t.requiredScope)) {
                throw new IllegalStateException("webhook event maps to unknown scope: " + t.requiredScope);
            }
        }
    }
}
