package com.gte619n.healthfitness.integrations.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import com.gte619n.healthfitness.config.JsonSupport;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseReference;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.FrameSpec;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.google.genai.Client;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * IMPL-19 live preview: for 25 real exercises, run the reference-grounded frame
 * PLANNER ({@code gemini-3.5-flash}) and then GENERATE the planned demo frames
 * with the real image model ({@code gemini-3.1-flash-image-preview}), writing
 * everything to disk for human review. No production Firestore/GCS mutation —
 * repo/storage are null and bytes are written to
 * {@code docs/test_reports/workout_logs/impl19_preview/}.
 *
 * <p>Same package as {@link GeminiExerciseMediaService} so it can call the
 * package-private {@code buildPrompt(exercise, FrameSpec, override, grounded)} +
 * {@code callGemini(prompt, RefImage, exerciseId)} directly, replicating the
 * service's grounded generation path inline (resolve grounding → map frame to
 * reference by order → buildPrompt → callGemini) and writing bytes to disk
 * instead of GCS.
 *
 * <p>Run: {@code GEMINI_API_KEY=… ./gradlew test --tests *Impl19FramePreview*}
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class Impl19FramePreviewTest {

    @Test
    void previewPlanAndFrames() throws Exception {
        String apiKey = System.getenv("GEMINI_API_KEY");
        Client client = Client.builder().apiKey(apiKey).build();

        GeminiExerciseFramePlanner planner =
            new GeminiExerciseFramePlanner(Optional.of(client), "gemini-3.5-flash");

        GroundingImageResolver grounding = new GroundingImageResolver(null, "test-exercise-media", true);

        // Null repo/storage: we never call any method that writes to Firestore/GCS.
        GeminiExerciseMediaService media = new GeminiExerciseMediaService(
            null, null, grounding, client, "gemini-3.1-flash-image-preview", true);

        Path baseDir = locate("docs/test_reports/workout_logs").resolve("impl19_preview");
        Files.createDirectories(baseDir);

        JsonNode rows = JsonSupport.LENIENT.readTree(baseDir.resolve("input_25.json").toFile());

        // Optional comma-separated slug filter (mirrors MEDIA_SAMPLE_SLUGS in
        // GeminiExerciseMediaPreviewTest): when IMPL19_ONLY is set, only the named
        // exercises (by slug = lowercased name, non-alnum -> '-') are processed, so a
        // targeted subset can be regenerated without rerunning all 25.
        String only = System.getenv("IMPL19_ONLY");
        List<String> onlySlugs = new ArrayList<>();
        if (only != null && !only.isBlank()) {
            for (String s : only.toLowerCase().split(",")) {
                if (!s.isBlank()) {
                    onlySlugs.add(s.trim());
                }
            }
        }

        int total = rows.size();
        int planned = 0;
        int totalFrames = 0;
        int failedFrames = 0;
        int groundedFrames = 0;
        List<Row> reportRows = new ArrayList<>();

        for (JsonNode row : rows) {
            Exercise exercise = exerciseFor(row);
            String slug = slug(exercise.name());
            if (!onlySlugs.isEmpty() && !onlySlugs.contains(slug)) {
                continue;
            }
            Path outDir = baseDir.resolve(slug);
            Files.createDirectories(outDir);
            Row report = new Row(exercise.name(),
                exercise.movementPattern() == null ? "" : exercise.movementPattern().name(),
                exercise.reference() == null ? "(none)" : exercise.reference().source());

            // a. Plan.
            List<FrameSpec> plan;
            try {
                plan = planner.plan(exercise, null);
            } catch (Exception e) {
                System.out.println("SKIP plan " + exercise.name() + ": " + e.getMessage());
                report.planError = e.getMessage();
                reportRows.add(report);
                continue;
            }
            if (plan == null || plan.isEmpty()) {
                System.out.println("SKIP plan " + exercise.name() + ": empty");
                report.planError = "empty plan";
                reportRows.add(report);
                continue;
            }
            planned++;
            Files.writeString(outDir.resolve("plan.json"), planJson(plan));

            // b. Exercise copy WITH the planned frames as demoPlan.
            Exercise planned9 = withPlan(exercise, plan);

            // c. Resolve grounding once (mirrors service.resolveGrounding), then
            //    generate every planned frame via the SERVICE's grounded path.
            List<GroundingImageResolver.RefImage> refs =
                planned9.reference() == null ? List.of() : grounding.imagesFor(planned9.reference());
            boolean usedGroundingForExercise = false;

            for (FrameSpec spec : plan) {
                FrameItem item = new FrameItem(spec.key(), spec.label(), spec.caption());
                try {
                    GroundingImageResolver.RefImage ref = referenceFor(plan, spec, refs);
                    boolean grounded = ref != null;
                    String prompt = media.buildPrompt(planned9, spec, null, grounded);
                    byte[] bytes = media.callGemini(prompt, ref, planned9.exerciseId());
                    if (bytes == null || bytes.length == 0) {
                        System.out.println("FAILED " + exercise.name() + " " + spec.key() + ": null/empty bytes");
                        item.failure = "null/empty bytes";
                        failedFrames++;
                    } else {
                        String ext = sniffExt(bytes);
                        String file = spec.order() + "_" + safe(spec.key()) + "." + ext;
                        Files.write(outDir.resolve(file), bytes);
                        item.file = file;
                        item.grounded = grounded;
                        totalFrames++;
                        if (grounded) {
                            groundedFrames++;
                            usedGroundingForExercise = true;
                        }
                        System.out.println("Wrote " + outDir.resolve(file) + " (" + bytes.length
                            + " bytes" + (grounded ? ", grounded" : "") + ")");
                    }
                } catch (Exception e) {
                    System.out.println("FAILED " + exercise.name() + " " + spec.key() + ": " + e.getMessage());
                    item.failure = e.getMessage();
                    failedFrames++;
                }
                report.frames.add(item);
            }
            report.usedGrounding = usedGroundingForExercise;
            reportRows.add(report);
        }

        writeIndex(baseDir, reportRows, total, planned, totalFrames, failedFrames, groundedFrames);

        long groundedExercises = reportRows.stream().filter(r -> r.usedGrounding).count();
        System.out.println("==== IMPL-19 PREVIEW SUMMARY ====");
        System.out.println("Planned:        " + planned + "/" + total);
        System.out.println("Total frames:   " + totalFrames);
        System.out.println("Failed frames:  " + failedFrames);
        System.out.println("Grounded frames:" + groundedFrames + " (across " + groundedExercises + " exercises)");
        System.out.println("Output:         " + baseDir.toAbsolutePath());
    }

    // ---- frame → reference mapping (mirrors GeminiExerciseMediaService.referenceFor) ----

    private static GroundingImageResolver.RefImage referenceFor(
        List<FrameSpec> plan, FrameSpec spec, List<GroundingImageResolver.RefImage> refs) {
        if (refs == null || refs.isEmpty() || plan == null || plan.isEmpty()) {
            return null;
        }
        if (refs.size() == 1) {
            return refs.get(0);
        }
        int idx = Math.max(0, plan.indexOf(spec));
        int last = plan.size() - 1;
        if (idx <= 0) {
            return refs.get(0);
        }
        if (idx >= last) {
            return refs.get(refs.size() - 1);
        }
        int mapped = Math.round((float) idx / last * (refs.size() - 1));
        mapped = Math.min(refs.size() - 1, Math.max(0, mapped));
        return refs.get(mapped);
    }

    // ---- Exercise construction ----

    private static Exercise exerciseFor(JsonNode row) {
        Instant now = Instant.now();
        String name = textOr(row, "name", "Exercise");
        return new Exercise(
            textOr(row, "exerciseId", slug(name)),
            name,
            name.toLowerCase(),
            List.of(),
            enumOr(MovementPattern.class, text(row, "movementPattern"), MovementPattern.OTHER),
            List.of(),
            List.of(),
            enumOr(Laterality.class, text(row, "laterality"), Laterality.BILATERAL),
            enumOr(Mechanic.class, text(row, "mechanic"), Mechanic.COMPOUND),
            text(row, "description"),
            stringList(row.get("formCues")),
            List.of(),
            List.of(),
            null,
            row.path("isTimed").asBoolean(false),
            List.of(),                          // demoFrames
            null,                               // videoUrl
            null,                               // demoPromptOverride
            ExerciseMediaStatus.NONE,           // mediaStatus
            null,                               // demoPlan (filled after planning)
            ExerciseMediaStatus.NONE,           // planStatus
            referenceFor(row.get("reference")), // reference
            ExerciseStatus.PUBLISHED,
            null,
            now,
            now,
            null,
            false,                              // reviewed
            List.of());                         // groundingImageUrls
    }

    private static ExerciseReference referenceFor(JsonNode r) {
        if (r == null || r.isNull()) {
            return null;
        }
        Double score = r.has("score") && !r.get("score").isNull() ? r.get("score").asDouble() : null;
        return new ExerciseReference(
            text(r, "url"),
            text(r, "source"),
            text(r, "name"),
            score,
            text(r, "match"),
            stringList(r.get("images")),
            stringList(r.get("groundingImages")));
    }

    /** Rebuild the Exercise with demoPlan set (record has no wither). */
    private static Exercise withPlan(Exercise e, List<FrameSpec> plan) {
        return new Exercise(
            e.exerciseId(), e.name(), e.nameLower(), e.aliases(),
            e.movementPattern(), e.primaryMuscles(), e.secondaryMuscles(),
            e.laterality(), e.mechanic(), e.description(), e.formCues(),
            e.requiredEquipment(), e.suitableBlockTypes(), e.defaultRepRange(),
            e.isTimed(), e.demoFrames(), e.videoUrl(), e.demoPromptOverride(),
            e.mediaStatus(), plan, ExerciseMediaStatus.NEEDS_REVIEW, e.reference(),
            e.status(), e.contributorId(), e.createdAt(), e.updatedAt(),
            e.aliasOfExerciseId(), e.reviewed(), e.groundingImageUrls());
    }

    // ---- report ----

    private static final class Row {
        final String name;
        final String pattern;
        final String refSource;
        String planError;
        boolean usedGrounding;
        final List<FrameItem> frames = new ArrayList<>();

        Row(String name, String pattern, String refSource) {
            this.name = name;
            this.pattern = pattern;
            this.refSource = refSource;
        }
    }

    private static final class FrameItem {
        final String key;
        final String label;
        final String caption;
        String file;
        String failure;
        boolean grounded;

        FrameItem(String key, String label, String caption) {
            this.key = key;
            this.label = label;
            this.caption = caption;
        }
    }

    private static void writeIndex(
        Path baseDir, List<Row> rows, int total, int planned,
        int totalFrames, int failedFrames, int groundedFrames) throws Exception {
        long groundedExercises = rows.stream().filter(r -> r.usedGrounding).count();
        StringBuilder sb = new StringBuilder();
        sb.append("# IMPL-19 dynamic demo-frame preview\n\n");
        sb.append("Live planner (`gemini-3.5-flash`) + grounded image generation ")
            .append("(`gemini-3.1-flash-image-preview`) over ").append(total)
            .append(" real exercises. No production Firestore/GCS mutation.\n\n");
        sb.append("- **Planned:** ").append(planned).append('/').append(total).append('\n');
        sb.append("- **Total frames generated:** ").append(totalFrames).append('\n');
        sb.append("- **Failed frames:** ").append(failedFrames).append('\n');
        sb.append("- **Grounded frames:** ").append(groundedFrames)
            .append(" (across ").append(groundedExercises).append(" exercises)\n\n");

        sb.append("| Exercise | Pattern | Ref | Frames | Detail |\n");
        sb.append("|---|---|---|---|---|\n");
        for (Row r : rows) {
            sb.append("| ").append(esc(r.name)).append(" | ").append(r.pattern)
                .append(" | ").append(r.refSource).append(" | ");
            if (r.planError != null) {
                sb.append("PLAN FAILED").append(" | ").append(esc(r.planError)).append(" |\n");
                continue;
            }
            sb.append(r.frames.size()).append(r.usedGrounding ? " (grounded)" : "").append(" | ");
            StringBuilder detail = new StringBuilder();
            for (FrameItem f : r.frames) {
                if (detail.length() > 0) {
                    detail.append("<br>");
                }
                detail.append("`").append(f.key).append("` — ")
                    .append(esc(f.label)).append(" — ").append(esc(f.caption)).append(" — ");
                if (f.failure != null) {
                    detail.append("**FAILED**: ").append(esc(f.failure));
                } else {
                    detail.append(f.file).append(f.grounded ? " (grounded)" : "");
                }
            }
            sb.append(detail).append(" |\n");
        }
        Files.writeString(baseDir.resolve("index.md"), sb.toString());
    }

    // ---- helpers ----

    private static String planJson(List<FrameSpec> plan) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < plan.size(); i++) {
            FrameSpec s = plan.get(i);
            sb.append("  {\n")
                .append("    \"key\": ").append(jstr(s.key())).append(",\n")
                .append("    \"order\": ").append(s.order()).append(",\n")
                .append("    \"label\": ").append(jstr(s.label())).append(",\n")
                .append("    \"caption\": ").append(jstr(s.caption())).append(",\n")
                .append("    \"positionPrompt\": ").append(jstr(s.positionPrompt())).append("\n")
                .append("  }").append(i < plan.size() - 1 ? "," : "").append("\n");
        }
        return sb.append("]\n").toString();
    }

    private static String jstr(String s) {
        if (s == null) {
            return "null";
        }
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "") + "\"";
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String sniffExt(byte[] b) {
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
            && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "webp";
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
            return "jpg";
        }
        return "png";
    }

    private static String safe(String key) {
        return key == null ? "frame" : key.replaceAll("[^a-zA-Z0-9._-]+", "-");
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        String v = text(n, field);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static List<String> stringList(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isNull()) {
                out.add(n.asText());
            }
        }
        return out;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, String name, E fallback) {
        if (name == null || name.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static Path locate(String relative) {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("could not locate " + relative);
    }
}
