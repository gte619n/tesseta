package com.gte619n.healthfitness.api.location;

import com.gte619n.healthfitness.api.equipment.BulkImportConfirmRequest;
import com.gte619n.healthfitness.api.equipment.BulkImportConfirmResponse;
import com.gte619n.healthfitness.api.gym.ScanRegisterRequest;
import com.gte619n.healthfitness.api.gym.ScanRegisterResponse;
import com.gte619n.healthfitness.api.gym.ScanStatusResponse;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.equipment.BulkImportService;
import com.gte619n.healthfitness.core.gym.EquipmentScan;
import com.gte619n.healthfitness.core.gym.GymVideoScanService;
import com.gte619n.healthfitness.core.gym.GymVideoScanService.RegisterResult;
import jakarta.validation.Valid;
import java.util.List;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * IMPL-GYM-003: gym-video equipment scan endpoints. Four steps:
 *
 * <ol>
 *   <li>{@code POST /scan} — register + get a direct-to-GCS upload URL.</li>
 *   <li>{@code POST /scan/{scanId}/start} — the upload landed; begin analysis.</li>
 *   <li>{@code GET  /scan/{scanId}} — poll status; carries the review preview at READY.</li>
 *   <li>{@code POST /scan/{scanId}/confirm} — apply reviewed items (reuses bulk import).</li>
 * </ol>
 *
 * <p>Lives in the {@code app} module alongside {@link BulkImportController} because
 * confirm needs {@link LocationService} to attach equipment to the location.
 */
@RestController
@RequestMapping("/api/me/gyms/{locationId}/equipment/scan")
public class GymVideoScanController {

    private static final Logger log = LoggerFactory.getLogger(GymVideoScanController.class);

    private final GymVideoScanService scans;
    private final BulkImportService bulkImportService;
    private final LocationService locationService;
    private final CurrentUserProvider currentUser;

    public GymVideoScanController(
        GymVideoScanService scans,
        BulkImportService bulkImportService,
        LocationService locationService,
        CurrentUserProvider currentUser
    ) {
        this.scans = scans;
        this.bulkImportService = bulkImportService;
        this.locationService = locationService;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<ScanRegisterResponse> register(
        @PathVariable String locationId,
        @Valid @RequestBody ScanRegisterRequest request
    ) {
        String userId = currentUser.get().userId();
        try {
            RegisterResult result = scans.register(
                userId, locationId, request.mimeType(), request.sizeBytes());
            return ResponseEntity.status(HttpStatus.CREATED).body(ScanRegisterResponse.from(result));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
        }
    }

    @PostMapping("/{scanId}/start")
    public ResponseEntity<ScanStatusResponse> start(
        @PathVariable String locationId,
        @PathVariable String scanId
    ) {
        String userId = currentUser.get().userId();
        try {
            EquipmentScan scan = scans.start(userId, locationId, scanId);
            log.info("Gym scan started: locationId={}, scanId={}", locationId, scanId);
            return ResponseEntity.accepted().body(ScanStatusResponse.from(scan));
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scan not found");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @GetMapping("/{scanId}")
    public ScanStatusResponse status(
        @PathVariable String locationId,
        @PathVariable String scanId
    ) {
        String userId = currentUser.get().userId();
        return scans.status(userId, locationId, scanId)
            .map(ScanStatusResponse::from)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "scan not found"));
    }

    @PostMapping("/{scanId}/confirm")
    public BulkImportConfirmResponse confirm(
        @PathVariable String locationId,
        @PathVariable String scanId,
        @Valid @RequestBody BulkImportConfirmRequest request
    ) {
        String userId = currentUser.get().userId();
        List<BulkImportService.ConfirmItem> serviceItems = request.items().stream()
            .map(item -> new BulkImportService.ConfirmItem(
                item.index(),
                item.action(),
                item.matchedEquipmentId(),
                item.parsed(),
                item.overrides() == null ? null
                    : new BulkImportService.NameOverride(item.overrides().name())))
            .toList();

        BulkImportService.ConfirmResult result;
        try {
            result = scans.confirm(userId, locationId, scanId, serviceItems);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "scan not found");
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }

        // Same as bulk import: add each resolved equipment ID to the location,
        // continuing past individual failures so one bad ID doesn't lose the rest.
        int added = 0;
        for (String equipmentId : result.equipmentIdsToAdd()) {
            try {
                locationService.addEquipmentToLocation(userId, locationId, equipmentId);
                added++;
            } catch (Exception e) {
                log.warn("Failed to add equipment {} to location {}: {}",
                    equipmentId, locationId, e.getMessage());
            }
        }
        log.info("Gym scan confirm: locationId={}, scanId={}, created={}, matched={}, added={}",
            locationId, scanId, result.created().size(), result.matched().size(), added);
        return BulkImportConfirmResponse.from(result, added);
    }
}
