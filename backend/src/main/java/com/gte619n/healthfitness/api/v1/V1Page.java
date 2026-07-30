package com.gte619n.healthfitness.api.v1;

import com.gte619n.healthfitness.api.v1.CursorCodec.Position;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

// The uniform list envelope for every /v1 collection (ADR-0020, D5):
// `{ data, nextCursor, hasMore }`, keyset-paginated newest-first.
@Schema(description = "Keyset-paginated list envelope, newest first. To page, pass "
    + "`nextCursor` back as the `cursor` query parameter until `hasMore` is false.")
public record V1Page<T>(
    @Schema(description = "The results for this page, newest first.")
    List<T> data,
    @Schema(description = "Opaque cursor for the next page; pass as `cursor`. Null on the last page.")
    String nextCursor,
    @Schema(description = "True when more results exist beyond this page.")
    boolean hasMore) {

    // Sort `items` newest-first by (sortKey desc, id desc), resume after
    // `cursor`, and take `limit`. Items are mapped to the wire shape by `mapFn`
    // only after the page is selected, so we never map more than one page.
    public static <S, T> V1Page<T> paginate(
        List<S> items,
        Function<S, Instant> keyFn,
        Function<S, String> idFn,
        Function<S, T> mapFn,
        String cursor,
        int limit
    ) {
        List<S> sorted = new ArrayList<>(items);
        sorted.sort(Comparator
            .comparing((S s) -> coalesce(keyFn.apply(s)))
            .thenComparing(s -> idOrEmpty(idFn.apply(s)))
            .reversed());

        int start = 0;
        if (cursor != null && !cursor.isBlank()) {
            Position pos = CursorCodec.decode(cursor);
            Instant cKey = coalesce(pos.sortKey());
            String cId = idOrEmpty(pos.id());
            // Advance past every item at-or-before the cursor position in the
            // descending order.
            while (start < sorted.size() && !isAfter(sorted.get(start), keyFn, idFn, cKey, cId)) {
                start++;
            }
        }

        List<T> data = new ArrayList<>();
        int i = start;
        for (; i < sorted.size() && data.size() < limit; i++) {
            data.add(mapFn.apply(sorted.get(i)));
        }
        boolean hasMore = i < sorted.size();
        String nextCursor = null;
        if (hasMore) {
            S last = sorted.get(i - 1);
            nextCursor = CursorCodec.encode(keyFn.apply(last), idFn.apply(last));
        }
        return new V1Page<>(data, nextCursor, hasMore);
    }

    private static <S> boolean isAfter(
        S s, Function<S, Instant> keyFn, Function<S, String> idFn, Instant cKey, String cId) {
        Instant k = coalesce(keyFn.apply(s));
        int cmp = k.compareTo(cKey);
        if (cmp != 0) {
            return cmp < 0; // strictly older comes after in desc order
        }
        return idOrEmpty(idFn.apply(s)).compareTo(cId) < 0;
    }

    private static Instant coalesce(Instant i) {
        return i == null ? Instant.EPOCH : i;
    }

    private static String idOrEmpty(String id) {
        return id == null ? "" : id;
    }
}
