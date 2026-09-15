package com.gte619n.healthfitness.api.gym;

import com.gte619n.healthfitness.core.gym.GymVideoScanService.RegisterResult;
import com.gte619n.healthfitness.core.gym.VideoStore;
import java.util.Map;

/**
 * IMPL-GYM-003: where + how the client uploads the video. It PUTs the raw bytes to
 * {@code uploadUrl} with the given {@code headers}, then calls {@code POST
 * /scan/{scanId}/start}.
 */
public record ScanRegisterResponse(
    String scanId,
    String uploadUrl,
    String method,
    Map<String, String> headers,
    String status
) {
    public static ScanRegisterResponse from(RegisterResult r) {
        VideoStore.UploadTarget t = r.target();
        return new ScanRegisterResponse(r.scanId(), t.uploadUrl(), t.method(), t.headers(), "REGISTERED");
    }
}
