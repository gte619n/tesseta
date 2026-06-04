package com.gte619n.healthfitness.integrations.bloodtest;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.bloodtest.enabled", havingValue = "true", matchIfMissing = true)
public class BloodTestPdfStorage {

    private final Storage storage;
    private final String bucket;

    public BloodTestPdfStorage(Storage storage, @Value("${app.bloodtest.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    public String upload(String userId, String reportId, byte[] pdfBytes) {
        String objectName = objectName(userId, reportId);
        BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName))
            .setContentType("application/pdf")
            .build();
        storage.create(info, pdfBytes);
        return "gs://" + bucket + "/" + objectName;
    }

    public byte[] download(String userId, String reportId) {
        return storage.readAllBytes(BlobId.of(bucket, objectName(userId, reportId)));
    }

    public void delete(String userId, String reportId) {
        storage.delete(BlobId.of(bucket, objectName(userId, reportId)));
    }

    private static String objectName(String userId, String reportId) {
        return "users/" + userId + "/bloodtests/" + reportId + ".pdf";
    }
}
