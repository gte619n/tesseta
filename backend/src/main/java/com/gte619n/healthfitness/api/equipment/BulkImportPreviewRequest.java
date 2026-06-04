package com.gte619n.healthfitness.api.equipment;

import jakarta.validation.constraints.NotBlank;

public record BulkImportPreviewRequest(
    @NotBlank(message = "rawText is required")
    String rawText
) {}
