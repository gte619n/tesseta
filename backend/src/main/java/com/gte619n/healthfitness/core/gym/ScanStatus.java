package com.gte619n.healthfitness.core.gym;

/**
 * IMPL-GYM-003: lifecycle of a gym-video equipment scan.
 *
 * <pre>
 *   REGISTERED  — scan row created, signed upload URL handed out; awaiting the client's upload.
 *   UPLOADED    — client confirmed the video is in GCS; about to enqueue analysis.
 *   ANALYZING   — durable job in flight (Files API upload + Gemini detection + catalog match).
 *   READY       — detection done; {@code preview} populated for the review/confirm UX.
 *   FAILED      — terminal error; {@code error} carries a user-safe message.
 * </pre>
 */
public enum ScanStatus {
    REGISTERED,
    UPLOADED,
    ANALYZING,
    READY,
    FAILED
}
