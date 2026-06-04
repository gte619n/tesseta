package com.gte619n.healthfitness.admin;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.admin.RejectRequest;
import com.gte619n.healthfitness.api.admin.UpdateEquipmentRequest;
import com.gte619n.healthfitness.api.equipment.CreateEquipmentRequest;
import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
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

    private static final String PENDING_ID = "eq_test_pending";
    // AdminCheckAspect hard-codes admin@example.com as an admin email.
    private static final String ADMIN_EMAIL = "admin@example.com";
    private static final String USER_EMAIL = "user@example.com";

    @Autowired private MockMvc mockMvc;
    @Autowired private EquipmentRepository equipmentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        equipmentRepository.delete(PENDING_ID);
        Instant now = Instant.now();
        equipmentRepository.save(new Equipment(
            PENDING_ID,
            "Test Equipment",
            "Free Weights",
            "Dumbbells",
            SpecSchema.BODYWEIGHT,
            Map.of("weight", "50"),
            null,
            java.util.List.of(),
            ImageStatus.PENDING,
            "user123",
            EquipmentStatus.PENDING_REVIEW,
            "user123",
            0,
            now,
            now,
            null
        ));
        // AdminCheckAspect resolves the caller via the X-Dev-User header,
        // but the response serializer reads the contributor's display name
        // out of UserRepository — seed a matching user so listPending() can
        // hydrate it without an NPE-ish fallback path.
        userRepository.save(new User("user123", "contributor@example.com", "Contributor", null, null, now, now));
    }

    @Test
    void listPending_asAdmin_returnsSubmissions() throws Exception {
        mockMvc.perform(get("/api/admin/equipment/pending")
                .header("X-Dev-User", ADMIN_EMAIL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
            .andExpect(jsonPath("$[?(@.equipmentId == '" + PENDING_ID + "')]", hasSize(1)));
    }

    @Test
    void listPending_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/admin/equipment/pending")
                .header("X-Dev-User", USER_EMAIL))
            .andExpect(status().isForbidden());
    }

    @Test
    void approve_asAdmin_setsStatusActiveAndClearsOwner() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", PENDING_ID)
                .header("X-Dev-User", ADMIN_EMAIL))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId", is(PENDING_ID)))
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andExpect(jsonPath("$.ownerId").doesNotExist())
            .andExpect(jsonPath("$.contributorId", is("user123")));

        Equipment updated = equipmentRepository.findById(PENDING_ID).orElseThrow();
        assert updated.status() == EquipmentStatus.ACTIVE;
        assert updated.ownerId() == null;
    }

    @Test
    void approve_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", PENDING_ID)
                .header("X-Dev-User", USER_EMAIL))
            .andExpect(status().isForbidden());
    }

    @Test
    void reject_asAdmin_setsStatusRejected() throws Exception {
        RejectRequest request = new RejectRequest("Not suitable for catalog");

        mockMvc.perform(post("/api/admin/equipment/{id}/reject", PENDING_ID)
                .header("X-Dev-User", ADMIN_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId", is(PENDING_ID)))
            .andExpect(jsonPath("$.status", is("REJECTED")))
            .andExpect(jsonPath("$.ownerId", is("user123")));

        Equipment updated = equipmentRepository.findById(PENDING_ID).orElseThrow();
        assert updated.status() == EquipmentStatus.REJECTED;
    }

    @Test
    void reject_asNonAdmin_returns403() throws Exception {
        RejectRequest request = new RejectRequest("Not suitable");

        mockMvc.perform(post("/api/admin/equipment/{id}/reject", PENDING_ID)
                .header("X-Dev-User", USER_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void create_asAdmin_addsActiveCatalogEquipment() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Olympic Barbell",
            "Free Weights",
            "Barbells",
            SpecSchema.PLATE_LOADED,
            Map.of("barWeight", 45)
        );

        String body = mockMvc.perform(post("/api/admin/equipment")
                .header("X-Dev-User", ADMIN_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.name", is("Olympic Barbell")))
            .andExpect(jsonPath("$.status", is("ACTIVE")))
            .andExpect(jsonPath("$.ownerId").doesNotExist())
            .andExpect(jsonPath("$.contributorId").doesNotExist())
            .andReturn().getResponse().getContentAsString();

        String createdId = objectMapper.readTree(body).get("equipmentId").asText();
        Equipment created = equipmentRepository.findById(createdId).orElseThrow();
        assert created.status() == EquipmentStatus.ACTIVE;
        assert created.ownerId() == null;
    }

    @Test
    void create_asNonAdmin_returns403() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Olympic Barbell", "Free Weights", "Barbells", SpecSchema.PLATE_LOADED, Map.of()
        );

        mockMvc.perform(post("/api/admin/equipment")
                .header("X-Dev-User", USER_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void create_withInvalidSubcategory_returns400() throws Exception {
        CreateEquipmentRequest request = new CreateEquipmentRequest(
            "Mystery Machine", "Free Weights", "NotARealSubcategory", SpecSchema.SELECTORIZED, Map.of()
        );

        mockMvc.perform(post("/api/admin/equipment")
                .header("X-Dev-User", ADMIN_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isBadRequest());
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

        mockMvc.perform(patch("/api/admin/equipment/{id}", PENDING_ID)
                .header("X-Dev-User", ADMIN_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.equipmentId", is(PENDING_ID)))
            .andExpect(jsonPath("$.name", is("Updated Equipment Name")))
            .andExpect(jsonPath("$.subcategory", is("Kettlebells")))
            .andExpect(jsonPath("$.specs.weight", is("60")));

        Equipment updated = equipmentRepository.findById(PENDING_ID).orElseThrow();
        assert updated.name().equals("Updated Equipment Name");
        assert updated.subcategory().equals("Kettlebells");
    }

    @Test
    void edit_asNonAdmin_returns403() throws Exception {
        UpdateEquipmentRequest request = new UpdateEquipmentRequest(
            "Updated Name", null, null, null, null
        );

        mockMvc.perform(patch("/api/admin/equipment/{id}", PENDING_ID)
                .header("X-Dev-User", USER_EMAIL)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isForbidden());
    }

    @Test
    void regenerateImage_asAdmin_returnsOk() throws Exception {
        // Controller fires generateImageAsync on a background thread, so we
        // can't reliably assert the post-call state of imageStatus/imageUrl —
        // by the time we read, the async worker may already have moved it on.
        // Verify the externally-observable contract instead: admin can call
        // the endpoint and it returns 200.
        mockMvc.perform(post("/api/admin/equipment/{id}/regenerate-image", PENDING_ID)
                .header("X-Dev-User", ADMIN_EMAIL))
            .andExpect(status().isOk());
    }

    @Test
    void regenerateImage_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/regenerate-image", PENDING_ID)
                .header("X-Dev-User", USER_EMAIL))
            .andExpect(status().isForbidden());
    }

    @Test
    void approve_nonPendingEquipment_returns400() throws Exception {
        mockMvc.perform(post("/api/admin/equipment/{id}/approve", PENDING_ID)
                .header("X-Dev-User", ADMIN_EMAIL))
            .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/equipment/{id}/approve", PENDING_ID)
                .header("X-Dev-User", ADMIN_EMAIL))
            .andExpect(status().is4xxClientError());
    }
}
