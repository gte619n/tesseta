package com.gte619n.healthfitness.core.equipment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the bulk equipment import flow:
 *
 * <ol>
 *   <li>parse raw text via {@link EquipmentParser} (impl lives in {@code integrations})</li>
 *   <li>fuzzy-match each parsed item against the existing ACTIVE catalog</li>
 *   <li>return a {@link PreviewResult} with per-item match suggestions</li>
 *   <li>on confirm, create new submissions (delegating to {@link EquipmentService})
 *       and return the equipment IDs that should be added to the location</li>
 * </ol>
 *
 * Adding equipment to a {@code Location} is intentionally NOT done here —
 * {@code Location} lives outside this module's reach in the API layer, so the
 * Phase 4 controller will take the {@code equipmentIdsToAdd} list and call
 * {@code LocationService} itself.
 */
@Service
public class BulkImportService {

    /** Below this Jaccard score (after boosts) we treat it as no match. */
    private static final double MIN_MATCH_SCORE = 0.6;
    /** At or above this score we auto-select the catalog match. */
    private static final double AUTO_MATCH_SCORE = 0.85;

    private static final Set<String> STOPWORDS = Set.of(
        "the", "a", "an", "with", "and", "or", "for", "of", "rack", "set"
    );

    private final EquipmentParser parser;
    private final EquipmentRepository equipmentRepository;
    private final EquipmentService equipmentService;

    public BulkImportService(
        EquipmentParser parser,
        EquipmentRepository equipmentRepository,
        EquipmentService equipmentService
    ) {
        this.parser = parser;
        this.equipmentRepository = equipmentRepository;
        this.equipmentService = equipmentService;
    }

    /**
     * Parse {@code rawText} and produce preview entries (one per parsed item)
     * with the best fuzzy match against the catalog, if any.
     *
     * <p>The {@code locationId} is not used by the matching itself but is
     * accepted so the controller can pass it through; the eventual confirm
     * step needs it.
     */
    public PreviewResult preview(String userId, String locationId, String rawText) {
        List<ParsedEquipment> parsed = parser.parse(rawText);
        // Match against the global active catalog AND the user's own non-rejected
        // submissions (PENDING_REVIEW items they previously imported or submitted).
        // Without this, repeated bulk imports duplicate the user's own pending items.
        // Dedupe by equipmentId so an admin-owned ACTIVE item isn't matched twice.
        Map<String, Equipment> pool = new LinkedHashMap<>();
        for (Equipment e : equipmentRepository.findCatalog(null, null, null)) {
            pool.putIfAbsent(e.equipmentId(), e);
        }
        for (Equipment e : equipmentRepository.findByOwner(userId)) {
            if (e.status() != EquipmentStatus.REJECTED) {
                pool.putIfAbsent(e.equipmentId(), e);
            }
        }
        List<Equipment> catalog = new ArrayList<>(pool.values());

        List<PreviewItem> items = new ArrayList<>(parsed.size());
        int matched = 0;
        int suggested = 0;
        int newSubmissions = 0;

        for (int i = 0; i < parsed.size(); i++) {
            ParsedEquipment p = parsed.get(i);
            MatchInfo match = bestMatch(p, catalog);
            Action action;
            if (match == null) {
                action = Action.CREATE_NEW;
                newSubmissions++;
            } else if (match.score() >= AUTO_MATCH_SCORE) {
                action = Action.MATCH_AUTO;
                matched++;
            } else {
                action = Action.MATCH_SUGGESTED;
                suggested++;
            }
            items.add(new PreviewItem(i, p, match, action));
        }

        PreviewSummary summary = new PreviewSummary(
            parsed.size(), matched, suggested, newSubmissions);
        return new PreviewResult(items, summary);
    }

    /**
     * Apply the user-confirmed import decisions. Creates new equipment
     * submissions via {@link EquipmentService#submitEquipment} for
     * {@code CREATE_NEW} items, collects matched equipment IDs for
     * {@code USE_MATCH} items, and ignores {@code SKIP} items.
     *
     * <p>Does NOT mutate any location — the returned
     * {@code equipmentIdsToAdd} list is what the controller should hand to
     * {@code LocationService}.
     */
    public ConfirmResult confirm(
        String userId,
        String locationId,
        List<ConfirmItem> items
    ) {
        List<CreatedItem> created = new ArrayList<>();
        List<MatchedItem> matched = new ArrayList<>();
        List<FailedItem> failed = new ArrayList<>();
        // Preserve order using a LinkedHashMap keyed by equipmentId to
        // dedupe in case the user picks the same match twice.
        Map<String, Boolean> idsInOrder = new LinkedHashMap<>();
        int skipped = 0;

        for (ConfirmItem item : items) {
            String action = item.confirmAction();
            if (action == null) {
                throw new IllegalArgumentException(
                    "confirmAction is required for item index=" + item.index());
            }
            switch (action) {
                case "SKIP" -> skipped++;
                case "USE_MATCH" -> {
                    try {
                        String id = item.matchedEquipmentId();
                        if (id == null || id.isBlank()) {
                            throw new IllegalArgumentException(
                                "matchedEquipmentId is required for USE_MATCH at index="
                                    + item.index());
                        }
                        Equipment existing = equipmentRepository.findById(id)
                            .orElseThrow(() -> new IllegalArgumentException(
                                "Equipment not found: " + id));
                        matched.add(new MatchedItem(existing.equipmentId(), existing.name()));
                        idsInOrder.putIfAbsent(existing.equipmentId(), Boolean.TRUE);
                    } catch (RuntimeException e) {
                        String name = item.parsed() != null
                            ? item.parsed().name()
                            : item.matchedEquipmentId();
                        failed.add(new FailedItem(item.index(), name, e.getMessage()));
                    }
                }
                case "CREATE_NEW" -> {
                    try {
                        ParsedEquipment p = item.parsed();
                        if (p == null) {
                            throw new IllegalArgumentException(
                                "parsed equipment is required for CREATE_NEW at index="
                                    + item.index());
                        }
                        String name = (item.overrides() != null
                                       && item.overrides().name() != null
                                       && !item.overrides().name().isBlank())
                            ? item.overrides().name()
                            : p.name();
                        Equipment eq = equipmentService.submitEquipment(
                            userId,
                            name,
                            p.category(),
                            p.subcategory(),
                            p.specSchema(),
                            p.specs()
                        );
                        created.add(new CreatedItem(
                            eq.equipmentId(), eq.name(), eq.status().name()));
                        idsInOrder.putIfAbsent(eq.equipmentId(), Boolean.TRUE);
                    } catch (RuntimeException e) {
                        String name = item.parsed() != null
                            ? item.parsed().name()
                            : item.matchedEquipmentId();
                        failed.add(new FailedItem(item.index(), name, e.getMessage()));
                    }
                }
                default -> throw new IllegalArgumentException(
                    "Unknown confirmAction '" + action + "' at index=" + item.index());
            }
        }

        return new ConfirmResult(
            created,
            matched,
            new ArrayList<>(idsInOrder.keySet()),
            skipped,
            failed
        );
    }

    // -- fuzzy matching ----------------------------------------------------

    private MatchInfo bestMatch(ParsedEquipment parsed, List<Equipment> catalog) {
        Set<String> parsedTokens = tokenize(parsed.name());
        if (parsedTokens.isEmpty()) {
            return null;
        }

        Equipment best = null;
        double bestScore = 0.0;

        for (Equipment candidate : catalog) {
            Set<String> candTokens = tokenize(candidate.name());
            if (candTokens.isEmpty()) {
                continue;
            }
            double score = jaccard(parsedTokens, candTokens);
            if (parsed.category() != null
                && parsed.category().equalsIgnoreCase(candidate.category())) {
                score += 0.10;
            }
            if (parsed.subcategory() != null
                && parsed.subcategory().equalsIgnoreCase(candidate.subcategory())) {
                score += 0.05;
            }
            score = Math.min(score, 1.0);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        if (best == null || bestScore < MIN_MATCH_SCORE) {
            return null;
        }
        String reason = bestScore >= AUTO_MATCH_SCORE ? "high-confidence fuzzy" : "fuzzy";
        return new MatchInfo(best.equipmentId(), best.name(), bestScore, reason);
    }

    private Set<String> tokenize(String s) {
        if (s == null) {
            return Set.of();
        }
        return Arrays.stream(s.toLowerCase()
                .replaceAll("[^a-z0-9 ]", " ")
                .split("\\s+"))
            .filter(t -> !t.isBlank() && !STOPWORDS.contains(t))
            .collect(Collectors.toSet());
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0.0;
        }
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) intersection.size() / union.size();
    }

    // -- nested types (public — used by API layer DTOs in Phase 4) --------

    public enum Action { MATCH_AUTO, MATCH_SUGGESTED, CREATE_NEW }

    public record MatchInfo(String equipmentId, String name, double score, String reason) {}

    public record PreviewItem(
        int index,
        ParsedEquipment parsed,
        MatchInfo match,
        Action action
    ) {}

    public record PreviewSummary(
        int total,
        int matched,
        int suggestedMatches,
        int newSubmissions
    ) {}

    public record PreviewResult(List<PreviewItem> items, PreviewSummary summary) {}

    public record ConfirmItem(
        int index,
        String confirmAction,
        String matchedEquipmentId,
        ParsedEquipment parsed,
        NameOverride overrides
    ) {}

    public record NameOverride(String name) {}

    public record CreatedItem(String equipmentId, String name, String status) {}

    public record MatchedItem(String equipmentId, String name) {}

    public record FailedItem(int index, String name, String reason) {}

    public record ConfirmResult(
        List<CreatedItem> created,
        List<MatchedItem> matched,
        List<String> equipmentIdsToAdd,
        int skipped,
        List<FailedItem> failed
    ) {}
}
