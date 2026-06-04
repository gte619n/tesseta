package com.gte619n.healthfitness.api.nutrition;

import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.nutrition.LabelProposal;
import com.gte619n.healthfitness.core.nutrition.MealProposal;
import com.gte619n.healthfitness.core.nutrition.NutritionCaptureService;
import com.gte619n.healthfitness.integrations.nutrition.MealPhotoStorageException;
import com.gte619n.healthfitness.integrations.nutrition.NutritionExtractionException;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * AI capture endpoints (IMPL-13 Milestone 3) the phone calls to itemize a meal
 * photo or OCR a nutrition label. Both return <strong>proposals only</strong> —
 * nothing is persisted; the client saves via the M1 endpoints.
 *
 * <p>Extraction failures (Gemini empty/no-tool-call, or the analyzer bean being
 * unavailable) map to 422 Unprocessable Entity; storage failures map to 502.
 */
@RestController
@RequestMapping("/api/nutrition/capture")
public class NutritionCaptureController {

    private static final Logger log = LoggerFactory.getLogger(NutritionCaptureController.class);

    private final CurrentUserProvider currentUser;
    private final NutritionCaptureService capture;

    public NutritionCaptureController(
        CurrentUserProvider currentUser, NutritionCaptureService capture) {
        this.currentUser = currentUser;
        this.capture = capture;
    }

    @PostMapping(value = "/meal", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MealProposalResponse meal(@RequestPart("photo") MultipartFile photo) {
        String userId = currentUser.get().userId();
        byte[] bytes = readBytes(photo);
        try {
            MealProposal proposal = capture.analyzeMeal(userId, bytes, photo.getContentType());
            return MealProposalResponse.from(proposal);
        } catch (NutritionExtractionException | IllegalStateException e) {
            throw extractionFailed(e);
        } catch (MealPhotoStorageException e) {
            throw storageFailed(e);
        }
    }

    @PostMapping(value = "/label", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public LabelProposalResponse label(
        @RequestPart("photo") MultipartFile photo,
        @RequestParam(value = "barcode", required = false) String barcode) {
        String userId = currentUser.get().userId();
        byte[] bytes = readBytes(photo);
        try {
            LabelProposal proposal = capture.analyzeLabel(userId, bytes, photo.getContentType(), barcode);
            return LabelProposalResponse.from(proposal);
        } catch (NutritionExtractionException | IllegalStateException e) {
            throw extractionFailed(e);
        } catch (MealPhotoStorageException e) {
            throw storageFailed(e);
        }
    }

    private static byte[] readBytes(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "photo is required");
        }
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "could not read uploaded photo");
        }
    }

    private static ResponseStatusException extractionFailed(RuntimeException e) {
        log.warn("Nutrition capture extraction failed: {}", e.getMessage());
        return new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "could not extract nutrition from photo");
    }

    private static ResponseStatusException storageFailed(RuntimeException e) {
        log.warn("Nutrition photo storage failed: {}", e.getMessage());
        return new ResponseStatusException(
            HttpStatus.BAD_GATEWAY, "could not store the uploaded photo");
    }
}
