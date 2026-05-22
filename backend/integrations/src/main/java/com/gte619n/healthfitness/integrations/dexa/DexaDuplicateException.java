package com.gte619n.healthfitness.integrations.dexa;

// Thrown when a user uploads a PDF whose content hash already exists in
// their scan collection. Carries the existing scanId so the UI can
// link to / highlight the existing record.
public class DexaDuplicateException extends RuntimeException {
    private final String existingScanId;

    public DexaDuplicateException(String message, String existingScanId) {
        super(message);
        this.existingScanId = existingScanId;
    }

    public String existingScanId() {
        return existingScanId;
    }
}
