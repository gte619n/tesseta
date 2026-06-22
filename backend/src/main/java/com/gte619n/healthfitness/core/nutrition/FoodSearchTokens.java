package com.gte619n.healthfitness.core.nutrition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Tokenization for catalog food search. Two shapes, kept together so the index
 * side and the query side can't drift apart:
 *
 * <ul>
 *   <li>{@link #queryWords(String)} — the distinct words a user typed.</li>
 *   <li>{@link #indexTokens(String, String)} — every edge-prefix of every word
 *       in a food's name and brand, stored on the doc so an
 *       {@code array-contains} query matches a prefix of <em>any</em> word, not
 *       just a prefix of the whole name.</li>
 * </ul>
 *
 * <p>A food's index set "contains" a query word exactly when some word of its
 * name or brand starts with that query word — so {@code "breast"} finds
 * {@code "chicken breast"}, {@code "siggi"} finds the brand
 * {@code "Siggi's"}, and word order no longer matters.
 */
public final class FoodSearchTokens {

    /** Edge-prefixes shorter than this aren't indexed (keeps arrays small). */
    private static final int MIN_PREFIX = 2;
    /** Don't index past this length — longer prefixes add no selectivity. */
    private static final int MAX_PREFIX = 20;
    /** Cap words per food so a pathological name can't bloat the array. */
    private static final int MAX_WORDS = 16;

    private FoodSearchTokens() {}

    /** Distinct lowercased words in {@code text}; any non-alphanumeric splits. */
    public static List<String> queryWords(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        Set<String> words = new LinkedHashSet<>();
        for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
            if (!raw.isBlank()) {
                words.add(raw);
            }
        }
        return new ArrayList<>(words);
    }

    /**
     * The set of edge-prefixes to store for a food. Order is stable but
     * irrelevant — Firestore array membership is a set test. A word shorter than
     * {@link #MIN_PREFIX} is still stored whole so it stays findable.
     */
    public static List<String> indexTokens(String name, String brand) {
        String text = (name == null ? "" : name) + " " + (brand == null ? "" : brand);
        Set<String> tokens = new LinkedHashSet<>();
        int words = 0;
        for (String word : queryWords(text)) {
            if (words++ >= MAX_WORDS) {
                break;
            }
            int max = Math.min(word.length(), MAX_PREFIX);
            int from = Math.min(MIN_PREFIX, word.length());
            for (int len = from; len <= max; len++) {
                tokens.add(word.substring(0, len));
            }
        }
        return new ArrayList<>(tokens);
    }
}
