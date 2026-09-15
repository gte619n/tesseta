package com.gte619n.healthfitness.core.gym;

import java.io.InputStream;
import java.util.Map;

/**
 * IMPL-GYM-003: storage port for walkthrough videos. The client uploads the video
 * DIRECTLY to object storage via {@link #createUploadTarget} (so a large video
 * never transits the backend / Cloud Run's request-size limit); the analysis job
 * later streams it back out with {@link #openStream}/{@link #size} to hand to the
 * Gemini Files API, then {@link #delete}s it.
 */
public interface VideoStore {

    /** A signed, time-boxed upload target the client PUTs the raw video bytes to. */
    record UploadTarget(String objectRef, String uploadUrl, String method, Map<String, String> headers) {}

    UploadTarget createUploadTarget(String userId, String locationId, String scanId, String mimeType);

    boolean exists(String objectRef);

    long size(String objectRef);

    /** Caller closes the stream (try-with-resources). */
    InputStream openStream(String objectRef);

    void delete(String objectRef);
}
