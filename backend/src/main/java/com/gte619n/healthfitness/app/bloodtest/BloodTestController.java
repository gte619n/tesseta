package com.gte619n.healthfitness.app.bloodtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gte619n.healthfitness.api.bloodtest.BloodTestReportResponse;
import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.config.SseStreamer;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestDuplicateException;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestExtractionException;
import com.gte619n.healthfitness.integrations.bloodtest.BloodTestPdfStorage;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Upload + list + read + delete blood test reports for the current user.
// The upload endpoint streams progress as Server-Sent Events: validation
// + auth happen synchronously (and surface as 4xx if the request is bad),
// then the long-running extraction runs on a virtual thread, emitting
// `phase` events the UI uses to drive a step indicator.
@RestController
@RequestMapping("/api/me/blood/reports")
@ConditionalOnProperty(name = "app.bloodtest.enabled", havingValue = "true", matchIfMissing = true)
public class BloodTestController {

    private static final long MAX_PDF_BYTES = 25L * 1024 * 1024; // 25 MB
    // Gemini Flash on a multi-page lab report tends to land around 10–20s
    // but we give it generous headroom. The client overlay shows the
    // active phase the whole time so a longer wait doesn't feel like a hang.
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private static final ObjectMapper JSON = JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();

    private final CurrentUserProvider currentUser;
    private final BloodTestService service;
    private final BloodTestReportRepository reports;
    private final BloodTestPdfStorage pdfStorage;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;
    private final SseStreamer sseStreamer;

    public BloodTestController(
        CurrentUserProvider currentUser,
        BloodTestService service,
        BloodTestReportRepository reports,
        BloodTestPdfStorage pdfStorage,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier,
        SseStreamer sseStreamer
    ) {
        this.currentUser = currentUser;
        this.service = service;
        this.reports = reports;
        this.pdfStorage = pdfStorage;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
        this.sseStreamer = sseStreamer;
    }

    @PostMapping(
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty file");
        }
        if (file.getSize() > MAX_PDF_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                "PDF exceeds 25 MB limit");
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.equals("application/pdf")) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "Expected application/pdf, got " + contentType);
        }
        final String userId = currentUser.get().userId();
        final String fileName = file.getOriginalFilename();
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Could not read uploaded file", e);
        }

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        sseStreamer.stream(() -> {
            try {
                BloodTestReport report = service.upload(userId, fileName, bytes, phase ->
                    sendPhase(emitter, phase, phaseMessage(phase)));
                Map<String, Object> done = new LinkedHashMap<>();
                done.put("phase", "complete");
                done.put("report", BloodTestReportResponse.from(report));
                sendData(emitter, done);
                emitter.complete();
            } catch (BloodTestDuplicateException e) {
                sendFailure(emitter, e.getMessage());
                emitter.complete();
            } catch (BloodTestExtractionException e) {
                sendFailure(emitter, "Could not read the blood test report: " + e.getMessage());
                emitter.complete();
            } catch (Exception e) {
                sendFailure(emitter, e.getMessage() == null ? "Upload failed" : e.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    @GetMapping
    public List<BloodTestReportResponse> list() {
        String userId = currentUser.get().userId();
        return reports.findByUser(userId).stream()
            .map(BloodTestReportResponse::from)
            .toList();
    }

    @GetMapping("/{reportId}")
    public BloodTestReportResponse get(@PathVariable String reportId) {
        String userId = currentUser.get().userId();
        BloodTestReport report = reports.findById(userId, reportId).orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        return BloodTestReportResponse.from(report);
    }

    @GetMapping(value = "/{reportId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadPdf(@PathVariable String reportId) {
        String userId = currentUser.get().userId();
        if (reports.findById(userId, reportId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        byte[] pdf = pdfStorage.download(userId, reportId);
        return ResponseEntity.ok()
            .header("Content-Disposition", "inline; filename=\"bloodtest-" + reportId + ".pdf\"")
            .body(pdf);
    }

    @PatchMapping("/{reportId}/field")
    public BloodTestReportResponse updateField(
        @PathVariable String reportId,
        @RequestBody UpdateFieldRequest body
    ) {
        String userId = currentUser.get().userId();
        if (reports.findById(userId, reportId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        try {
            BloodTestReport updated = service.updateField(
                userId, reportId, body.path(), body.value());
            // The PDF/SSE AI upload stays online-only (D17), but this manual
            // field correction is an in-scope JSON write: fan out so other
            // devices pull the edit (origin suppressed, IMPL-AND-20 #8/D18).
            syncNotifier.changed(userId, syncWrite.originDeviceId(), "bloodTestReports");
            return BloodTestReportResponse.from(updated);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    public record UpdateFieldRequest(String path, Object value) {}

    @DeleteMapping("/{reportId}")
    public ResponseEntity<Void> delete(@PathVariable String reportId) {
        String userId = currentUser.get().userId();
        if (reports.findById(userId, reportId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        service.delete(userId, reportId);
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "bloodTestReports");
        return ResponseEntity.noContent().build();
    }

    private static String phaseMessage(String phase) {
        return switch (phase) {
            case "uploading" -> "Saving your PDF";
            case "extracting" -> "Reading the report with Gemini";
            case "saving" -> "Storing the results";
            default -> phase;
        };
    }

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
            // Client disconnected. Nothing to do — the virtual thread
            // will hit the next emit and we'll bail out.
            emitter.completeWithError(e);
        }
    }
}
