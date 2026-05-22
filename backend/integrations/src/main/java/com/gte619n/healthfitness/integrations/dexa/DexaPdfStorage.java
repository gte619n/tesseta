package com.gte619n.healthfitness.integrations.dexa;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

// Wraps GCS reads/writes for DEXA PDFs. Blobs live at
//   gs://{bucket}/users/{userId}/dexa/{scanId}.pdf
// We store the gs:// URI on the scan document so the API can return
// signed download URLs without re-deriving the path.
@Component
@ConditionalOnProperty(name = "app.dexa.enabled", havingValue = "true", matchIfMissing = true)
public class DexaPdfStorage {

    private final Storage storage;
    private final String bucket;

    public DexaPdfStorage(@Value("${app.dexa.bucket}") String bucket) {
        this.storage = StorageOptions.getDefaultInstance().getService();
        this.bucket = bucket;
    }

    public String upload(String userId, String scanId, byte[] pdfBytes) {
        String objectName = objectName(userId, scanId);
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType("application/pdf")
            .build();
        storage.create(info, pdfBytes);
        return "gs://" + bucket + "/" + objectName;
    }

    public byte[] download(String userId, String scanId) {
        return storage.readAllBytes(BlobId.of(bucket, objectName(userId, scanId)));
    }

    public void delete(String userId, String scanId) {
        storage.delete(BlobId.of(bucket, objectName(userId, scanId)));
    }

    private static String objectName(String userId, String scanId) {
        return "users/" + userId + "/dexa/" + scanId + ".pdf";
    }
}
