package com.gte619n.healthfitness.integrations.nutrition;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * SEC-012 (Phase 1+2) — mints short-lived V4 read-signed URLs for private meal
 * photos so the object can be served without the bucket being world-readable.
 *
 * <p>The nutrition entry stores {@code photoRef} as the object's public GCS URL
 * ({@code https://storage.googleapis.com/{bucket}/{object}}). This service maps
 * that back to the object name and signs a time-boxed read URL for it, which
 * {@code GET /api/me/nutrition/photo/{entryId}} 302-redirects to. The bucket IAM
 * flip to private is a deferred Phase-3 follow-up (DEC-W2-1); until then both the
 * public URL and the signed URL resolve, so this is safe to ship ahead of it.
 *
 * <p>Gated by {@code app.nutrition.capture.enabled} like the other GCS beans so
 * unit-test contexts skip the client that would authenticate at construction.
 */
@Service
@ConditionalOnProperty(name = "app.nutrition.capture.enabled", havingValue = "true", matchIfMissing = true)
public class SignedUrlService {

    private static final Logger log = LoggerFactory.getLogger(SignedUrlService.class);

    /** Read-URL lifetime (contract): 15 minutes — long enough to render, short
     * enough that a leaked URL expires quickly. */
    static final long TTL_MINUTES = 15;

    private final Storage storage;
    private final String bucket;

    public SignedUrlService(Storage storage, @Value("${app.nutrition.bucket}") String bucket) {
        this.storage = storage;
        this.bucket = bucket;
    }

    /**
     * A V4 read-signed URL for the object behind {@code photoRef}, or null when the
     * ref is blank / not in this bucket / signing fails. Never throws — a missing
     * signed URL is handled by the caller as "no photo".
     */
    public String signedReadUrl(String photoRef) {
        String objectName = objectNameFromRef(photoRef);
        if (objectName == null) {
            return null;
        }
        try {
            BlobInfo info = BlobInfo.newBuilder(BlobId.of(bucket, objectName)).build();
            URL url = storage.signUrl(
                info, TTL_MINUTES, TimeUnit.MINUTES,
                Storage.SignUrlOption.httpMethod(HttpMethod.GET),
                Storage.SignUrlOption.withV4Signature());
            return url != null ? url.toString() : null;
        } catch (RuntimeException e) {
            log.warn("Failed to sign meal-photo URL for object {}: {}", objectName, e.toString());
            return null;
        }
    }

    /** Map a stored public URL (or a bare object name) back to its object name. */
    String objectNameFromRef(String ref) {
        if (ref == null || ref.isBlank()) {
            return null;
        }
        String prefix = "https://storage.googleapis.com/" + bucket + "/";
        String objectName;
        if (ref.startsWith(prefix)) {
            objectName = ref.substring(prefix.length());
        } else if (!ref.startsWith("http")) {
            // Already a bare object name.
            objectName = ref;
        } else {
            // A URL for some other bucket/host — not ours.
            return null;
        }
        int q = objectName.indexOf('?');
        if (q >= 0) {
            objectName = objectName.substring(0, q);
        }
        return objectName.isBlank() ? null : objectName;
    }
}
