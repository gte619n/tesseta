package com.gte619n.healthfitness.integrations.medication;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stores generated medication images in Google Cloud Storage.
 *
 * Images are written to versioned paths so each regeneration produces a
 * NEW public URL:
 *   gs://{bucket}/drugs/{drugId}/{ts}.png
 *
 * Versioned URLs let us serve images with aggressive year-long immutable
 * caching — clients don't have to re-validate, since a regeneration
 * naturally changes the URL.
 */
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugImageStorage {

    private static final Logger log = LoggerFactory.getLogger(DrugImageStorage.class);

    private final Storage storage;
    private final String bucket;

    public DrugImageStorage(
        @Value("${app.medications.bucket}") String bucket
    ) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    /**
     * Upload a drug image to GCS at a fresh, versioned path. Assumes PNG —
     * used by the AI image generator, which always emits PNG.
     *
     * @param drugId The drug ID (used as folder name)
     * @param imageBytes The image data (PNG format)
     * @return The public URL of the uploaded image
     */
    public String upload(String drugId, byte[] imageBytes) {
        return upload(drugId, imageBytes, "image/png");
    }

    /**
     * Upload a drug image to GCS at a fresh, versioned path, choosing the
     * object extension from the supplied content type. Used by the admin
     * custom-upload path, where the file may be PNG, JPEG, WebP, or GIF.
     *
     * @param drugId The drug ID (used as folder name)
     * @param imageBytes The image data
     * @param contentType The MIME type of the image (e.g. {@code image/jpeg})
     * @return The public URL of the uploaded image
     */
    public String upload(String drugId, byte[] imageBytes, String contentType) {
        String ext = extensionFor(contentType);
        String objectPath = versionedObjectPath(drugId, ext);
        BlobId blobId = BlobId.of(bucket, objectPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType(contentType)
            .setCacheControl("public, max-age=31536000, immutable")
            .build();

        storage.create(blobInfo, imageBytes);

        return getPublicUrl(objectPath);
    }

    /**
     * Delete the legacy unversioned drug image (drugs/{drugId}/image.png).
     * Retained for callers that still operate on the original layout.
     *
     * @param drugId The drug ID
     */
    public void delete(String drugId) {
        String objectPath = "drugs/" + drugId + "/image.png";
        BlobId blobId = BlobId.of(bucket, objectPath);
        storage.delete(blobId);
    }

    /**
     * Best-effort delete of a previously uploaded drug image by its public
     * URL. Used after a successful regeneration to clean up the old blob
     * once the new (differently named) URL has been persisted.
     *
     * <p>Never throws — null/blank URLs, mismatched URL patterns, and
     * storage errors are logged at WARN and swallowed. Worst case we
     * leave a stale object in GCS, which is harmless.
     */
    public void deleteByUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String prefix = "https://storage.googleapis.com/" + bucket + "/";
        if (!url.startsWith(prefix)) {
            log.warn("Skipping drug image cleanup — URL does not match bucket prefix: {}", url);
            return;
        }
        String objectPath = url.substring(prefix.length());
        // Strip any legacy ?v= query suffix in case old records still carry one.
        int q = objectPath.indexOf('?');
        if (q >= 0) {
            objectPath = objectPath.substring(0, q);
        }
        if (objectPath.isBlank()) {
            return;
        }
        try {
            storage.delete(BlobId.of(bucket, objectPath));
        } catch (Exception e) {
            log.warn("Failed to delete stale drug image {}: {}", objectPath, e.getMessage());
        }
    }

    /**
     * Check if a legacy unversioned image exists for a drug.
     *
     * @param drugId The drug ID
     * @return true if an image exists
     */
    public boolean exists(String drugId) {
        String objectPath = "drugs/" + drugId + "/image.png";
        BlobId blobId = BlobId.of(bucket, objectPath);
        return storage.get(blobId) != null;
    }

    /**
     * Get the public URL for a drug image.
     *
     * @param objectPath The GCS object path
     * @return The public URL
     */
    private String getPublicUrl(String objectPath) {
        return "https://storage.googleapis.com/" + bucket + "/" + objectPath;
    }

    /**
     * Legacy unversioned URL accessor — returns the canonical path that
     * older records used. Kept for backward compatibility.
     *
     * @param drugId The drug ID
     * @return The public URL
     */
    public String getUrl(String drugId) {
        return getPublicUrl("drugs/" + drugId + "/image.png");
    }

    private static String versionedObjectPath(String drugId, String ext) {
        return "drugs/" + drugId + "/" + System.currentTimeMillis() + "." + ext;
    }

    /**
     * Map an image content type to the GCS object extension. Defaults to
     * {@code png} for null/unknown types.
     */
    private static String extensionFor(String contentType) {
        if (contentType == null) {
            return "png";
        }
        return switch (contentType) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "png";
        };
    }
}
