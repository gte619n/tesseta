package com.gte619n.healthfitness.location;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.location.CreateLocationRequest;
import com.gte619n.healthfitness.api.location.UpdateLocationRequest;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.testsupport.InMemoryLocationRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class LocationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired LocationRepository locationRepository;
    @Autowired ObjectMapper objectMapper;

    private static final String TEST_USER = "user-123";

    @BeforeEach
    void setUp() {
        // Spring caches the @SpringBootTest context across the run, so the
        // in-memory store survives between tests; wipe it before each one to
        // keep tests that assert exact list sizes isolated.
        ((InMemoryLocationRepository) locationRepository).clear();
    }

    @Test
    void createLocationWithMinimalFields() throws Exception {
        CreateLocationRequest request = new CreateLocationRequest(
            "Gold's Gym",
            null, // address
            false, // is24Hours
            null, // hours
            null, // amenities
            null  // equipmentIds
        );

        mvc.perform(post("/api/me/gyms")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.locationId").exists())
            .andExpect(jsonPath("$.name").value("Gold's Gym"))
            .andExpect(jsonPath("$.is24Hours").value(false))
            .andExpect(jsonPath("$.isDefault").value(false))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.createdAt").exists())
            .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void createLocationWithAllFields() throws Exception {
        Map<DayOfWeek, HoursSlot> hours = Map.of(
            DayOfWeek.MON, new HoursSlot("06:00", "22:00"),
            DayOfWeek.TUE, new HoursSlot("06:00", "22:00"),
            DayOfWeek.WED, new HoursSlot("06:00", "22:00")
        );

        CreateLocationRequest request = new CreateLocationRequest(
            "24 Hour Fitness",
            "123 Main St, San Francisco, CA",
            true,
            hours,
            List.of("Sauna", "Pool", "Lockers"),
            List.of("eq_001", "eq_002")
        );

        mvc.perform(post("/api/me/gyms")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.locationId").exists())
            .andExpect(jsonPath("$.name").value("24 Hour Fitness"))
            .andExpect(jsonPath("$.address").value("123 Main St, San Francisco, CA"))
            .andExpect(jsonPath("$.is24Hours").value(true))
            .andExpect(jsonPath("$.hours.mon.open").value("06:00"))
            .andExpect(jsonPath("$.hours.mon.close").value("22:00"))
            .andExpect(jsonPath("$.amenities").isArray())
            .andExpect(jsonPath("$.amenities.length()").value(3))
            .andExpect(jsonPath("$.equipmentIds").isArray())
            .andExpect(jsonPath("$.equipmentIds.length()").value(2));
    }

    @Test
    void createLocationWithoutNameFails() throws Exception {
        CreateLocationRequest request = new CreateLocationRequest(
            "", // empty name
            "123 Main St",
            false,
            null,
            null,
            null
        );

        mvc.perform(post("/api/me/gyms")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listLocationsActiveOnly() throws Exception {
        // Create active location
        Location active = createLocation("loc_001", "Active Gym", true);
        locationRepository.save(active);

        // Create inactive location
        Location inactive = createLocation("loc_002", "Inactive Gym", false);
        locationRepository.save(inactive);

        mvc.perform(get("/api/me/gyms")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].name").value("Active Gym"));
    }

    @Test
    void listLocationsIncludeInactive() throws Exception {
        // Create active location
        Location active = createLocation("loc_001", "Active Gym", true);
        locationRepository.save(active);

        // Create inactive location
        Location inactive = createLocation("loc_002", "Inactive Gym", false);
        locationRepository.save(inactive);

        mvc.perform(get("/api/me/gyms")
                .header("X-Dev-User", TEST_USER)
                .param("include", "inactive"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void getSingleLocation() throws Exception {
        Location location = createLocation("loc_001", "Test Gym", true);
        locationRepository.save(location);

        mvc.perform(get("/api/me/gyms/loc_001")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.locationId").value("loc_001"))
            .andExpect(jsonPath("$.name").value("Test Gym"))
            .andExpect(jsonPath("$.isActive").value(true));
    }

    @Test
    void getSingleLocationNotFound() throws Exception {
        mvc.perform(get("/api/me/gyms/nonexistent")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateLocationFields() throws Exception {
        Location location = createLocation("loc_001", "Old Name", true);
        locationRepository.save(location);

        UpdateLocationRequest update = new UpdateLocationRequest(
            "New Name",
            "New Address",
            true,
            null,
            List.of("Pool"),
            null
        );

        mvc.perform(patch("/api/me/gyms/loc_001")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("New Name"))
            .andExpect(jsonPath("$.address").value("New Address"))
            .andExpect(jsonPath("$.is24Hours").value(true))
            .andExpect(jsonPath("$.amenities.length()").value(1));
    }

    @Test
    void updateLocationPartialFields() throws Exception {
        Location location = createLocation("loc_001", "Test Gym", true);
        locationRepository.save(location);

        UpdateLocationRequest update = new UpdateLocationRequest(
            null,
            "New Address Only",
            null,
            null,
            null,
            null
        );

        mvc.perform(patch("/api/me/gyms/loc_001")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Test Gym")) // unchanged
            .andExpect(jsonPath("$.address").value("New Address Only"));
    }

    @Test
    void softDeleteLocation() throws Exception {
        Location location = createLocation("loc_001", "Test Gym", true);
        locationRepository.save(location);

        mvc.perform(delete("/api/me/gyms/loc_001")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isNoContent());

        // Verify it's soft deleted (isActive = false)
        Location deleted = locationRepository.findById(TEST_USER, "loc_001").orElseThrow();
        assert !deleted.isActive();
    }

    @Test
    void setDefaultLocation() throws Exception {
        // Create two locations
        Location loc1 = createLocation("loc_001", "Gym 1", true);
        locationRepository.save(loc1);

        Location loc2 = createLocation("loc_002", "Gym 2", true);
        locationRepository.save(loc2);

        // Set loc_002 as default
        mvc.perform(post("/api/me/gyms/loc_002/default")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isNoContent());

        // Verify loc_002 is default and loc_001 is not
        Location updated1 = locationRepository.findById(TEST_USER, "loc_001").orElseThrow();
        Location updated2 = locationRepository.findById(TEST_USER, "loc_002").orElseThrow();

        assert !updated1.isDefault();
        assert updated2.isDefault();
    }

    @Test
    void setDefaultLocationNotFound() throws Exception {
        mvc.perform(post("/api/me/gyms/nonexistent/default")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isNotFound());
    }

    @Test
    void updateNonExistentLocationFails() throws Exception {
        UpdateLocationRequest update = new UpdateLocationRequest(
            "New Name",
            null,
            null,
            null,
            null,
            null
        );

        mvc.perform(patch("/api/me/gyms/nonexistent")
                .header("X-Dev-User", TEST_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
            .andExpect(status().isNotFound());
    }

    @Test
    void deleteNonExistentLocationFails() throws Exception {
        mvc.perform(delete("/api/me/gyms/nonexistent")
                .header("X-Dev-User", TEST_USER))
            .andExpect(status().isNotFound());
    }

    // Helper method
    private Location createLocation(String locationId, String name, boolean isActive) {
        Instant now = Instant.now();
        return new Location(
            TEST_USER,
            locationId,
            name,
            "123 Main St",
            null, // coverPhotoUrl
            false, // is24Hours
            null, // hours
            List.of(), // amenities
            List.of(), // equipmentIds
            java.util.Map.of(), // equipmentSpecs
            false, // isDefault
            isActive,
            now,
            now
        );
    }
}
