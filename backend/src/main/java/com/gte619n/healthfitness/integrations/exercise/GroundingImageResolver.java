package com.gte619n.healthfitness.integrations.exercise;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.gte619n.healthfitness.config.JsonSupport;
import com.gte619n.healthfitness.core.exercise.ExerciseReference;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Resolves public-library reference pose image bytes for an exercise (IMPL-19),
 * transiently and best-effort. The bytes are fed to the image model as a pose
 * reference at generation time and <em>never</em> persisted or displayed.
 *
 * <p>By source:
 * <ul>
 *   <li><b>fedb</b> — {@link ExerciseReference#images()} are direct GitHub-Pages
 *       jpg URLs (a start/end pose pair); fetched as-is.</li>
 *   <li><b>yoga</b> — {@link ExerciseReference#url()} is a Wikipedia article; the
 *       lead image is resolved via the Wikimedia REST summary API
 *       ({@code /api/rest_v1/page/summary/<title>}).</li>
 *   <li><b>jefit / rb100</b> — best-effort GET of the page, scrape the primary
 *       exercise {@code <img>}; swallowed if blocked.</li>
 * </ul>
 *
 * <p>If {@link ExerciseReference#groundingImages()} is already populated those
 * URLs are used directly (the planner may have cached them). Nothing here ever
 * throws; on any failure the resolver logs and returns whatever it has.
 */
@Component
public class GroundingImageResolver {

    private static final Logger log = LoggerFactory.getLogger(GroundingImageResolver.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(6);
    private static final long MAX_IMAGE_BYTES = 8L * 1024 * 1024;
    private static final String USER_AGENT =
        "Mozilla/5.0 (compatible; TessetaExerciseBot/1.0; +https://tesseta.app)";

    // Pull the og:image first (most reliable primary image), else first <img src>.
    private static final Pattern OG_IMAGE = Pattern.compile(
        "<meta[^>]+property=[\"']og:image[\"'][^>]+content=[\"']([^\"']+)[\"']",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern IMG_SRC = Pattern.compile(
        "<img[^>]+src=[\"']([^\"']+\\.(?:jpe?g|png|webp)[^\"']*)[\"']",
        Pattern.CASE_INSENSITIVE);

    private final boolean enabled;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Storage storage;
    private final String ownBucket;
    private final String ownBucketUrlPrefix;

    public GroundingImageResolver(
        Storage storage,
        @Value("${app.exercises.bucket}") String ownBucket,
        @Value("${app.exercises.media.grounding-enabled:true}") boolean enabled
    ) {
        this.enabled = enabled;
        this.storage = storage;
        this.ownBucket = ownBucket;
        this.ownBucketUrlPrefix = "https://storage.googleapis.com/" + ownBucket + "/";
        this.http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        this.json = JsonSupport.LENIENT;
    }

    /** A resolved reference pose image: raw bytes plus the mime to decode them. */
    public record RefImage(byte[] bytes, String mime) {}

    /**
     * Best-effort ordered list of reference pose image bytes for the exercise.
     * Empty if grounding is disabled, the reference is null, or nothing
     * resolves. Never throws.
     */
    public List<RefImage> imagesFor(ExerciseReference ref) {
        if (!enabled || ref == null) {
            return List.of();
        }
        try {
            List<String> urls = resolveUrls(ref);
            List<RefImage> out = new ArrayList<>();
            for (String url : urls) {
                byte[] bytes = fetchBytes(url);
                if (bytes != null && bytes.length > 0) {
                    out.add(new RefImage(bytes, mimeForUrl(url)));
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Grounding image resolution failed for {}: {}",
                ref.url(), e.getMessage());
            return List.of();
        }
    }

    /**
     * Best-effort ordered list of reference pose image bytes for an explicit set
     * of grounding URLs (IMPL-20). Each URL is resolved independently:
     * <ul>
     *   <li><b>Own-object</b> — a URL pointing at the exercise-media bucket
     *       (host {@code storage.googleapis.com}, path starting with the bucket
     *       name) is fetched directly from GCS as bytes.</li>
     *   <li><b>External</b> — any other URL is fetched over HTTP, exactly like an
     *       already-resolved IMPL-19 reference image (fedb / Wikimedia direct
     *       image URLs).</li>
     * </ul>
     * Order is preserved so the generator's frame→image mapping is stable.
     * Empty if grounding is disabled or {@code urls} is null/empty. Never throws.
     */
    public List<RefImage> imagesForUrls(List<String> urls) {
        if (!enabled || urls == null || urls.isEmpty()) {
            return List.of();
        }
        List<RefImage> out = new ArrayList<>();
        for (String url : urls) {
            if (url == null || url.isBlank()) {
                continue;
            }
            try {
                byte[] bytes = isOwnObjectUrl(url) ? fetchOwnObjectBytes(url) : fetchBytes(url);
                if (bytes != null && bytes.length > 0) {
                    out.add(new RefImage(bytes, mimeForUrl(url)));
                }
            } catch (Exception e) {
                log.debug("Grounding URL resolution failed for {}: {}", url, e.getMessage());
            }
        }
        return out;
    }

    /**
     * True when {@code url} points at our own exercise-media bucket: the GCS
     * public host with a path under the configured bucket name. These are
     * fetched directly from GCS rather than over HTTP.
     */
    boolean isOwnObjectUrl(String url) {
        return url != null && url.startsWith(ownBucketUrlPrefix);
    }

    /** Read an own-bucket object's bytes directly from GCS (strips any query). */
    private byte[] fetchOwnObjectBytes(String url) {
        String objectName = url.substring(ownBucketUrlPrefix.length());
        int q = objectName.indexOf('?');
        if (q >= 0) {
            objectName = objectName.substring(0, q);
        }
        if (objectName.isBlank()) {
            return null;
        }
        try {
            Blob blob = storage.get(BlobId.of(ownBucket, objectName));
            if (blob == null || !blob.exists()) {
                log.debug("Own-object grounding image not found in GCS: {}", objectName);
                return null;
            }
            byte[] bytes = blob.getContent();
            if (bytes != null && bytes.length > 0 && bytes.length <= MAX_IMAGE_BYTES) {
                return bytes;
            }
        } catch (Exception e) {
            log.debug("Own-object grounding GCS read failed for {}: {}", objectName, e.getMessage());
        }
        return null;
    }

    // ---- URL resolution ----

    private List<String> resolveUrls(ExerciseReference ref) {
        // Cached/pre-resolved grounding URLs win.
        if (ref.groundingImages() != null && !ref.groundingImages().isEmpty()) {
            return ref.groundingImages();
        }
        String source = ref.source() == null ? "" : ref.source().toLowerCase();
        return switch (source) {
            case "fedb" -> ref.images() == null ? List.of() : ref.images();
            case "yoga" -> wikipediaLeadImage(ref.url());
            case "jefit", "rb100" -> scrapePageImage(ref.url());
            default -> ref.images() != null && !ref.images().isEmpty()
                ? ref.images() : scrapePageImage(ref.url());
        };
    }

    /** Wikimedia REST summary API → originalimage/thumbnail source URL. */
    private List<String> wikipediaLeadImage(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            return List.of();
        }
        try {
            String title = articleUrl;
            int wiki = title.indexOf("/wiki/");
            if (wiki >= 0) {
                title = title.substring(wiki + "/wiki/".length());
            }
            int hash = title.indexOf('#');
            if (hash >= 0) {
                title = title.substring(0, hash);
            }
            String encoded = URLEncoder.encode(title, StandardCharsets.UTF_8).replace("+", "%20");
            String api = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encoded;
            String body = fetchString(api);
            if (body == null) {
                return List.of();
            }
            JsonNode root = json.readTree(body);
            JsonNode original = root.path("originalimage").path("source");
            if (original.isTextual()) {
                return List.of(original.asText());
            }
            JsonNode thumb = root.path("thumbnail").path("source");
            if (thumb.isTextual()) {
                return List.of(thumb.asText());
            }
        } catch (Exception e) {
            log.debug("Wikipedia lead image resolution failed for {}: {}", articleUrl, e.getMessage());
        }
        return List.of();
    }

    /** Best-effort scrape of a jefit/rb100 page's primary exercise image. */
    private List<String> scrapePageImage(String pageUrl) {
        if (pageUrl == null || pageUrl.isBlank()) {
            return List.of();
        }
        try {
            String html = fetchString(pageUrl);
            if (html == null) {
                return List.of();
            }
            Matcher og = OG_IMAGE.matcher(html);
            if (og.find()) {
                return List.of(absolutize(og.group(1), pageUrl));
            }
            Matcher img = IMG_SRC.matcher(html);
            if (img.find()) {
                return List.of(absolutize(img.group(1), pageUrl));
            }
        } catch (Exception e) {
            log.debug("Page image scrape failed for {}: {}", pageUrl, e.getMessage());
        }
        return List.of();
    }

    private static String absolutize(String src, String pageUrl) {
        if (src == null) {
            return null;
        }
        if (src.startsWith("//")) {
            return "https:" + src;
        }
        if (src.startsWith("http")) {
            return src;
        }
        try {
            return URI.create(pageUrl).resolve(src).toString();
        } catch (Exception e) {
            return src;
        }
    }

    // ---- HTTP ----

    private String fetchString(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/json,*/*")
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 == 2) {
                return resp.body();
            }
            log.debug("Grounding GET {} returned HTTP {}", url, resp.statusCode());
        } catch (Exception e) {
            log.debug("Grounding GET {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    private byte[] fetchBytes(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "image/*,*/*")
                .GET()
                .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 == 2) {
                byte[] body = resp.body();
                if (body != null && body.length > 0 && body.length <= MAX_IMAGE_BYTES) {
                    return body;
                }
            } else {
                log.debug("Grounding image GET {} returned HTTP {}", url, resp.statusCode());
            }
        } catch (Exception e) {
            log.debug("Grounding image GET {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Best-guess mime for an image url (genai needs an explicit type). */
    public static String mimeForUrl(String url) {
        String u = url == null ? "" : url.toLowerCase();
        if (u.contains(".png")) {
            return "image/png";
        }
        if (u.contains(".webp")) {
            return "image/webp";
        }
        return "image/jpeg";
    }
}
