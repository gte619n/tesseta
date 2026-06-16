package com.gte619n.healthfitness.integrations.exercise;

import com.google.genai.Client;
import com.google.genai.types.GenerateVideosConfig;
import com.google.genai.types.GenerateVideosOperation;
import com.google.genai.types.GeneratedVideo;
import com.google.genai.types.Image;
import com.google.genai.types.Video;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Live-Veo preview of exercise demo-video generation (IMPL-15, ADR-0009).
 * Disabled in CI — only runs when {@code GEMINI_API_KEY} is set.
 *
 * <p>For each sample movement it generates TWO ~8s clips with Veo 3.1, both
 * image-to-video seeded from the same START still (so the actor + equipment are
 * identical), using {@link ExerciseVideoPrompt}:
 * <ul>
 *   <li>{@code View.SIDE} — locked side profile;</li>
 *   <li>{@code View.FRONT} — camera orbits side → front three-quarter.</li>
 * </ul>
 * The two clips are stitched (and trimmed to ~15s) with ffmpeg into
 * {@code <slug>_demo.mp4}, leaving the per-view {@code _side.mp4}/{@code _front.mp4}
 * for inspection. Output: {@code docs/test_reports/workout_logs/media_preview/}.
 *
 * <p>Run (slow + billed per second of video):
 * {@code GEMINI_API_KEY=… ./gradlew :integrations:test --tests *GeminiExerciseVideoPreview*}
 * Veo model overridable via {@code VEO_MODEL} (default {@code veo-3.1-generate-preview}).
 */
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
class GeminiExerciseVideoPreviewTest {

    private static final long POLL_MS = 10_000L;
    private static final int MAX_POLLS = 120;           // ~20 min ceiling per clip
    private static final int FINAL_SECONDS = 15;        // trim the stitched demo to ~15s

    private static Exercise exercise(String name, MovementPattern p, Laterality lat, List<String> cues) {
        Instant now = Instant.now();
        return new Exercise(
            slug(name), name, name.toLowerCase(), List.of(), p, List.of(), List.of(),
            lat, Mechanic.COMPOUND, null, cues, List.of(), List.of(), null, false,
            List.of(), null, null, ExerciseMediaStatus.NONE,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, now, now, null);
    }

    private static final List<Exercise> SAMPLES = List.of(
        exercise("Dumbbell Goblet Squat", MovementPattern.SQUAT, Laterality.BILATERAL,
            List.of("Hold the dumbbell vertically at your chest", "Keep your chest up and knees out")),
        exercise("Barbell Bench Press", MovementPattern.PUSH_HORIZONTAL, Laterality.BILATERAL,
            List.of("Retract your shoulder blades", "Lower the bar to mid-chest under control")),
        exercise("Single-Arm Dumbbell Row", MovementPattern.PULL_HORIZONTAL, Laterality.UNILATERAL,
            List.of("Keep your spine neutral", "Drive your elbow toward the ceiling")));

    @Test
    void previewDemoVideos() throws Exception {
        Client client = Client.builder().apiKey(System.getenv("GEMINI_API_KEY")).build();
        String model = System.getenv().getOrDefault("VEO_MODEL", "veo-3.1-generate-preview");
        Path dir = locate("docs/test_reports/workout_logs").resolve("media_preview");
        Files.createDirectories(dir);

        // Veo 3.1 clips are ~8s each; two views → ~16s, trimmed to ~15s on stitch.
        // (generateAudio isn't accepted by the Gemini API; the ffmpeg stitch drops
        // audio anyway, so the final demo is silent regardless.)
        GenerateVideosConfig config = GenerateVideosConfig.builder()
            .numberOfVideos(1)
            .aspectRatio("9:16")
            .resolution("1080p")
            .build();

        // Optional comma-separated slug filter (e.g. VIDEO_SAMPLE_SLUGS=barbell-bench-press)
        // to generate just some samples and limit spend.
        String only = System.getenv("VIDEO_SAMPLE_SLUGS");
        List<String> onlySlugs = (only == null || only.isBlank())
            ? List.of() : List.of(only.split(","));

        int done = 0;
        for (Exercise ex : SAMPLES) {
            String slug = slug(ex.name());
            if (!onlySlugs.isEmpty() && !onlySlugs.contains(slug)) {
                continue;
            }
            Path seed = dir.resolve(slug + "_start.png");
            if (!Files.exists(seed)) {
                System.out.println("SKIP " + slug + " — seed still missing: " + seed);
                continue;
            }
            Image seedImage = Image.builder()
                .imageBytes(Files.readAllBytes(seed)).mimeType("image/png").build();

            Path side = generate(client, model, config, seedImage,
                ExerciseVideoPrompt.build(ex, ExerciseVideoPrompt.View.SIDE), dir.resolve(slug + "_side.mp4"));
            Path front = generate(client, model, config, seedImage,
                ExerciseVideoPrompt.build(ex, ExerciseVideoPrompt.View.FRONT), dir.resolve(slug + "_front.mp4"));

            if (side == null || front == null) {
                System.out.println("INCOMPLETE " + slug + " — missing a view, skipping stitch");
                continue;
            }
            Path demo = dir.resolve(slug + "_demo.mp4");
            stitch(side, front, demo);
            done++;
            System.out.println("Wrote " + demo.toAbsolutePath()
                + " (" + (Files.exists(demo) ? Files.size(demo) : 0) + " bytes)");
        }
        System.out.println("Built " + done + " stitched demo video(s) in " + dir.toAbsolutePath());
    }

    /** Generate one clip (image-to-video), poll to completion, write to out. Returns out or null. */
    private Path generate(Client client, String model, GenerateVideosConfig config,
                          Image seed, String prompt, Path out) throws Exception {
        if (Files.exists(out) && Files.size(out) > 0) {
            System.out.println("REUSE " + out.getFileName() + " (" + Files.size(out) + " bytes)");
            return out;     // don't re-pay for a clip already generated
        }
        System.out.println("Generating " + out.getFileName() + " with " + model + " …");
        GenerateVideosOperation op = client.models.generateVideos(model, prompt, seed, config);
        int polls = 0;
        while (!op.done().orElse(false) && polls++ < MAX_POLLS) {
            Thread.sleep(POLL_MS);
            op = client.operations.getVideosOperation(op, null);
        }
        if (!op.done().orElse(false)) {
            System.out.println("TIMEOUT " + out.getFileName());
            return null;
        }
        if (op.error().isPresent()) {
            System.out.println("ERROR " + out.getFileName() + ": " + op.error().get());
            return null;
        }
        Optional<Video> video = op.response()
            .flatMap(r -> r.generatedVideos())
            .filter(v -> !v.isEmpty())
            .map(List::getFirst)
            .flatMap(GeneratedVideo::video);
        if (video.isEmpty()) {
            System.out.println("NO VIDEO " + out.getFileName());
            return null;
        }
        Video v = video.get();
        if (v.videoBytes().isPresent()) {
            Files.write(out, v.videoBytes().get());
        } else {
            client.files.download(v, out.toString(), null);
        }
        System.out.println("Wrote " + out.getFileName() + " (" + Files.size(out) + " bytes)");
        return out;
    }

    /** Concatenate two clips into one ~15s video (video-only re-encode) via ffmpeg. */
    private void stitch(Path side, Path front, Path out) throws Exception {
        Process p = new ProcessBuilder(
            "ffmpeg", "-y",
            "-i", side.toString(),
            "-i", front.toString(),
            "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
            "-map", "[v]",
            "-t", String.valueOf(FINAL_SECONDS),
            "-c:v", "libx264", "-pix_fmt", "yuv420p",
            out.toString())
            .redirectErrorStream(true)
            .start();
        new String(p.getInputStream().readAllBytes());     // drain
        int code = p.waitFor();
        if (code != 0) {
            System.out.println("ffmpeg stitch failed (exit " + code + ") for " + out.getFileName());
        }
    }

    private static String slug(String name) {
        return name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
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
