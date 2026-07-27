package com.gte619n.healthfitness.api.v1;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

// Opaque keyset cursor for the /v1 API (ADR-0020, decision D5). Encodes the
// sort position of the last item returned — `<sortEpochMillis>:<id>` — so the
// next page resumes strictly after it. Keyset (not offset) so pages stay stable
// when rows are inserted between requests, which matters for a monitoring client
// that pages while new data lands.
public final class CursorCodec {

    private CursorCodec() {}

    public record Position(Instant sortKey, String id) {}

    public static String encode(Instant sortKey, String id) {
        long millis = sortKey == null ? 0L : sortKey.toEpochMilli();
        String raw = millis + ":" + (id == null ? "" : id);
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    // Decode a client-supplied cursor. Malformed input throws
    // IllegalArgumentException, which the v1 problem-detail advice maps to a 400
    // — a client must never be able to 500 the server with a bad cursor.
    public static Position decode(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            if (sep < 0) {
                throw new IllegalArgumentException("cursor missing separator");
            }
            long millis = Long.parseLong(raw.substring(0, sep));
            return new Position(Instant.ofEpochMilli(millis), raw.substring(sep + 1));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid cursor", e);
        }
    }
}
