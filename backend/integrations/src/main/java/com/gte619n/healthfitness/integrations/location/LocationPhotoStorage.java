package com.gte619n.healthfitness.integrations.location;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Wraps GCS reads/writes for location cover photos. Blobs live at
//   gs://{bucket}/covers/{locationId}.webp
// The public URL is returned for direct access.
@Component
public class LocationPhotoStorage {

    private final Storage storage;
    private final String bucket;

    public LocationPhotoStorage(@Value("${app.gym.bucket}") String bucket) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    public String upload(String locationId, byte[] imageBytes) {
        String objectName = objectName(locationId);
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType("image/webp")
            .build();
        storage.create(info, imageBytes);
        return publicUrl(objectName);
    }

    public void delete(String locationId) {
        storage.delete(BlobId.of(bucket, objectName(locationId)));
    }

    private static String objectName(String locationId) {
        return "covers/" + locationId + ".webp";
    }

    private String publicUrl(String objectName) {
        return "https://storage.googleapis.com/" + bucket + "/" + objectName;
    }
}
