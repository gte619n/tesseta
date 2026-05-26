package com.gte619n.healthfitness.api.equipment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.equipment.EquipmentController;
import com.gte619n.healthfitness.api.equipment.UserEquipmentController;
import com.gte619n.healthfitness.core.equipment.*;
import com.gte619n.healthfitness.testsupport.TestAuthConfig;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

// Integration test for Equipment API endpoints
// Uses in-memory repository and disabled security for simplicity
@SpringBootTest(classes = {
    EquipmentController.class,
    UserEquipmentController.class,
    EquipmentService.class,
    TestPersistenceConfig.class,
    TestAuthConfig.class,
    EquipmentControllerTest.TestSecurityConfig.class
})
@ActiveProfiles("test")
class EquipmentControllerTest {

    @TestConfiguration
    @EnableWebSecurity
    static class TestSecurityConfig {
        @Bean
        SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
            return http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Autowired WebApplicationContext webApplicationContext;
    @Autowired EquipmentRepository equipmentRepository;
    @Autowired ObjectMapper objectMapper;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // Set up MockMvc
        mvc = MockMvcBuilders
            .webAppContextSetup(webApplicationContext)
            .build();
    }

    @Test
    void listAllCatalogEquipment() throws Exception {
        // Create catalog equipment (ownerId=null, status=ACTIVE)
        Equipment bench = createCatalogEquipment(
            "eq_bench001",
            "Flat Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of("adjustable", false)
        );
        equipmentRepository.save(bench);

        Equipment squat = createCatalogEquipment(
            "eq_squat001",
            "Squat Rack",
            "Benches & Racks",
            "Racks",
            SpecSchema.PLATE_LOADED,
            Map.of("adjustable", true, "maxWeight", 500)
        );
        equipmentRepository.save(squat);

        // Create user-submitted equipment (should NOT appear in catalog)
        Equipment userEquip = createUserEquipment(
            "eq_user001",
            "user-123",
            "My Custom Bench",
            "Benches & Racks",
            "Benches"
        );
        equipmentRepository.save(userEquip);

        mvc.perform(get("/api/equipment"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].equipmentId").exists())
            .andExpect(jsonPath("$[0].name").exists())
            .andExpect(jsonPath("$[0].category").exists())
            .andExpect(jsonPath("$[0].subcategory").exists());
    }

    @Test
    void searchByName() throws Exception {
        Equipment bench1 = createCatalogEquipment(
            "eq_bench001",
            "Flat Bench Press",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );
        equipmentRepository.save(bench1);

        Equipment bench2 = createCatalogEquipment(
            "eq_bench002",
            "Incline Bench Press",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );
        equipmentRepository.save(bench2);

        Equipment squat = createCatalogEquipment(
            "eq_squat001",
            "Squat Rack",
            "Benches & Racks",
            "Racks",
            SpecSchema.PLATE_LOADED,
            Map.of()
        );
        equipmentRepository.save(squat);

        mvc.perform(get("/api/equipment").param("search", "bench"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void filterByCategory() throws Exception {
        Equipment bench = createCatalogEquipment(
            "eq_bench001",
            "Flat Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );
        equipmentRepository.save(bench);

        Equipment dumbbell = createCatalogEquipment(
            "eq_db001",
            "Dumbbell 20kg",
            "Free Weights",
            "Dumbbells",
            SpecSchema.PLATE_LOADED,
            Map.of("weight", 20)
        );
        equipmentRepository.save(dumbbell);

        mvc.perform(get("/api/equipment").param("category", "Free Weights"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].category").value("Free Weights"));
    }

    @Test
    void filterByCategoryAndSubcategory() throws Exception {
        Equipment bench = createCatalogEquipment(
            "eq_bench001",
            "Flat Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );
        equipmentRepository.save(bench);

        Equipment rack = createCatalogEquipment(
            "eq_rack001",
            "Squat Rack",
            "Benches & Racks",
            "Racks",
            SpecSchema.PLATE_LOADED,
            Map.of()
        );
        equipmentRepository.save(rack);

        mvc.perform(get("/api/equipment")
                .param("category", "Benches & Racks")
                .param("subcategory", "Benches"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].subcategory").value("Benches"));
    }

    @Test
    void getSingleEquipment() throws Exception {
        Equipment bench = createCatalogEquipment(
            "eq_bench001",
            "Flat Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of("adjustable", false)
        );
        equipmentRepository.save(bench);

        mvc.perform(get("/api/equipment/eq_bench001"))
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
        mvc.perform(get("/api/equipment/nonexistent"))
            .andExpect(status().isNotFound());
    }

    @Test
    void getCategoryTree() throws Exception {
        mvc.perform(get("/api/equipment/categories"))
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
            "Custom Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of("adjustable", true)
        );

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", "user-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.equipmentId").exists())
            .andExpect(jsonPath("$.name").value("Custom Bench"))
            .andExpect(jsonPath("$.ownerId").value("user-123"))
            .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
            .andExpect(jsonPath("$.imageStatus").value("PENDING"))
            .andExpect(jsonPath("$.contributorId").value("user-123"))
            .andExpect(jsonPath("$.exerciseCount").value(0));
    }

    @Test
    void submitEquipmentWithValidation() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "", // empty name
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", "user-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitEquipmentWithInvalidCategory() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Custom Equipment",
            "InvalidCategory",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", "user-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void submitEquipmentWithInvalidSubcategory() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Custom Equipment",
            "Benches & Racks",
            "InvalidSubcategory",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );

        mvc.perform(post("/api/me/equipment")
                .header("X-Dev-User", "user-123")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
    }

    @Test
    void listUserSubmissions() throws Exception {
        // User's own equipment
        Equipment userEquip1 = createUserEquipment(
            "eq_user001",
            "user-123",
            "My Bench",
            "Benches & Racks",
            "Benches"
        );
        equipmentRepository.save(userEquip1);

        Equipment userEquip2 = createUserEquipment(
            "eq_user002",
            "user-123",
            "My Squat Rack",
            "Benches & Racks",
            "Racks"
        );
        equipmentRepository.save(userEquip2);

        // Other user's equipment
        Equipment otherEquip = createUserEquipment(
            "eq_other001",
            "user-456",
            "Other's Bench",
            "Benches & Racks",
            "Benches"
        );
        equipmentRepository.save(otherEquip);

        mvc.perform(get("/api/me/equipment")
                .header("X-Dev-User", "user-123"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$").isArray())
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void deleteOwnSubmission() throws Exception {
        Equipment userEquip = createUserEquipment(
            "eq_user001",
            "user-123",
            "My Bench",
            "Benches & Racks",
            "Benches"
        );
        equipmentRepository.save(userEquip);

        mvc.perform(delete("/api/me/equipment/eq_user001")
                .header("X-Dev-User", "user-123"))
            .andExpect(status().isNoContent());

        // Verify it's deleted
        mvc.perform(get("/api/equipment/eq_user001"))
            .andExpect(status().isNotFound());
    }

    @Test
    void cannotDeleteApprovedEquipment() throws Exception {
        // Create approved equipment
        Equipment approved = createCatalogEquipment(
            "eq_approved001",
            "Approved Bench",
            "Benches & Racks",
            "Benches",
            SpecSchema.BODYWEIGHT,
            Map.of()
        );
        // Set ownerId to simulate it was originally user-submitted
        Equipment withOwner = new Equipment(
            approved.equipmentId(),
            approved.name(),
            approved.category(),
            approved.subcategory(),
            approved.specSchema(),
            approved.specs(),
            approved.imageUrl(),
            approved.imageStatus(),
            "user-123", // ownerId
            EquipmentStatus.ACTIVE, // status is ACTIVE, not PENDING_REVIEW
            approved.contributorId(),
            approved.exerciseCount(),
            approved.createdAt(),
            approved.updatedAt(),
            null
        );
        equipmentRepository.save(withOwner);

        mvc.perform(delete("/api/me/equipment/eq_approved001")
                .header("X-Dev-User", "user-123"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void cannotDeleteOtherUsersEquipment() throws Exception {
        Equipment userEquip = createUserEquipment(
            "eq_user001",
            "user-456",
            "Other's Bench",
            "Benches & Racks",
            "Benches"
        );
        equipmentRepository.save(userEquip);

        mvc.perform(delete("/api/me/equipment/eq_user001")
                .header("X-Dev-User", "user-123"))
            .andExpect(status().isBadRequest());
    }

    // Helper methods
    private Equipment createCatalogEquipment(
        String equipmentId,
        String name,
        String category,
        String subcategory,
        SpecSchema specSchema,
        Map<String, Object> specs
    ) {
        Instant now = Instant.now();
        return new Equipment(
            equipmentId,
            name,
            category,
            subcategory,
            specSchema,
            specs,
            null, // imageUrl
            ImageStatus.PENDING,
            null, // ownerId is null for catalog equipment
            EquipmentStatus.ACTIVE,
            "system", // contributorId
            0,
            now,
            now,
            null
        );
    }

    private Equipment createUserEquipment(
        String equipmentId,
        String userId,
        String name,
        String category,
        String subcategory
    ) {
        Instant now = Instant.now();
        return new Equipment(
            equipmentId,
            name,
            category,
            subcategory,
            SpecSchema.BODYWEIGHT,
            Map.of(),
            null, // imageUrl
            ImageStatus.PENDING,
            userId, // ownerId
            EquipmentStatus.PENDING_REVIEW,
            userId, // contributorId
            0,
            now,
            now,
            null
        );
    }
}
