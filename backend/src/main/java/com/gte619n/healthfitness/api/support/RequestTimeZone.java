package com.gte619n.healthfitness.api.support;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.lang.Nullable;

/**
 * Resolves the caller's IANA time zone from the {@code X-Timezone} request
 * header (attached by every client's HTTP stack). When the header is absent or
 * unparseable it falls back to the server's own default zone — the prior
 * {@code LocalDate.now()} behaviour (UTC in production) — so callers that don't
 * yet send the header are unaffected.
 *
 * <p>Lets "today"-style endpoints compute the user's <em>local</em> calendar day
 * server-side — {@code LocalDate.now(RequestTimeZone.resolve(header))} — instead
 * of trusting the server's own clock, which runs in UTC in production and would
 * otherwise date an evening workout for a user behind UTC to "tomorrow".
 */
public final class RequestTimeZone {

    /** The header every client attaches with its device/browser IANA zone id. */
    public static final String HEADER = "X-Timezone";

    private RequestTimeZone() {}

    /**
     * The header value as a {@link ZoneId}, or the server's default zone
     * ({@link ZoneId#systemDefault()}) when the header is missing or invalid.
     */
    public static ZoneId resolve(@Nullable String header) {
        if (header == null || header.isBlank()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(header.trim());
        } catch (DateTimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
