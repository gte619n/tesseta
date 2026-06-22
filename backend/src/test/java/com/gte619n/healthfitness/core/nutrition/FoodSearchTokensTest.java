package com.gte619n.healthfitness.core.nutrition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The token contract that makes catalog search intuitive: a food's index set
 * "contains" a typed word exactly when some word of its name/brand starts with
 * it — so any-word and brand searches resolve, regardless of word order.
 */
class FoodSearchTokensTest {

    @Test
    void queryWordsSplitsAndLowercasesAndDedupes() {
        assertEquals(List.of("chicken", "breast"),
            FoodSearchTokens.queryWords("Chicken  Breast"));
        // Punctuation splits; repeats collapse.
        assertEquals(List.of("siggi", "s"),
            FoodSearchTokens.queryWords("Siggi's"));
        assertEquals(List.of(), FoodSearchTokens.queryWords("   "));
    }

    @Test
    void anyWordPrefixIsFindable() {
        List<String> tokens = FoodSearchTokens.indexTokens("Grilled Chicken Breast", null);
        // A prefix of the *second* and *third* words — not just the first.
        assertTrue(tokens.contains("chick"), "mid-name word prefix should index");
        assertTrue(tokens.contains("breast"), "last word whole should index");
        assertTrue(tokens.contains("gr"), "first word short prefix should index");
        // A word the name doesn't start-with anywhere is absent.
        assertFalse(tokens.contains("xyz"));
    }

    @Test
    void brandWordsAreIndexed() {
        List<String> tokens = FoodSearchTokens.indexTokens("Skyr", "Siggi's");
        assertTrue(tokens.contains("siggi"), "brand should be searchable");
        assertTrue(tokens.contains("sk"));
    }

    @Test
    void everyQueryWordMustBeAStoredPrefix() {
        // The repository AND-filters on exactly this membership relation.
        List<String> tokens = FoodSearchTokens.indexTokens("Chicken Breast", null);
        for (String w : FoodSearchTokens.queryWords("chick breast")) {
            assertTrue(tokens.contains(w), "expected token for query word: " + w);
        }
        assertFalse(tokens.contains("beef"));
    }

    @Test
    void shortWordsStoredWhole() {
        // A one-character word can't form a 2-char prefix, so it's kept as-is.
        List<String> tokens = FoodSearchTokens.indexTokens("A1 Sauce", null);
        assertTrue(tokens.contains("a1"));
    }
}
