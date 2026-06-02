package com.gte619n.healthfitness.core.nutrition;

/**
 * Lifecycle of the background AI analysis for a photo-captured entry.
 *
 * <p>A meal/product photo is logged in two phases: the capture endpoint creates
 * a placeholder entry ({@code ANALYZING}) and returns immediately, then a
 * background task fills the entry in — its name, macros and ingredients — and
 * flips the status to {@code READY} (or {@code FAILED} if the photo couldn't be
 * understood). Entries that were never captured from a photo are {@code NONE}.
 */
public enum EntryAnalysisStatus {
    /** Not a background-analyzed entry (manual / catalog / barcode / label). */
    NONE,
    /** Photo stored; AI analysis is running off the request thread. */
    ANALYZING,
    /** Analysis finished and the entry has been filled in. */
    READY,
    /** Analysis failed (no food recognized or the model errored). */
    FAILED
}
