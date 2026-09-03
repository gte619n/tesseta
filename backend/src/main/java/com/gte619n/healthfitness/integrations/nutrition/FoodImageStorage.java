package com.gte619n.healthfitness.integrations.nutrition;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.gte619n.healthfitness.core.nutrition.FoodImageStore;
import com.gte619n.healthfitness.core.nutrition.MealPhotoReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stores generated catalog-food studio images in Google Cloud Storage, mirroring
 * {@code DrugImageStorage}. Reuses the existing nutrition bucket
 * ({@code app.nutrition.bucket}) under a distinct {@code food-images/} prefix so
 * no new bucket is introduced — raw meal photos live under {@code nutrition/}
 * (see {@link MealPhotoStorage}). Generated images are written to:
 * <pre>gs://{bucket}/food-images/{foodId}.png</pre>
 * and served via the public URL.
 *
 * <p>Also implements {@link MealPhotoReader} so the studio generator can read
 * back a user's meal photo (stored by {@link MealPhotoStorage} as a public URL)
 * to use as a visual reference.
 *
 * <p>Gated by {@code app.nutrition.images.enabled} (default true) so unit-test
 * contexts skip the GCS bean that would otherwise authenticate at construction
 * time — the same pattern {@link MealPhotoStorage} uses for
 * {@code app.nutrition.capture.enabled}. GCS errors are wrapped in
 * {@link FoodImageStorageException}; reads fail soft (empty).
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.images.enabled", havingValue = "true", matchIfMissing = true)
public class FoodImageStorage implements FoodImageStore, MealPhotoReader {

    private static final Logger log = LoggerFactory.getLogger(FoodImageStorage.class);
    private static final String PREFIX = "food-images/";
    /** Tiny key→url pointer objects backing the content cache (IMPL-13 M4 reuse). */
    private static final String INDEX_PREFIX = "food-images/index/";
    /** A generated image is expensive; retry a transient upload blip before losing it. */
    private static final int UPLOAD_ATTEMPTS = 3;

    private final Storage storage;
    private final String bucket;

    public FoodImageStorage(Storage storage, @Value("${app.nutrition.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    @Override
    public String upload(String foodId, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new FoodImageStorageException("image bytes are empty");
        }
        String objectName = PREFIX + safeId(foodId) + ".png";
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType("image/png")
            .setCacheControl("public, max-age=31536000, immutable")
            .build();
        try {
            createWithRetry(info, imageBytes);
            return publicUrl(objectName);
        } catch (RuntimeException e) {
            log.warn("Failed to store food image at {}: {}", objectName, e.getMessage());
            throw new FoodImageStorageException("failed to store food image", e);
        }
    }

    @Override
    public Optional<String> findCachedUrl(String cacheKey) {
        if (cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        String objectName = INDEX_PREFIX + sha256Hex(cacheKey) + ".txt";
        try {
            Blob blob = storage.get(BlobId.of(bucket, objectName));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            byte[] bytes = blob.getContent();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            String url = new String(bytes, StandardCharsets.UTF_8).trim();
            return url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (RuntimeException e) {
            log.warn("Food image cache lookup failed for {}: {}", objectName, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void putCachedUrl(String cacheKey, String url) {
        if (cacheKey == null || cacheKey.isBlank() || url == null || url.isBlank()) {
            return;
        }
        String objectName = INDEX_PREFIX + sha256Hex(cacheKey) + ".txt";
        try {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType("text/plain; charset=utf-8")
                .build();
            storage.create(info, url.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Best-effort: a failed pointer write just means the next identical
            // subject regenerates instead of reusing this image.
            log.warn("Food image cache write failed for {}: {}", objectName, e.getMessage());
        }
    }

    @Override
    public Optional<String> findCachedText(String namespace, String cacheKey) {
        if (namespace == null || namespace.isBlank() || cacheKey == null || cacheKey.isBlank()) {
            return Optional.empty();
        }
        String objectName = textCachePath(namespace, cacheKey);
        try {
            Blob blob = storage.get(BlobId.of(bucket, objectName));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            byte[] bytes = blob.getContent();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            String value = new String(bytes, StandardCharsets.UTF_8).trim();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        } catch (RuntimeException e) {
            log.warn("Text cache lookup failed for {}: {}", objectName, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void putCachedText(String namespace, String cacheKey, String value) {
        if (namespace == null || namespace.isBlank()
            || cacheKey == null || cacheKey.isBlank()
            || value == null || value.isBlank()) {
            return;
        }
        String objectName = textCachePath(namespace, cacheKey);
        try {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType("text/plain; charset=utf-8")
                .build();
            storage.create(info, value.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Best-effort: a failed write just means the next identical subject
            // regenerates the value.
            log.warn("Text cache write failed for {}: {}", objectName, e.getMessage());
        }
    }

    private static String textCachePath(String namespace, String cacheKey) {
        return namespace + "/index/" + sha256Hex(cacheKey) + ".txt";
    }

    /**
     * Upload with a couple of quick retries. Generation runs on a detached
     * background thread, so a transient connection reset mid-upload ("Broken
     * pipe" / "Error writing request body to server") would otherwise discard an
     * image we already paid Gemini to produce. The durable fix for background-
     * thread starvation is running the service with CPU always allocated (backend
     * cloudbuild {@code --no-cpu-throttling}); this is cheap extra insurance.
     */
    private void createWithRetry(BlobInfo info, byte[] bytes) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= UPLOAD_ATTEMPTS; attempt++) {
            try {
                storage.create(info, bytes);
                return;
            } catch (RuntimeException e) {
                last = e;
                if (attempt < UPLOAD_ATTEMPTS) {
                    log.warn("Food image upload attempt {}/{} failed ({}); retrying",
                        attempt, UPLOAD_ATTEMPTS, e.getMessage());
                    sleepQuietly(200L * attempt);
                }
            }
        }
        throw last;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public Optional<Photo> read(String ref) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        String objectName = objectNameFromUrl(ref);
        if (objectName == null) {
            return Optional.empty();
        }
        try {
            Blob blob = storage.get(BlobId.of(bucket, objectName));
            if (blob == null || !blob.exists()) {
                return Optional.empty();
            }
            byte[] bytes = blob.getContent();
            if (bytes == null || bytes.length == 0) {
                return Optional.empty();
            }
            String mime = blob.getContentType() != null ? blob.getContentType() : "image/jpeg";
            return Optional.of(new Photo(bytes, mime));
        } catch (RuntimeException e) {
            log.warn("Failed to read reference photo {}: {}", ref, e.getMessage());
            return Optional.empty();
        }
    }

    /** Map a public URL for this bucket back to its object name, or null. */
    private String objectNameFromUrl(String url) {
        String prefix = "https://storage.googleapis.com/" + bucket + "/";
        if (!url.startsWith(prefix)) {
            return null;
        }
        String objectName = url.substring(prefix.length());
        int q = objectName.indexOf('?');
        if (q >= 0) {
            objectName = objectName.substring(0, q);
        }
        return objectName.isBlank() ? null : objectName;
    }

    private static String safeId(String foodId) {
        return (foodId == null || foodId.isBlank()) ? "unknown" : foodId;
    }

    private String publicUrl(String objectName) {
        return "https://storage.googleapis.com/" + bucket + "/" + objectName;
    }
}
