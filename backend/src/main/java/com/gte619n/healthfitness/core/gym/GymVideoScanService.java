package com.gte619n.healthfitness.core.gym;

import com.gte619n.healthfitness.core.equipment.BulkImportService;
import com.gte619n.healthfitness.core.equipment.BulkImportService.ConfirmItem;
import com.gte619n.healthfitness.core.equipment.BulkImportService.ConfirmResult;
import com.gte619n.healthfitness.core.equipment.BulkImportService.PreviewResult;
import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import com.gte619n.healthfitness.core.gym.jobs.GymScanJob;
import com.gte619n.healthfitness.core.gym.jobs.GymScanJobQueue;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * IMPL-GYM-003: orchestrates gym-video equipment detection end to end, feeding the
 * IMPL-GYM-002 review/confirm pipeline.
 *
 * <ol>
 *   <li>{@link #register} — validate limits, mint a direct-to-GCS upload URL, and
 *       persist a {@link ScanStatus#REGISTERED} row.</li>
 *   <li>{@link #start} — verify the upload landed, flip to {@link ScanStatus#ANALYZING},
 *       and durably enqueue the analysis job.</li>
 *   <li>{@link #runScan} — (job) detect equipment from the video, match it against
 *       the catalog via {@link BulkImportService}, persist a {@link ScanStatus#READY}
 *       preview, and delete the raw video.</li>
 *   <li>{@link #confirm} — apply the user's per-item decisions (reused verbatim).</li>
 * </ol>
 *
 * <p>The GCS/Gemini adapters are gated by {@code app.gym.video-scan.enabled}; when
 * absent the feature reports unavailable rather than failing obscurely.
 */
@Service
public class GymVideoScanService {

    private static final Logger log = LoggerFactory.getLogger(GymVideoScanService.class);

    private static final Set<String> ALLOWED_MIME = Set.of("video/mp4", "video/quicktime");

    private final EquipmentScanRepository scans;
    private final ObjectProvider<VideoStore> videoStore;
    private final ObjectProvider<VideoEquipmentDetector> detector;
    private final ObjectProvider<GymScanJobQueue> jobQueue;
    private final BulkImportService bulkImport;
    private final long maxBytes;

    public GymVideoScanService(
        EquipmentScanRepository scans,
        ObjectProvider<VideoStore> videoStore,
        ObjectProvider<VideoEquipmentDetector> detector,
        ObjectProvider<GymScanJobQueue> jobQueue,
        BulkImportService bulkImport,
        @Value("${app.gym.video-scan.max-bytes:209715200}") long maxBytes
    ) {
        this.scans = scans;
        this.videoStore = videoStore;
        this.detector = detector;
        this.jobQueue = jobQueue;
        this.bulkImport = bulkImport;
        this.maxBytes = maxBytes;
    }

    public record RegisterResult(String scanId, VideoStore.UploadTarget target) {}

    /** Step 1 — validate + hand out a direct-to-GCS upload URL. */
    public RegisterResult register(String userId, String locationId, String mimeType, long sizeBytes) {
        String mime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MIME.contains(mime)) {
            throw new IllegalArgumentException("unsupported video type: " + mimeType);
        }
        if (sizeBytes <= 0 || sizeBytes > maxBytes) {
            throw new IllegalArgumentException(
                "video size must be 1.." + maxBytes + " bytes (got " + sizeBytes + ")");
        }
        VideoStore store = require(videoStore);
        String scanId = UUID.randomUUID().toString();
        VideoStore.UploadTarget target = store.createUploadTarget(userId, locationId, scanId, mime);
        Instant now = Instant.now();
        scans.save(new EquipmentScan(
            userId, locationId, scanId, ScanStatus.REGISTERED, target.objectRef(), mime,
            sizeBytes, null, null, null, now, now));
        return new RegisterResult(scanId, target);
    }

    /** Step 2 — the client says the upload finished; enqueue analysis. */
    public EquipmentScan start(String userId, String locationId, String scanId) {
        EquipmentScan scan = load(userId, locationId, scanId);
        if (scan.status() == ScanStatus.ANALYZING || scan.status() == ScanStatus.READY) {
            return scan; // idempotent: a re-tap while in flight / done is a no-op
        }
        VideoStore store = require(videoStore);
        if (scan.videoRef() == null || !store.exists(scan.videoRef())) {
            throw new IllegalStateException("video not uploaded");
        }
        EquipmentScan analyzing = scan.withStatus(ScanStatus.ANALYZING);
        scans.save(analyzing);
        require(jobQueue).enqueue(new GymScanJob(
            userId, locationId, scanId, scan.videoRef(), scan.mimeType()));
        return analyzing;
    }

    /**
     * Step 3 — the durable job body. Idempotent: a redelivery for a scan that is no
     * longer ANALYZING is a no-op. Always deletes the video on a terminal outcome.
     */
    public void runScan(GymScanJob job) {
        Optional<EquipmentScan> current = scans.findById(job.userId(), job.locationId(), job.scanId());
        if (current.isEmpty() || current.get().status() != ScanStatus.ANALYZING) {
            return;
        }
        EquipmentScan scan = current.get();
        VideoStore store = require(videoStore);
        VideoEquipmentDetector det = require(detector);
        try {
            List<ParsedEquipment> detected;
            try (InputStream in = store.openStream(scan.videoRef())) {
                detected = det.detect(in, store.size(scan.videoRef()), scan.mimeType());
            }
            List<ParsedEquipment> deduped = dedupeByName(detected);
            PreviewResult preview = bulkImport.preview(job.userId(), job.locationId(), deduped);
            scans.save(scan.ready(preview, deduped.size()));
            log.info("Gym scan {} READY: {} distinct equipment detected", job.scanId(), deduped.size());
        } catch (RuntimeException | java.io.IOException e) {
            log.warn("Gym scan {} failed: {}", job.scanId(), e.toString());
            scans.save(scan.failed("We couldn't read the equipment from that video. "
                + "Try a slower walkthrough with good lighting."));
        } finally {
            safeDelete(store, scan.videoRef());
        }
    }

    public Optional<EquipmentScan> status(String userId, String locationId, String scanId) {
        return scans.findById(userId, locationId, scanId);
    }

    /**
     * Step 4 — apply the reviewed decisions. Verifies the scan is the caller's and
     * READY, then reuses {@link BulkImportService#confirm} verbatim; the controller
     * adds the resolved IDs to the location (module layering).
     */
    public ConfirmResult confirm(String userId, String locationId, String scanId, List<ConfirmItem> items) {
        EquipmentScan scan = load(userId, locationId, scanId);
        if (scan.status() != ScanStatus.READY) {
            throw new IllegalStateException("scan is not ready for confirmation");
        }
        return bulkImport.confirm(userId, locationId, items);
    }

    // -- helpers ---------------------------------------------------------------

    private EquipmentScan load(String userId, String locationId, String scanId) {
        return scans.findById(userId, locationId, scanId)
            .orElseThrow(() -> new NoSuchElementException("scan not found"));
    }

    private static List<ParsedEquipment> dedupeByName(List<ParsedEquipment> items) {
        Map<String, ParsedEquipment> byName = new LinkedHashMap<>();
        for (ParsedEquipment p : items) {
            String key = p.name() == null ? "" : p.name().trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) {
                byName.putIfAbsent(key, p);
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static <T> T require(ObjectProvider<T> provider) {
        T bean = provider.getIfAvailable();
        if (bean == null) {
            throw new IllegalStateException("gym video scan is not enabled on this server");
        }
        return bean;
    }

    private static void safeDelete(VideoStore store, String ref) {
        if (ref == null) {
            return;
        }
        try {
            store.delete(ref);
        } catch (RuntimeException e) {
            log.warn("Failed to delete scan video {}: {}", ref, e.toString());
        }
    }
}
