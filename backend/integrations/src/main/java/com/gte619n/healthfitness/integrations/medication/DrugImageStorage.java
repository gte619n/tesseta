package com.gte619n.healthfitness.integrations.medication;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stores generated medication images in Google Cloud Storage.
 * Images are stored at: gs://{bucket}/drugs/{drugId}/image.png
 * Public URL: https://storage.googleapis.com/{bucket}/drugs/{drugId}/image.png
 */
@Component
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugImageStorage {

    private final Storage storage;
    private final String bucket;

    public DrugImageStorage(
        @Value("${app.medications.bucket}") String bucket
    ) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    /**
     * Upload a drug image to GCS.
     *
     * @param drugId The drug ID (used as folder name)
     * @param imageBytes The image data (PNG format)
     * @return The public URL of the uploaded image
     */
    public String upload(String drugId, byte[] imageBytes) {
        String objectPath = "drugs/" + drugId + "/image.png";
        BlobId blobId = BlobId.of(bucket, objectPath);
        BlobInfo blobInfo = BlobInfo.newBuilder(blobId)
            .setContentType("image/png")
            .setCacheControl("public, max-age=31536000") // 1 year cache
            .build();

        storage.create(blobInfo, imageBytes);

        return getPublicUrl(objectPath);
    }

    /**
     * Delete a drug image from GCS.
     *
     * @param drugId The drug ID
     */
    public void delete(String drugId) {
        String objectPath = "drugs/" + drugId + "/image.png";
        BlobId blobId = BlobId.of(bucket, objectPath);
        storage.delete(blobId);
    }

    /**
     * Check if an image exists for a drug.
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
     * Get the public URL for a drug by ID.
     *
     * @param drugId The drug ID
     * @return The public URL
     */
    public String getUrl(String drugId) {
        return getPublicUrl("drugs/" + drugId + "/image.png");
    }
}
