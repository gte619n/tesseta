package com.gte619n.healthfitness.api.location;

import com.gte619n.healthfitness.api.equipment.BulkImportConfirmRequest;
import com.gte619n.healthfitness.api.equipment.BulkImportConfirmResponse;
import com.gte619n.healthfitness.api.equipment.BulkImportPreviewRequest;
import com.gte619n.healthfitness.api.equipment.BulkImportPreviewResponse;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.equipment.BulkImportService;
import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bulk equipment import endpoints. The flow is two-step:
 *
 * <ol>
 *   <li>{@code POST /preview} — parse raw text + fuzzy match against the catalog.</li>
 *   <li>{@code POST /confirm} — create new submissions and add the resolved
 *       equipment IDs to the location.</li>
 * </ol>
 *
 * <p>Lives in the {@code app} module (alongside {@code LocationController})
 * because it needs {@link LocationService} to add equipment to a location;
 * {@code LocationService} is {@code app}-only by module layering.
 */
@RestController
@RequestMapping("/api/me/gyms/{locationId}/equipment/import")
public class BulkImportController {

    private static final Logger log = LoggerFactory.getLogger(BulkImportController.class);

    private final BulkImportService bulkImportService;
    private final LocationService locationService;
    private final CurrentUserProvider currentUser;

    public BulkImportController(
        BulkImportService bulkImportService,
        LocationService locationService,
        CurrentUserProvider currentUser
    ) {
        this.bulkImportService = bulkImportService;
        this.locationService = locationService;
        this.currentUser = currentUser;
    }

    @PostMapping("/preview")
    public BulkImportPreviewResponse preview(
        @PathVariable String locationId,
        @Valid @RequestBody BulkImportPreviewRequest request
    ) {
        String userId = currentUser.get().userId();
        log.info("Bulk import preview requested: locationId={}, rawTextLength={}",
            locationId, request.rawText() == null ? 0 : request.rawText().length());

        BulkImportService.PreviewResult result =
            bulkImportService.preview(userId, locationId, request.rawText());

        log.info("Bulk import preview completed: locationId={}, total={}, matched={}, "
                + "suggested={}, new={}",
            locationId,
            result.summary().total(),
            result.summary().matched(),
            result.summary().suggestedMatches(),
            result.summary().newSubmissions());

        return BulkImportPreviewResponse.from(result);
    }

    @PostMapping("/confirm")
    public BulkImportConfirmResponse confirm(
        @PathVariable String locationId,
        @Valid @RequestBody BulkImportConfirmRequest request
    ) {
        String userId = currentUser.get().userId();
        int itemCount = request.items() == null ? 0 : request.items().size();
        log.info("Bulk import confirm requested: locationId={}, itemCount={}",
            locationId, itemCount);

        // Translate API DTOs -> service DTOs
        List<BulkImportService.ConfirmItem> serviceItems = request.items().stream()
            .map(item -> new BulkImportService.ConfirmItem(
                item.index(),
                item.action(),
                item.matchedEquipmentId(),
                item.parsed(),
                item.overrides() == null
                    ? null
                    : new BulkImportService.NameOverride(item.overrides().name())
            ))
            .toList();

        BulkImportService.ConfirmResult result =
            bulkImportService.confirm(userId, locationId, serviceItems);

        // Add each equipmentId to the location. Continue on individual failures
        // so a single bad ID doesn't lose the rest of the import. Equipment
        // with status=PENDING_REVIEW is fine here — LocationService.update /
        // addEquipmentToLocation does not validate against the catalog, it
        // just stores the ID list.
        int added = 0;
        for (String equipmentId : result.equipmentIdsToAdd()) {
            try {
                locationService.addEquipmentToLocation(userId, locationId, equipmentId);
                added++;
            } catch (Exception e) {
                log.warn("Failed to add equipment {} to location {}: {}",
                    equipmentId, locationId, e.getMessage());
                // Continue with the rest; don't fail the whole import
            }
        }

        log.info("Bulk import confirm completed: locationId={}, created={}, matched={}, "
                + "addedToLocation={}, skipped={}, failed={}",
            locationId,
            result.created().size(),
            result.matched().size(),
            added,
            result.skipped(),
            result.failed().size());

        return BulkImportConfirmResponse.from(result, added);
    }
}
