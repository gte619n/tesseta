package com.gte619n.healthfitness.core.adhoc;

/** How an ad-hoc workout template came to exist (IMPL-ADHOC-01 D3). */
public enum AdHocSource {
    /** Produced by the single-workout AI generator. */
    AI_GENERATED,
    /** AI-seeded then materially edited by the user. */
    AI_ASSISTED,
    /** Built/edited by hand with no AI. */
    MANUAL
}
