package com.gte619n.healthfitness.api.gym;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/** IMPL-GYM-003: request a signed upload URL for a gym walkthrough video. */
public record ScanRegisterRequest(
    @NotBlank(message = "mimeType is required") String mimeType,
    @Positive(message = "sizeBytes must be positive") long sizeBytes
) {}
