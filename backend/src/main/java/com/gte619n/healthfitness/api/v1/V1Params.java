package com.gte619n.healthfitness.api.v1;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

// Shared query-parameter parsing for the /v1 API. Parsing lives here (not in
// @RequestParam converters) so a malformed value throws IllegalArgumentException
// — which the v1 problem-detail advice renders as a clean 400 — rather than
// Spring's generic type-mismatch error.
public final class V1Params {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;

    private V1Params() {}

    public static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        return Math.min(requested, MAX_LIMIT);
    }

    public static Instant instant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "invalid timestamp '" + value + "' (expected ISO-8601, e.g. 2026-07-01T00:00:00Z)");
        }
    }

    public static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                "invalid date '" + value + "' (expected YYYY-MM-DD)");
        }
    }

    // Resolve a [from,to] LocalDate window, defaulting to the last `defaultDays`
    // ending today, and reject an inverted or over-wide range.
    public static DateRange dateRange(String from, String to, int defaultDays, int maxDays) {
        LocalDate toDate = date(to);
        LocalDate fromDate = date(from);
        LocalDate resolvedTo = toDate != null ? toDate : LocalDate.now();
        LocalDate resolvedFrom = fromDate != null ? fromDate : resolvedTo.minusDays(defaultDays);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new IllegalArgumentException("'from' must not be after 'to'");
        }
        if (resolvedFrom.plusDays(maxDays).isBefore(resolvedTo)) {
            throw new IllegalArgumentException("date range too wide (max " + maxDays + " days)");
        }
        return new DateRange(resolvedFrom, resolvedTo);
    }

    public record DateRange(LocalDate from, LocalDate to) {}
}
