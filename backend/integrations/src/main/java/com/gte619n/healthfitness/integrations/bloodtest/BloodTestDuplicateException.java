package com.gte619n.healthfitness.integrations.bloodtest;

public class BloodTestDuplicateException extends RuntimeException {
    private final String existingReportId;

    public BloodTestDuplicateException(String message, String existingReportId) {
        super(message);
        this.existingReportId = existingReportId;
    }

    public String existingReportId() {
        return existingReportId;
    }
}
