package com.gte619n.healthfitness.api.admin;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
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
class AdminEquipmentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EquipmentRepository equipmentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private Equipment pendingEquipment;

    @BeforeEach
    void setUp() {
        // Create a pending equipment for testing
        Instant now = Instant.now();
        pendingEquipment = new Equipment(
            "eq_test_pending",
            "Test Equipment",
            "Free Weights",
            "Dumbbells",
            SpecSchema.BODYWEIGHT,
            Map.of("weight", "50"),
            null,
            ImageStatus.PENDING,
            "user123",
            EquipmentStatus.PENDING_REVIEW,
            "user123",
            0,
            now,
            now,
            null
        );
        equipmentRepository.save(pendingEquipment);
    }

    @Test
    void listPending_asAdmin_returnsSubmissions() throws Exception {
        mockMvc.perform(get("/api/admin/equipment/pending")
                .header("X-Dev-User", "admin@example.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
            .andExpect(jsonPath("$[0].equipmentId", is("eq_test_pending")))
            .andExpect(jsonPath("$[0].name", is("Test Equipment")))
            .andExpect(jsonPath("$[0].contributorId", is("user123")));
    }

    @Test
    void listPending_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/equipment/pending")
                .header("X-Dev-User", "user@example.com"))
            .andExpect(status().isForbidden());
    }

    @Test
    void approve_asAdmin_setsStatusActiveAndClearsOwner() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", "eq_test_pending")
                .header("X-Dev-User", "admin@example.com"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId", is("eq_test_pending")))
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andExpect(jsonPath("$.ownerId").doesNotExist())
            .andExpect(jsonPath("$.contributorId", is("user123")));

        // Verify the equipment was updated in the repository
        Equipment updated = equipmentRepository.findById("eq_test_pending").orElseThrow();
        assert updated.status() == EquipmentStatus.ACTIVE;
        assert updated.ownerId() == null;
    }

    @Test
    void approve_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", "eq_test_pending")
                .header("X-Dev-User", "user@example.com"))
            .andExpect(status().isForbidden());
    }

    @Test
    void reject_asAdmin_setsStatusRejected() throws Exception {
        RejectRequest request = new RejectRequest("Not suitable for catalog");

        mockMvc.perform(post("/api/admin/equipment/{id}/reject", "eq_test_pending")
                .header("X-Dev-User", "admin@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId", is("eq_test_pending")))
            .andExpect(jsonPath("$.status", is("REJECTED")))
            .andExpect(jsonPath("$.ownerId", is("user123"))); // ownerId should remain

        // Verify the equipment was updated in the repository
        Equipment updated = equipmentRepository.findById("eq_test_pending").orElseThrow();
        assert updated.status() == EquipmentStatus.REJECTED;
    }

    @Test
    void reject_asNonAdmin_returns403() throws Exception {
        RejectRequest request = new RejectRequest("Not suitable");

        mockMvc.perform(post("/api/admin/equipment/{id}/reject", "eq_test_pending")
                .header("X-Dev-User", "user@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void edit_asAdmin_updatesEquipmentDetails() throws Exception {
        UpdateEquipmentRequest request = new UpdateEquipmentRequest(
            "Updated Equipment Name",
            "Free Weights",
            "Kettlebells",
            SpecSchema.BODYWEIGHT,
            Map.of("weight", "60")
        );

        mockMvc.perform(patch("/api/admin/equipment/{id}", "eq_test_pending")
                .header("X-Dev-User", "admin@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId", is("eq_test_pending")))
            .andExpect(jsonPath("$.name", is("Updated Equipment Name")))
            .andExpect(jsonPath("$.subcategory", is("Kettlebells")))
            .andExpect(jsonPath("$.specs.weight", is("60")));

        // Verify the equipment was updated in the repository
        Equipment updated = equipmentRepository.findById("eq_test_pending").orElseThrow();
        assert updated.name().equals("Updated Equipment Name");
        assert updated.subcategory().equals("Kettlebells");
    }

    @Test
    void edit_asNonAdmin_returns403() throws Exception {
        UpdateEquipmentRequest request = new UpdateEquipmentRequest(
            "Updated Name",
            null,
            null,
            null,
            null
        );

        mockMvc.perform(patch("/api/admin/equipment/{id}", "eq_test_pending")
                .header("X-Dev-User", "user@example.com")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void regenerateImage_asAdmin_setsImageStatusToPending() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/regenerate-image", "eq_test_pending")
                .header("X-Dev-User", "admin@example.com"))
            .andExpect(status().isOk());

        // Verify the equipment image status was updated
        Equipment updated = equipmentRepository.findById("eq_test_pending").orElseThrow();
        assert updated.imageStatus() == ImageStatus.PENDING;
        assert updated.imageUrl() == null;
    }

    @Test
    void regenerateImage_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/regenerate-image", "eq_test_pending")
                .header("X-Dev-User", "user@example.com"))
            .andExpect(status().isForbidden());
    }

    @Test
    void approve_nonPendingEquipment_returns400() throws Exception {
        // First approve the equipment
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", "eq_test_pending")
                .header("X-Dev-User", "admin@example.com"))
            .andExpect(status().isOk());

        // Try to approve again
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", "eq_test_pending")
                .header("X-Dev-User", "admin@example.com"))
            .andExpect(status().is4xxClientError());
    }
}
