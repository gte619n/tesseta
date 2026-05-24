package com.gte619n.healthfitness.integrations.equipment;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Wraps GCS reads/writes for equipment images. Blobs live at
//   gs://{bucket}/equipment/{equipmentId}.webp
// The public URL is returned for direct access.
@Component
public class EquipmentImageStorage {

    private final Storage storage;
    private final String bucket;

    public EquipmentImageStorage(@Value("${app.equipment.bucket}") String bucket) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    public String upload(String equipmentId, byte[] imageBytes) {
        String objectName = objectName(equipmentId);
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType("image/webp")
            .build();
        storage.create(info, imageBytes);
        return publicUrl(objectName);
    }

    public void delete(String equipmentId) {
        storage.delete(BlobId.of(bucket, objectName(equipmentId)));
    }

    private static String objectName(String equipmentId) {
        return "equipment/" + equipmentId + ".webp";
    }

    private String publicUrl(String objectName) {
        return "https://storage.googleapis.com/" + bucket + "/" + objectName;
    }
}
