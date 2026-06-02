package com.gte619n.healthfitness.core.sync;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * Opaque pagination cursor for the unified delta-read API (IMPL-AND-20, D6).
 *
 * <p>A cursor identifies the <em>last emitted change</em> in a page so the next
 * page can resume strictly after it with no skips and no duplicates — even when
 * many documents share the same {@code lastUpdate} server timestamp. The total
 * order across the whole sync set is:
 *
 * <pre>(lastUpdateMillis ASC, collection ASC, id ASC)</pre>
 *
 * <p>The wire form is URL-safe Base64 of {@code "<millis>|<collection>|<id>"}.
 * {@code collection} and {@code id} never contain a {@code '|'} (collection
 * names are fixed literals; ids are UUIDs / date strings / composite paths
 * joined with {@code '/'}), so a plain split on the first two pipes is safe and
 * the encoding is reversible. The cursor is deliberately opaque to clients;
 * only this class encodes/decodes it.
 */
public record SyncCursor(long lastUpdateMillis, String collection, String id) {

    public SyncCursor {
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(id, "id");
        if (collection.indexOf('|') >= 0) {
            throw new IllegalArgumentException("collection must not contain '|': " + collection);
        }
    }

    /** Encode to the opaque URL-safe Base64 wire form. */
    public String encode() {
        String raw = lastUpdateMillis + "|" + collection + "|" + id;
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Decode the opaque wire form. Returns {@code null} for a null/blank cursor
     * (an initial full sync). Throws {@link IllegalArgumentException} on a
     * malformed cursor so the controller can answer 400 rather than silently
     * resyncing everything.
     */
    public static SyncCursor decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return null;
        }
        final String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Malformed sync cursor", e);
        }
        // Split on the FIRST two pipes only: the id may itself contain '/'
        // separators (composite subcollection paths) but never a '|'.
        int firstPipe = raw.indexOf('|');
        int secondPipe = firstPipe < 0 ? -1 : raw.indexOf('|', firstPipe + 1);
        if (firstPipe < 0 || secondPipe < 0) {
            throw new IllegalArgumentException("Malformed sync cursor payload");
        }
        final long millis;
        try {
            millis = Long.parseLong(raw.substring(0, firstPipe));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed sync cursor timestamp", e);
        }
        String collection = raw.substring(firstPipe + 1, secondPipe);
        String id = raw.substring(secondPipe + 1);
        return new SyncCursor(millis, collection, id);
    }

    /**
     * True when {@code change} sorts strictly after this cursor in the canonical
     * {@code (lastUpdate, collection, id)} order — i.e. it belongs on a later
     * page and must be emitted exactly once.
     */
    public boolean isBefore(SyncChange change) {
        long changeMillis = change.lastUpdateMillis();
        if (changeMillis != lastUpdateMillis) {
            return lastUpdateMillis < changeMillis;
        }
        int byCollection = collection.compareTo(change.collection());
        if (byCollection != 0) {
            return byCollection < 0;
        }
        return id.compareTo(change.id()) < 0;
    }
}
