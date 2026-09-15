package com.gte619n.healthfitness.core.gym;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.equipment.BulkImportService;
import com.gte619n.healthfitness.core.equipment.BulkImportService.Action;
import com.gte619n.healthfitness.core.equipment.BulkImportService.PreviewItem;
import com.gte619n.healthfitness.core.equipment.BulkImportService.PreviewResult;
import com.gte619n.healthfitness.core.equipment.BulkImportService.PreviewSummary;
import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import com.gte619n.healthfitness.core.gym.jobs.GymScanJob;
import com.gte619n.healthfitness.core.gym.jobs.GymScanJobQueue;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * IMPL-GYM-003 — orchestration of a gym-video scan: register hands out an upload
 * URL, start enqueues, the job detects + matches + persists a READY preview and
 * deletes the raw video, and redelivery is a no-op. Catalog matching itself is
 * mocked here (covered by BulkImportService's own tests).
 */
class GymVideoScanServiceTest {

    private static final String USER = "u1";
    private static final String LOC = "loc1";

    /** In-memory scan repo. */
    static class FakeScanRepo implements EquipmentScanRepository {
        final Map<String, EquipmentScan> byId = new HashMap<>();
        @Override public void save(EquipmentScan s) { byId.put(s.scanId(), s); }
        @Override public Optional<EquipmentScan> findById(String u, String l, String id) {
            EquipmentScan s = byId.get(id);
            return (s != null && s.userId().equals(u) && s.locationId().equals(l))
                ? Optional.of(s) : Optional.empty();
        }
    }

    /** Fake GCS: hands out a target, serves bytes, records deletions. */
    static class FakeVideoStore implements VideoStore {
        boolean exists = true;
        final List<String> deleted = new ArrayList<>();
        @Override public UploadTarget createUploadTarget(String u, String l, String id, String mime) {
            return new UploadTarget("gym-scans/" + u + "/" + id + ".mp4",
                "https://signed.example/put", "PUT", Map.of("Content-Type", mime));
        }
        @Override public boolean exists(String ref) { return exists; }
        @Override public long size(String ref) { return 3; }
        @Override public InputStream openStream(String ref) { return new ByteArrayInputStream(new byte[]{1, 2, 3}); }
        @Override public void delete(String ref) { deleted.add(ref); }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        when(p.getObject()).thenReturn(value);
        return p;
    }

    private static PreviewResult onePreview() {
        ParsedEquipment p = new ParsedEquipment(
            "Treadmill", null, "Machines - Cardio", "Treadmill", null, Map.of(), "CERTAIN", "seen");
        return new PreviewResult(
            List.of(new PreviewItem(0, p, null, Action.CREATE_NEW)),
            new PreviewSummary(1, 0, 0, 1));
    }

    @Test
    void happyPath_registerStartAnalyzeReadyAndDeletesVideo() {
        FakeScanRepo repo = new FakeScanRepo();
        FakeVideoStore store = new FakeVideoStore();
        VideoEquipmentDetector detector = (in, len, mime) -> List.of(
            new ParsedEquipment("Treadmill", null, "Machines - Cardio", "Treadmill",
                null, Map.of(), "CERTAIN", "seen"),
            // duplicate model — must be de-duped to one before matching
            new ParsedEquipment("treadmill", null, "Machines - Cardio", "Treadmill",
                null, Map.of(), "LIKELY", "seen again"));
        BulkImportService bulk = mock(BulkImportService.class);
        when(bulk.preview(eq(USER), eq(LOC), anyList())).thenReturn(onePreview());

        // Sync queue: run the job inline so the test is deterministic.
        GymVideoScanService[] holder = new GymVideoScanService[1];
        GymScanJobQueue queue = job -> holder[0].runScan(job);

        GymVideoScanService service = new GymVideoScanService(
            repo, provider(store), provider(detector), provider(queue), bulk, 209715200L);
        holder[0] = service;

        var reg = service.register(USER, LOC, "video/mp4", 1000);
        assertNotNull(reg.scanId());
        assertEquals("PUT", reg.target().method());
        assertEquals(ScanStatus.REGISTERED, repo.byId.get(reg.scanId()).status());

        service.start(USER, LOC, reg.scanId());

        EquipmentScan done = service.status(USER, LOC, reg.scanId()).orElseThrow();
        assertEquals(ScanStatus.READY, done.status());
        assertEquals(1, done.detectedCount(), "the two treadmill detections de-dupe to one");
        assertNotNull(done.preview());
        assertNull(done.videoRef(), "raw video ref cleared on terminal state");
        assertTrue(store.deleted.contains("gym-scans/" + USER + "/" + reg.scanId() + ".mp4"),
            "raw video deleted after analysis");
    }

    @Test
    void register_rejectsBadTypeAndOversize() {
        GymVideoScanService service = new GymVideoScanService(
            new FakeScanRepo(), provider(new FakeVideoStore()),
            provider((VideoEquipmentDetector) (in, l, m) -> List.of()),
            provider(job -> { }), mock(BulkImportService.class), 100L);
        assertThrows(IllegalArgumentException.class,
            () -> service.register(USER, LOC, "image/png", 10));
        assertThrows(IllegalArgumentException.class,
            () -> service.register(USER, LOC, "video/mp4", 999999));
    }

    @Test
    void runScan_isNoOpForNonAnalyzingScan() {
        FakeScanRepo repo = new FakeScanRepo();
        FakeVideoStore store = new FakeVideoStore();
        BulkImportService bulk = mock(BulkImportService.class);
        GymVideoScanService service = new GymVideoScanService(
            repo, provider(store), provider((VideoEquipmentDetector) (in, l, m) -> List.of()),
            provider(job -> { }), bulk, 209715200L);
        // No scan persisted → redelivered job must be a silent no-op.
        service.runScan(new GymScanJob(USER, LOC, "missing", "ref", "video/mp4"));
        assertTrue(store.deleted.isEmpty());
    }
}
