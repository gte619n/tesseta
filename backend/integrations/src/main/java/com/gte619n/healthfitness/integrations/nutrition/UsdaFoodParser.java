package com.gte619n.healthfitness.integrations.nutrition;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import java.io.BufferedReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/**
 * Parses USDA FoodData Central (Foundation + SR Legacy) nutrition data, in a
 * flattened CSV form, into {@link CatalogFood} rows for bulk seeding
 * (IMPL-13 Milestone 2, ADR-0006).
 *
 * <p>USDA values are already per-100 g and lab-grade, so seeded rows are tagged
 * {@code source = USDA}, {@code status = VERIFIED}, with a deterministic
 * {@code "usda-" + fdcId} id and {@code sourceRef = "usda-" + fdcId}. USDA data
 * is CC0 — no attribution obligation, unlike Open Food Facts.
 *
 * <p>Expects a header row. Columns are matched case-insensitively by name; the
 * canonical FDC abbreviated export and a pre-flattened export are both
 * supported via the alias table below. Required: an fdc-id column and a
 * description column. Recognised nutrient columns (any subset):
 * <ul>
 *   <li>energy / calories (kcal)</li>
 *   <li>protein (g)</li>
 *   <li>carbohydrate (g)</li>
 *   <li>total fat (g)</li>
 *   <li>fiber (g)</li>
 *   <li>sugars (g)</li>
 * </ul>
 *
 * <p>Streams row by row and emits each food to the supplied consumer, so it is
 * unit-testable independent of Firestore and the seed job.
 */
@Component
public class UsdaFoodParser {

    private static final System.Logger log =
        System.getLogger(UsdaFoodParser.class.getName());

    // Header aliases (lower-cased). First match wins.
    private static final List<String> FDC_ID = List.of("fdc_id", "fdcid", "ndb_no", "id");
    private static final List<String> DESCRIPTION = List.of("description", "shrt_desc", "desc", "name", "food");
    private static final List<String> CATEGORY = List.of("food_category", "category", "food_group", "category_id");
    private static final List<String> ENERGY = List.of("energy_kcal", "energ_kcal", "energy-kcal", "energy", "calories", "kcal");
    private static final List<String> PROTEIN = List.of("protein_g", "protein");
    private static final List<String> CARBS = List.of("carbs_g", "carbohydrate_g", "carbohydrate", "carbohydrt", "carbs");
    private static final List<String> FAT = List.of("fat_g", "total_fat", "lipid_tot", "total_lipid", "fat");
    private static final List<String> FIBER = List.of("fiber_g", "fiber_td", "fiber", "fibtg");
    private static final List<String> SUGAR = List.of("sugar_g", "sugars_g", "sugar_tot", "sugars", "sugar");

    /**
     * Stream a flattened USDA CSV, emitting one {@link CatalogFood} per row.
     *
     * @param reader   CSV source with a header row (caller closes)
     * @param consumer receives each parsed food (e.g. {@code repository::save})
     * @return the number of foods emitted
     */
    public long parse(BufferedReader reader, Consumer<CatalogFood> consumer) {
        long emitted = 0;
        try {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                return 0;
            }
            List<String> header = splitCsv(headerLine);
            Map<String, Integer> index = resolveColumns(header);
            if (!index.containsKey("fdcId") || !index.containsKey("description")) {
                log.log(System.Logger.Level.WARNING,
                    "USDA CSV missing fdc-id or description column; got header {0}", header);
                return 0;
            }

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Optional<CatalogFood> food = parseRow(splitCsv(line), index);
                if (food.isPresent()) {
                    consumer.accept(food.get());
                    emitted++;
                }
            }
        } catch (Exception e) {
            log.log(System.Logger.Level.WARNING, "USDA CSV parse stopped: " + e.getMessage());
        }
        return emitted;
    }

    Optional<CatalogFood> parseRow(List<String> cols, Map<String, Integer> index) {
        String fdcId = get(cols, index, "fdcId");
        String description = get(cols, index, "description");
        if (fdcId == null || fdcId.isBlank() || description == null || description.isBlank()) {
            return Optional.empty();
        }
        Macros macros = new Macros(
            num(get(cols, index, "energy")),
            num(get(cols, index, "protein")),
            num(get(cols, index, "carbs")),
            num(get(cols, index, "fat")),
            num(get(cols, index, "fiber")),
            num(get(cols, index, "sugar"))
        );
        String category = get(cols, index, "category");
        String id = "usda-" + fdcId.trim();
        String name = description.trim();
        return Optional.of(new CatalogFood(
            id,
            name,
            name.toLowerCase(),
            null,
            null,
            (category != null && !category.isBlank()) ? category.trim() : null,
            macros,
            List.of(new ServingSize("100 g", 100.0)),
            0,
            FoodSource.USDA,
            id,
            FoodStatus.VERIFIED,
            0,
            null,
            null,
            FoodImageStatus.NONE,
            null,
            null,
            null
        ));
    }

    private Map<String, Integer> resolveColumns(List<String> header) {
        List<String> lower = new ArrayList<>(header.size());
        for (String h : header) {
            lower.add(h == null ? "" : h.trim().toLowerCase());
        }
        Map<String, Integer> index = new HashMap<>();
        putFirst(index, "fdcId", lower, FDC_ID);
        putFirst(index, "description", lower, DESCRIPTION);
        putFirst(index, "category", lower, CATEGORY);
        putFirst(index, "energy", lower, ENERGY);
        putFirst(index, "protein", lower, PROTEIN);
        putFirst(index, "carbs", lower, CARBS);
        putFirst(index, "fat", lower, FAT);
        putFirst(index, "fiber", lower, FIBER);
        putFirst(index, "sugar", lower, SUGAR);
        return index;
    }

    private static void putFirst(
        Map<String, Integer> index, String key, List<String> header, List<String> aliases
    ) {
        for (String alias : aliases) {
            int pos = header.indexOf(alias);
            if (pos >= 0) {
                index.put(key, pos);
                return;
            }
        }
    }

    private static String get(List<String> cols, Map<String, Integer> index, String key) {
        Integer pos = index.get(key);
        if (pos == null || pos >= cols.size()) {
            return null;
        }
        return cols.get(pos);
    }

    private static Double num(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Minimal RFC-4180-ish CSV split: handles double-quoted fields with commas and "" escapes. */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }
}
