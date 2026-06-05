package com.gte619n.healthfitness.api.dashboard;

/**
 * The kind of activity in the dashboard "Recent" feed. Clients map this to an
 * icon and tone; the backend owns the merge/sort/cap and the display strings.
 */
public enum ActivityKind {
    WORKOUT,
    WEIGH_IN,
    SLEEP,
    FOOD,
    MEDICATION
}
