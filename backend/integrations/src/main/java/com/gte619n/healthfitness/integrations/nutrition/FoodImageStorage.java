package com.gte619n.healthfitness.integrations.nutrition;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.gte619n.healthfitness.core.nutrition.FoodImageStore;
import com.gte619n.healthfitness.core.nutrition.MealPhotoReader;
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

    private final Storage storage;
    private final String bucket;

    public FoodImageStorage(@Value("${app.nutrition.bucket}") String bucket) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    @Override
    public String upload(String foodId, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new FoodImageStorageException("image bytes are empty");
        }
        String objectName = PREFIX + safeId(foodId) + ".png";
        try {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType("image/png")
                .setCacheControl("public, max-age=31536000, immutable")
                .build();
            storage.create(info, imageBytes);
            return publicUrl(objectName);
        } catch (RuntimeException e) {
            log.warn("Failed to store food image at {}: {}", objectName, e.getMessage());
            throw new FoodImageStorageException("failed to store food image", e);
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
