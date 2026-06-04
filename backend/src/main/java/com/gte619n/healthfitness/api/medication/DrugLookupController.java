package com.gte619n.healthfitness.api.medication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gte619n.healthfitness.api.medication.DrugResponse;
import com.gte619n.healthfitness.config.SseStreamer;
import com.gte619n.healthfitness.core.medication.Drug;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * REST controller for drug lookup operations.
 * Uses AI with Google Search grounding to find and classify drugs.
 * Endpoints: /api/drugs/lookup
 */
@RestController
@RequestMapping("/api/drugs")
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class DrugLookupController {

    private static final long SSE_TIMEOUT_MS = 120_000L;
    private static final ObjectMapper JSON = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final DrugCatalogService catalogService;
    private final SseStreamer sseStreamer;

    public DrugLookupController(DrugCatalogService catalogService, SseStreamer sseStreamer) {
        this.catalogService = catalogService;
        this.sseStreamer = sseStreamer;
    }

    /**
     * Look up a drug using AI with Google Search grounding.
     * If the drug exists in the catalog, returns the existing entry.
     * If not found in catalog, uses AI to search for information and
     * creates a new catalog entry with AI-generated metadata.
     *
     * @param body The lookup request containing the search query
     * @return The drug information (existing or newly created)
     */
    @PostMapping("/lookup")
    public ResponseEntity<DrugLookupResponse> lookup(@RequestBody LookupRequest body) {
        if (body.query() == null || body.query().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        Optional<Drug> result = catalogService.lookupOrCreate(body.query());
        if (result.isEmpty()) {
            return ResponseEntity.ok(new DrugLookupResponse(
                false,
                null,
                "No drug found matching: " + body.query()
            ));
        }

        return ResponseEntity.ok(new DrugLookupResponse(
            true,
            DrugResponse.from(result.get()),
            null
        ));
    }

    /**
     * Look up a drug with SSE streaming for progress updates.
     * Streams phases: searching -> found/not_found -> generating_image -> complete
     */
    @PostMapping(value = "/lookup/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter lookupStream(@RequestBody LookupRequest body) {
        if (body.query() == null || body.query().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamer.stream(() -> {
            try {
                sendPhase(emitter, "searching", "Looking up drug information...");

                Optional<Drug> result = catalogService.lookupOrCreateWithCallback(
                    body.query(),
                    (phase, message) -> sendPhase(emitter, phase, message)
                );

                if (result.isEmpty()) {
                    Map<String, Object> notFound = new LinkedHashMap<>();
                    notFound.put("phase", "not_found");
                    notFound.put("message", "No drug found matching: " + body.query());
                    sendData(emitter, notFound);
                    emitter.complete();
                    return;
                }

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("phase", "complete");
                done.put("drug", DrugResponse.from(result.get()));
                sendData(emitter, done);
                emitter.complete();

            } catch (Exception e) {
                sendFailure(emitter, e.getMessage() == null ? "Lookup failed" : e.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    /**
     * Search the drug catalog by name/alias.
     * This is a fast local search - does not use AI.
     */
    @GetMapping("/search")
    public List<DrugResponse> search(@RequestParam String q) {
        return catalogService.search(q).stream()
            .map(DrugResponse::from)
            .toList();
    }

    /**
     * Trigger image regeneration for a drug with SSE progress.
     */
    @PostMapping(value = "/{drugId}/regenerate-image", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter regenerateImage(@PathVariable String drugId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamer.stream(() -> {
            try {
                sendPhase(emitter, "generating", "Generating image...");

                String imageUrl = catalogService.regenerateImageSync(drugId);

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("phase", "complete");
                done.put("imageUrl", imageUrl);
                sendData(emitter, done);
                emitter.complete();

            } catch (Exception e) {
                sendFailure(emitter, e.getMessage() == null ? "Image generation failed" : e.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    // SSE helper methods

    private static void sendPhase(SseEmitter emitter, String phase, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phase", phase);
        body.put("message", message);
        sendData(emitter, body);
    }

    private static void sendFailure(SseEmitter emitter, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phase", "failed");
        body.put("error", error);
        sendData(emitter, body);
    }

    private static void sendData(SseEmitter emitter, Map<String, Object> body) {
        try {
            emitter.send(SseEmitter.event().data(JSON.writeValueAsString(body), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    // Request/Response DTOs

    public record LookupRequest(String query) {}

    public record DrugLookupResponse(
        boolean found,
        DrugResponse drug,
        String message
    ) {}
}
