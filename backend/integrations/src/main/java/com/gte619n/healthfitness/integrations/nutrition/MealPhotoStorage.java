package com.gte619n.healthfitness.integrations.nutrition;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.gte619n.healthfitness.core.nutrition.MealPhotoStore;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stores raw user-captured meal/label photos in Google Cloud Storage, mirroring
 * {@code LocationPhotoStorage} / {@code DrugImageStorage}. Objects live at
 * <pre>gs://{bucket}/nutrition/{userId}/{uuid}.jpg</pre>
 * and the public URL is returned as the photo reference.
 *
 * <p>Implements the {@code core} {@link MealPhotoStore} port so
 * {@code NutritionCaptureService} can store photos through the abstraction
 * without {@code core} depending on GCS. Gated by
 * {@code app.nutrition.capture.enabled} (default true) so unit-test contexts can
 * skip the GCS bean that would otherwise authenticate at construction time —
 * the same pattern DEXA/blood-test/medication storages use.
 *
 * <p>Never leaks raw GCS errors: a failed upload is logged and re-thrown wrapped
 * in {@link MealPhotoStorageException}, which the capture service/controller can
 * handle uniformly.
 */
@Component
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class MealPhotoStorage implements MealPhotoStore {

    private static final Logger log = LoggerFactory.getLogger(MealPhotoStorage.class);

    private final Storage storage;
    private final String bucket;

    public MealPhotoStorage(@Value("${app.nutrition.bucket}") String bucket) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    @Override
    public String store(String userId, byte[] imageBytes, String mimeType) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new MealPhotoStorageException("photo bytes are empty");
        }
        String contentType = (mimeType == null || mimeType.isBlank()) ? "image/jpeg" : mimeType;
        String objectName = objectName(userId, extensionFor(contentType));
        try {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
                .setContentType(contentType)
                .build();
            storage.create(info, imageBytes);
            return publicUrl(objectName);
        } catch (RuntimeException e) {
            log.warn("Failed to store meal photo at {}: {}", objectName, e.getMessage());
            throw new MealPhotoStorageException("failed to store meal photo", e);
        }
    }

    private static String objectName(String userId, String extension) {
        String safeUser = (userId == null || userId.isBlank()) ? "unknown" : userId;
        return "nutrition/" + safeUser + "/" + UUID.randomUUID() + "." + extension;
    }

    private static String extensionFor(String contentType) {
        return switch (contentType.toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            default -> "jpg";
        };
    }

    private String publicUrl(String objectName) {
        return "https://storage.googleapis.com/" + bucket + "/" + objectName;
    }
}
