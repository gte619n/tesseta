package com.gte619n.healthfitness.data.workouts

import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-GYM-003: drives a gym-video equipment scan — register (get a signed upload
 * URL), upload the video directly to it, start analysis, poll status, and confirm
 * the reviewed items. The ViewModel sequences these; each returns a [Result].
 */
@Singleton
class GymScanRepository @Inject constructor(
    private val api: GymScanApi,
    private val uploader: SignedUploadClient,
) {
    suspend fun register(
        locationId: String,
        mimeType: String,
        sizeBytes: Long,
    ): Result<ScanRegisterResponseDto> = runCatching {
        api.register(locationId, ScanRegisterRequestDto(mimeType, sizeBytes))
    }

    suspend fun uploadVideo(
        target: ScanRegisterResponseDto,
        mimeType: String,
        contentLength: Long,
        openStream: () -> InputStream,
    ): Result<Unit> = uploader.put(
        url = target.uploadUrl,
        method = target.method,
        mimeType = mimeType,
        contentLength = contentLength,
        headers = target.headers,
        openStream = openStream,
    )

    suspend fun start(locationId: String, scanId: String): Result<ScanStatusResponseDto> =
        runCatching { api.start(locationId, scanId) }

    suspend fun status(locationId: String, scanId: String): Result<ScanStatusResponseDto> =
        runCatching { api.status(locationId, scanId) }

    suspend fun confirm(
        locationId: String,
        scanId: String,
        req: ImportConfirmRequestDto,
    ): Result<ImportConfirmResponseDto> = runCatching { api.confirm(locationId, scanId, req) }
}
