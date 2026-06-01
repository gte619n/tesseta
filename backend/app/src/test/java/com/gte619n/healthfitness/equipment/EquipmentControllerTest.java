package com.gte619n.healthfitness.equipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.equipment.CreateEquipmentRequest;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.gte619n.healthfitness.testsupport.InMemoryEquipmentRepository;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
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
class EquipmentControllerTest {

    private static final String USER = "user-123";

    @Autowired MockMvc mvc;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void resetStore() {
        // Tests assert exact counts; the in-memory store lives for the whole
        // Spring context, so wipe it before each test to keep them isolated.
        ((InMemoryEquipmentRepository) equipmentRepository).clear();
    }

    @Test
    void listAllCatalogEquipment() throws Exception {
        equipmentRepository.save(catalog("eq_bench001", "Flat Bench", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of("adjustable", false)));
        equipmentRepository.save(catalog("eq_squat001", "Squat Rack", "Benches & Racks", "Racks", SpecSchema.PLATE_LOADED, Map.of("adjustable", true, "maxWeight", 500)));
        // user-submitted, should NOT appear in /api/equipment
        equipmentRepository.save(userEquipment("eq_user001", USER, "My Custom Bench", "Benches & Racks", "Benches"));

        mvc.perform(get("/api/equipment").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void searchByName() throws Exception {
        equipmentRepository.save(catalog("eq_bench001", "Flat Bench Press", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of()));
        equipmentRepository.save(catalog("eq_bench002", "Incline Bench Press", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of()));
        equipmentRepository.save(catalog("eq_squat001", "Squat Rack", "Benches & Racks", "Racks", SpecSchema.PLATE_LOADED, Map.of()));

        mvc.perform(get("/api/equipment").header("X-Dev-User", USER).param("search", "bench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void filterByCategory() throws Exception {
        equipmentRepository.save(catalog("eq_bench001", "Flat Bench", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of()));
        equipmentRepository.save(catalog("eq_db001", "Dumbbell 20kg", "Free Weights", "Dumbbells", SpecSchema.PLATE_LOADED, Map.of("weight", 20)));

        mvc.perform(get("/api/equipment").header("X-Dev-User", USER).param("category", "Free Weights"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].category").value("Free Weights"));
    }

    @Test
    void filterByCategoryAndSubcategory() throws Exception {
        equipmentRepository.save(catalog("eq_bench001", "Flat Bench", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of()));
        equipmentRepository.save(catalog("eq_rack001", "Squat Rack", "Benches & Racks", "Racks", SpecSchema.PLATE_LOADED, Map.of()));

        mvc.perform(get("/api/equipment")
                .header("X-Dev-User", USER)
                .param("category", "Benches & Racks")
                .param("subcategory", "Benches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].subcategory").value("Benches"));
    }

    @Test
    void getSingleEquipment() throws Exception {
        equipmentRepository.save(catalog("eq_bench001", "Flat Bench", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of("adjustable", false)));

        mvc.perform(get("/api/equipment/eq_bench001").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId").value("eq_bench001"))
            .andExpect(jsonPath("$.name").value("Flat Bench"))
            .andExpect(jsonPath("$.category").value("Benches & Racks"))
            .andExpect(jsonPath("$.subcategory").value("Benches"))
            .andExpect(jsonPath("$.specSchema").value("BODYWEIGHT"))
            .andExpect(jsonPath("$.specs.adjustable").value(false));
    }

    @Test
    void getSingleEquipmentNotFound() throws Exception {
        mvc.perform(get("/api/equipment/nonexistent").header("X-Dev-User", USER))
            .andExpect(status().isNotFound());
    }

    @Test
    void getCategoryTree() throws Exception {
        mvc.perform(get("/api/equipment/categories").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.categories").isMap())
            .andExpect(jsonPath("$.categories['Free Weights']").isArray())
            .andExpect(jsonPath("$.categories['Free Weights']").value(
                org.hamcrest.Matchers.containsInAnyOrder(
                    "Barbells", "Dumbbells", "Kettlebells", "Weight Plates", "Other"
                )
            ))
            .andExpect(jsonPath("$.categories['Machines - Strength']").isArray())
            .andExpect(jsonPath("$.categories['Machines - Strength']").value(
                org.hamcrest.Matchers.containsInAnyOrder(
                    "Chest", "Back", "Shoulders", "Arms", "Legs", "Core"
                )
            ));
    }

    @Test
    void submitNewEquipment() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Custom Bench", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of("adjustable", true));

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.equipmentId").exists())
            .andExpect(jsonPath("$.name").value("Custom Bench"))
            .andExpect(jsonPath("$.ownerId").value(USER))
            .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
            .andExpect(jsonPath("$.imageStatus").value("PENDING"))
            .andExpect(jsonPath("$.contributorId").value(USER))
            .andExpect(jsonPath("$.exerciseCount").value(0));
    }

    @Test
    void submitEquipmentWithValidation() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of());

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitEquipmentWithInvalidCategory() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Custom Equipment", "InvalidCategory", "Benches", SpecSchema.BODYWEIGHT, Map.of());

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitEquipmentWithInvalidSubcategory() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Custom Equipment", "Benches & Racks", "InvalidSubcategory", SpecSchema.BODYWEIGHT, Map.of());

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listUserSubmissions() throws Exception {
        equipmentRepository.save(userEquipment("eq_user001", USER, "My Bench", "Benches & Racks", "Benches"));
        equipmentRepository.save(userEquipment("eq_user002", USER, "My Squat Rack", "Benches & Racks", "Racks"));
        equipmentRepository.save(userEquipment("eq_other001", "user-456", "Other's Bench", "Benches & Racks", "Benches"));

        mvc.perform(get("/api/me/equipment")
                .header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deleteOwnSubmission() throws Exception {
        equipmentRepository.save(userEquipment("eq_user001", USER, "My Bench", "Benches & Racks", "Benches"));

        mvc.perform(delete("/api/me/equipment/eq_user001")
                .header("X-Dev-User", USER))
            .andExpect(status().isNoContent());

        mvc.perform(get("/api/equipment/eq_user001").header("X-Dev-User", USER))
            .andExpect(status().isNotFound());
    }

    @Test
    void cannotDeleteApprovedEquipment() throws Exception {
        // Equipment that's been approved (ACTIVE) but still has an ownerId — the
        // controller must refuse deletion because it now belongs to the catalog.
        Equipment approved = catalog("eq_approved001", "Approved Bench", "Benches & Racks", "Benches", SpecSchema.BODYWEIGHT, Map.of());
        Equipment withOwner = new Equipment(
            approved.equipmentId(), approved.name(), approved.category(), approved.subcategory(),
            approved.specSchema(), approved.specs(), approved.imageUrl(), approved.imageCandidates(),
            approved.imageStatus(),
            USER, EquipmentStatus.ACTIVE, approved.contributorId(), approved.exerciseCount(),
            approved.createdAt(), approved.updatedAt(), null);
        equipmentRepository.save(withOwner);

        mvc.perform(delete("/api/me/equipment/eq_approved001")
                .header("X-Dev-User", USER))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cannotDeleteOtherUsersEquipment() throws Exception {
        equipmentRepository.save(userEquipment("eq_user001", "user-456", "Other's Bench", "Benches & Racks", "Benches"));

        mvc.perform(delete("/api/me/equipment/eq_user001")
                .header("X-Dev-User", USER))
            .andExpect(status().isBadRequest());
    }

    private Equipment catalog(String id, String name, String category, String subcategory,
                              SpecSchema schema, Map<String, Object> specs) {
        Instant now = Instant.now();
        return new Equipment(id, name, category, subcategory, schema, specs,
            null, java.util.List.of(), ImageStatus.PENDING, null, EquipmentStatus.ACTIVE, "system",
            0, now, now, null);
    }

    private Equipment userEquipment(String id, String userId, String name, String category, String subcategory) {
        Instant now = Instant.now();
        return new Equipment(id, name, category, subcategory, SpecSchema.BODYWEIGHT, Map.of(),
            null, java.util.List.of(), ImageStatus.PENDING, userId, EquipmentStatus.PENDING_REVIEW, userId,
            0, now, now, null);
    }
}
