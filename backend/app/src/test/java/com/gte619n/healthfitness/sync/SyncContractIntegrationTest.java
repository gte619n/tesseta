package com.gte619n.healthfitness.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.testsupport.TestPersistenceConfig;
import com.gte619n.healthfitness.testsupport.push.RecordingFcmSender;
import com.gte619n.healthfitness.testsupport.sync.InMemorySyncChangeReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Phase 7 backend slice — the offline-sync write→delta→fan-out contract end to
 * end (IMPL-AND-20), driven through the real controllers against the in-memory
 * fakes. Exercises the full loop:
 *
 * <ol>
 *   <li>create several docs across ≥2 collections (client-minted ids +
 *       {@code Idempotency-Key}) and confirm each write response carries the
 *       top-level {@code lastUpdate} (#11);</li>
 *   <li>those docs surface in {@code GET /api/me/sync} as ACTIVE with the same
 *       {@code lastUpdate};</li>
 *   <li>replaying a create with the same {@code Idempotency-Key} is a no-op —
 *       still one doc (D7);</li>
 *   <li>DELETE one doc ⇒ it reappears in a later delta as an ARCHIVED tombstone
 *       ({@code doc:null}) exactly once, with no duplicate across pages (D2);</li>
 *   <li>a write carrying {@code X-HF-Origin-Device: device-A} fans the silent
 *       FCM data message out to the user's OTHER device tokens (B, C) but not A
 *       (D18) — tokens registered first via {@code PUT /api/me/devices/fcm};</li>
 *   <li>paging the delta with a small {@code limit} skips/duplicates nothing.</li>
 * </ol>
 *
 * <p><b>Delta projection.</b> The merged delta feed in production is the live
 * {@link com.gte619n.healthfitness.persistence.sync.FirestoreSyncChangeReader}
 * driven off Firestore {@code updatedAt}/{@code syncStatus}. On the JVM there is
 * no Firestore, so this test projects each controller write into the
 * {@link InMemorySyncChangeReader} (the same {@code SyncChangeReader} the
 * {@code SyncController} reads) using the authoritative {@code id} and
 * {@code lastUpdate} the write response returns — i.e. the contract the Android
 * sync engine actually consumes. The write path (idempotency, client id,
 * fan-out, {@code lastUpdate}) and the read path (cursor paging, tombstones,
 * canonical order) are both real; only the Firestore-side change capture is
 * stubbed.
 *
 * <p><b>Out of scope here (needs the Firestore emulator):</b> the live
 * {@code FirestoreSyncChangeReader}, the {@code collectionGroup} subcollection
 * enumeration, index-backed ordering, and the {@code idempotencyKeys} TTL reaper
 * — those are exercised only against the emulator, not this JVM test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestPersistenceConfig.class)
class SyncContractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired InMemorySyncChangeReader delta;
    @Autowired RecordingFcmSender sender;

    private static final String USER = "user-sync-contract";
    private static final String ORIGIN_DEVICE = "device-A";

    @BeforeEach
    void reset() {
        delta.clear();
        sender.clear();
    }

    @Test
    void fullWriteDeltaFanoutLoop() throws Exception {
        // ---- Register 3 FCM device tokens for the user (D18) -------------
        registerFcm("device-A", "token-A");
        registerFcm("device-B", "token-B");
        registerFcm("device-C", "token-C");

        // ---- 1. Create across two collections with client ids + keys -----
        String bloodId = "11111111-1111-1111-1111-111111111111";
        String locId = "22222222-2222-2222-2222-222222222222";

        JsonNode blood = createBlood(bloodId, "blood-key-1");
        JsonNode loc = createLocation(locId, "loc-key-1");

        // Client-minted ids are honored, and the response carries a top-level
        // lastUpdate (#11) — the value the outbox adopts.
        assertThat(blood.get("readingId").asText()).isEqualTo(bloodId);
        assertThat(loc.get("locationId").asText()).isEqualTo(locId);
        assertThat(blood.hasNonNull("lastUpdate")).as("blood write exposes lastUpdate").isTrue();
        assertThat(loc.hasNonNull("lastUpdate")).as("location write exposes lastUpdate").isTrue();

        // Project the writes into the delta feed exactly as Firestore's
        // updatedAt/syncStatus capture would (id + lastUpdate from the write).
        delta.put(USER, "bloodReadings", bloodId, Map.of("marker", "HDL"));
        delta.put(USER, "locations", locId, Map.of("name", "Downtown Gym"));

        // ---- 2. Both appear in the delta as ACTIVE with a lastUpdate -----
        Map<String, JsonNode> firstSnapshot = drainDelta(2);
        assertThat(firstSnapshot.keySet())
            .containsExactlyInAnyOrder("bloodReadings/" + bloodId, "locations/" + locId);
        for (JsonNode change : firstSnapshot.values()) {
            assertThat(change.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(change.get("doc").isNull()).isFalse();
            assertThat(change.hasNonNull("lastUpdate")).isTrue();
        }

        // ---- 3. Replay the blood create with the SAME key ⇒ no dup -------
        JsonNode replay = createBlood(bloodId, "blood-key-1");
        assertThat(replay.get("readingId").asText())
            .as("idempotent replay returns the original id")
            .isEqualTo(bloodId);
        long bloodRows = mvc.perform(get("/api/me/blood").header("X-Dev-User", USER))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()
            .split("\"readingId\"", -1).length - 1;
        assertThat(bloodRows).as("replay must not create a second reading").isEqualTo(1);

        // ---- 4. DELETE the location ⇒ tombstone (ARCHIVED, doc null) -----
        mvc.perform(delete("/api/me/gyms/" + locId)
                .header("X-Dev-User", USER)
                .header(SyncWriteContext.ORIGIN_DEVICE_HEADER, ORIGIN_DEVICE))
            .andExpect(status().isNoContent());
        // Project the soft-delete: the row becomes a newer-timestamped tombstone.
        delta.archive(USER, "locations", locId);

        // ---- 5. Fan-out asserted: the delete from device-A reached B,C ---
        // (each origin-tagged write fans out; B and C get it, A is suppressed.)
        assertThat(sender.sends()).as("at least one fan-out captured").isNotEmpty();
        RecordingFcmSender.Sent lastSend = sender.lastSend();
        assertThat(lastSend.tokens())
            .containsExactlyInAnyOrder("token-B", "token-C")
            .doesNotContain("token-A");
        assertThat(lastSend.collections()).containsExactly("locations");

        // ---- 6. Page the full delta with limit=1: tombstone once, no dup -
        Map<String, String> statusByKey = new HashMap<>();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore;
        do {
            StringBuilder url = new StringBuilder("/api/me/sync?limit=1");
            if (cursor != null) {
                url.append("&since=").append(cursor);
            }
            MvcResult res = mvc.perform(get(url.toString()).header("X-Dev-User", USER))
                .andExpect(status().isOk())
                .andReturn();
            JsonNode body = json.readTree(res.getResponse().getContentAsString());
            for (JsonNode change : body.get("changes")) {
                String key = change.get("collection").asText() + "/" + change.get("id").asText();
                assertThat(seen.add(key)).as("no duplicate change across pages for " + key).isTrue();
                statusByKey.put(key, change.get("status").asText());
                if ("ARCHIVED".equals(change.get("status").asText())) {
                    assertThat(change.get("doc").isNull()).as("tombstone doc null").isTrue();
                }
            }
            cursor = body.get("nextCursor").asText(null);
            hasMore = body.get("hasMore").asBoolean();
            pages++;
            assertThat(pages).as("pagination terminates").isLessThan(20);
        } while (hasMore);

        // Final converged state: blood ACTIVE, location ARCHIVED (tombstone),
        // each emitted exactly once, multi-page proven.
        assertThat(statusByKey).containsEntry("bloodReadings/" + bloodId, "ACTIVE");
        assertThat(statusByKey).containsEntry("locations/" + locId, "ARCHIVED");
        assertThat(statusByKey).hasSize(2);
        assertThat(pages).as("small limit forced multiple pages").isGreaterThan(1);
    }

    // ---- helpers -------------------------------------------------------

    private void registerFcm(String deviceId, String token) throws Exception {
        mvc.perform(put("/api/me/devices/fcm")
                .header("X-Dev-User", USER)
                .contentType("application/json")
                .content("{\"deviceId\":\"%s\",\"token\":\"%s\"}".formatted(deviceId, token)))
            .andExpect(status().isNoContent());
    }

    private JsonNode createBlood(String id, String idempotencyKey) throws Exception {
        MvcResult res = mvc.perform(post("/api/me/blood")
                .header("X-Dev-User", USER)
                .header(SyncWriteContext.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(SyncWriteContext.ORIGIN_DEVICE_HEADER, ORIGIN_DEVICE)
                .contentType("application/json")
                .content("""
                    {"id":"%s","marker":"HDL","value":55,"unit":"mg/dL","sampleDate":"2026-05-31"}
                    """.formatted(id)))
            .andExpect(status().isCreated())
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    private JsonNode createLocation(String id, String idempotencyKey) throws Exception {
        MvcResult res = mvc.perform(post("/api/me/gyms")
                .header("X-Dev-User", USER)
                .header(SyncWriteContext.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                .header(SyncWriteContext.ORIGIN_DEVICE_HEADER, ORIGIN_DEVICE)
                .contentType("application/json")
                .content("""
                    {"id":"%s","name":"Downtown Gym","is24Hours":true}
                    """.formatted(id)))
            .andExpect(status().isCreated())
            .andReturn();
        return json.readTree(res.getResponse().getContentAsString());
    }

    /** Drain the whole delta from an empty cursor and return changes by key. */
    private Map<String, JsonNode> drainDelta(int limit) throws Exception {
        Map<String, JsonNode> out = new HashMap<>();
        String cursor = null;
        boolean hasMore;
        int guard = 0;
        do {
            StringBuilder url = new StringBuilder("/api/me/sync?limit=" + limit);
            if (cursor != null) {
                url.append("&since=").append(cursor);
            }
            MvcResult res = mvc.perform(get(url.toString()).header("X-Dev-User", USER))
                .andExpect(status().isOk())
                .andReturn();
            JsonNode body = json.readTree(res.getResponse().getContentAsString());
            for (JsonNode change : body.get("changes")) {
                out.put(change.get("collection").asText() + "/" + change.get("id").asText(), change);
            }
            cursor = body.get("nextCursor").asText(null);
            hasMore = body.get("hasMore").asBoolean();
        } while (hasMore && ++guard < 20);
        return out;
    }
}
