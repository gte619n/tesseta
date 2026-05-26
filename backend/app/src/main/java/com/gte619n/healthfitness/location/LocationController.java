package com.gte619n.healthfitness.location;

import com.gte619n.healthfitness.api.location.CreateLocationRequest;
import com.gte619n.healthfitness.api.location.LocationResponse;
import com.gte619n.healthfitness.api.location.UpdateEquipmentSpecsRequest;
import com.gte619n.healthfitness.api.location.UpdateLocationRequest;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/me/gyms")
public class LocationController {

    private static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024; // 10 MB

    private final CurrentUserProvider currentUser;
    private final LocationService service;
    private final LocationRepository repository;

    public LocationController(
        CurrentUserProvider currentUser,
        LocationService service,
        LocationRepository repository
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.repository = repository;
    }

    @GetMapping
    public List<LocationResponse> list(
        @RequestParam(value = "include", required = false) String include
    ) {
        String userId = currentUser.get().userId();
        boolean includeInactive = "inactive".equals(include);
        return repository.findByUser(userId, includeInactive).stream()
            .map(LocationResponse::from)
            .toList();
    }

    @GetMapping("/{locationId}")
    public LocationResponse get(@PathVariable String locationId) {
        String userId = currentUser.get().userId();
        Location location = repository.findById(userId, locationId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return LocationResponse.from(location);
    }

    @PostMapping
    public ResponseEntity<LocationResponse> create(@RequestBody CreateLocationRequest body) {
        String userId = currentUser.get().userId();
        Location location = service.create(
            userId,
            body.name(),
            body.address(),
            body.is24Hours(),
            body.hours(),
            body.amenities(),
            body.equipmentIds()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(LocationResponse.from(location));
    }

    @PatchMapping("/{locationId}")
    public LocationResponse update(
        @PathVariable String locationId,
        @RequestBody UpdateLocationRequest body
    ) {
        String userId = currentUser.get().userId();
        // Verify location exists and belongs to user
        if (repository.findById(userId, locationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Location updated = service.update(
            userId,
            locationId,
            body.name(),
            body.address(),
            body.is24Hours(),
            body.hours(),
            body.amenities(),
            body.equipmentIds()
        );
        return LocationResponse.from(updated);
    }

    @DeleteMapping("/{locationId}")
    public ResponseEntity<Void> delete(@PathVariable String locationId) {
        String userId = currentUser.get().userId();
        // Verify location exists and belongs to user
        if (repository.findById(userId, locationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.softDelete(userId, locationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{locationId}/default")
    public ResponseEntity<Void> setDefault(@PathVariable String locationId) {
        String userId = currentUser.get().userId();
        // Verify location exists and belongs to user
        if (repository.findById(userId, locationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.setDefault(userId, locationId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/{locationId}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LocationResponse uploadPhoto(
        @PathVariable String locationId,
        @RequestParam("file") MultipartFile file
    ) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "Photo exceeds 10 MB limit");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Expected image file");
        }

        String userId = currentUser.get().userId();
        // Verify location exists and belongs to user
        if (repository.findById(userId, locationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Could not read uploaded file", e);
        }

        // Service handles GCS upload and location update
        Location updated = service.setCoverPhoto(userId, locationId, bytes);
        return LocationResponse.from(updated);
    }

    @PatchMapping("/{locationId}/equipment/{equipmentId}")
    public LocationResponse updateEquipmentSpecs(
        @PathVariable String locationId,
        @PathVariable String equipmentId,
        @RequestBody UpdateEquipmentSpecsRequest body
    ) {
        String userId = currentUser.get().userId();
        if (repository.findById(userId, locationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        Location updated = service.updateEquipmentSpecs(userId, locationId, equipmentId, body.specs());
        return LocationResponse.from(updated);
    }

    @DeleteMapping("/{locationId}/photo")
    public LocationResponse deletePhoto(@PathVariable String locationId) {
        String userId = currentUser.get().userId();
        // Verify location exists and belongs to user
        if (repository.findById(userId, locationId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }

        // Service handles GCS deletion and location update
        Location updated = service.removeCoverPhoto(userId, locationId);
        return LocationResponse.from(updated);
    }
}
