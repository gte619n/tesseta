package com.gte619n.healthfitness.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class V1PageTest {

    record Item(String id, Instant at) {}

    private static final Function<Item, Instant> KEY = Item::at;
    private static final Function<Item, String> ID = Item::id;
    private static final Function<Item, Item> IDENTITY = i -> i;

    private static Item item(String id, String iso) {
        return new Item(id, Instant.parse(iso));
    }

    @Test
    void sortsNewestFirstAndReportsNoMoreWhenUnderLimit() {
        List<Item> items = List.of(
            item("a", "2026-01-01T00:00:00Z"),
            item("c", "2026-03-01T00:00:00Z"),
            item("b", "2026-02-01T00:00:00Z"));

        V1Page<Item> page = V1Page.paginate(items, KEY, ID, IDENTITY, null, 10);

        assertThat(page.data()).extracting(Item::id).containsExactly("c", "b", "a");
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void pagesThroughEveryItemExactlyOnceAcrossCursors() {
        List<Item> items = List.of(
            item("a", "2026-01-01T00:00:00Z"),
            item("b", "2026-02-01T00:00:00Z"),
            item("c", "2026-03-01T00:00:00Z"),
            item("d", "2026-04-01T00:00:00Z"),
            item("e", "2026-05-01T00:00:00Z"));

        V1Page<Item> p1 = V1Page.paginate(items, KEY, ID, IDENTITY, null, 2);
        assertThat(p1.data()).extracting(Item::id).containsExactly("e", "d");
        assertThat(p1.hasMore()).isTrue();

        V1Page<Item> p2 = V1Page.paginate(items, KEY, ID, IDENTITY, p1.nextCursor(), 2);
        assertThat(p2.data()).extracting(Item::id).containsExactly("c", "b");
        assertThat(p2.hasMore()).isTrue();

        V1Page<Item> p3 = V1Page.paginate(items, KEY, ID, IDENTITY, p2.nextCursor(), 2);
        assertThat(p3.data()).extracting(Item::id).containsExactly("a");
        assertThat(p3.hasMore()).isFalse();
        assertThat(p3.nextCursor()).isNull();
    }

    @Test
    void breaksSortKeyTiesByIdDeterministically() {
        String ts = "2026-01-01T00:00:00Z";
        List<Item> items = List.of(item("a", ts), item("b", ts), item("c", ts));

        V1Page<Item> p1 = V1Page.paginate(items, KEY, ID, IDENTITY, null, 2);
        assertThat(p1.data()).extracting(Item::id).containsExactly("c", "b");
        V1Page<Item> p2 = V1Page.paginate(items, KEY, ID, IDENTITY, p1.nextCursor(), 2);
        assertThat(p2.data()).extracting(Item::id).containsExactly("a");
    }

    @Test
    void mapFunctionShapesTheWireItems() {
        List<Item> items = List.of(item("a", "2026-01-01T00:00:00Z"));
        V1Page<String> page = V1Page.paginate(items, KEY, ID, Item::id, null, 10);
        assertThat(page.data()).containsExactly("a");
    }
}
