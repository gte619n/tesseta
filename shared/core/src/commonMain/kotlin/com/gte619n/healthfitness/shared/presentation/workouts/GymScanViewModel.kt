package com.gte619n.healthfitness.shared.presentation.workouts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.GymScanRepository
import com.gte619n.healthfitness.shared.data.NameOverride
import com.gte619n.healthfitness.shared.data.ScanConfirmItem
import com.gte619n.healthfitness.shared.data.ScanConfirmRequest
import com.gte619n.healthfitness.shared.data.ScanConfirmResult
import com.gte619n.healthfitness.shared.data.ScanPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) — shared port of the Android
 * `feature-workouts/.../GymScanViewModel` (IMPL-GYM-003): pick/record a gym
 * walkthrough video → upload → poll → review the detected equipment → confirm.
 * Mirrors the web modal flow and reuses the backend preview/confirm shapes.
 *
 * The iOS scan surface can EITHER record a video (uploaded here) OR — as a
 * lighter fallback the screen offers — skip the scan and use a location picker /
 * manual add; the video path is what this VM drives.
 */
class GymScanViewModel(
    private val locationId: String,
    private val repo: GymScanRepository,
) : ViewModel() {

    enum class Stage { IDLE, UPLOADING, ANALYZING, REVIEW, CONFIRMING, DONE }

    /** Per-detected-item review choice. */
    data class Row(val action: String, val nameOverride: String? = null)

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val preview: ScanPreview? = null,
        val rows: Map<Int, Row> = emptyMap(),
        val result: ScanConfirmResult? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanId: String? = null

    /** [readBytes] supplies the recorded video lazily so the platform owns the file. */
    fun analyzeVideo(mimeType: String, sizeBytes: Long, readBytes: () -> ByteArray) {
        if (sizeBytes <= 0 || sizeBytes > MAX_VIDEO_BYTES) {
            _state.update { it.copy(error = "That video is over 200 MB. Record a shorter walkthrough.") }
            return
        }
        _state.update { it.copy(stage = Stage.UPLOADING, error = null) }
        viewModelScope.launch {
            val target = repo.register(locationId, mimeType, sizeBytes).getOrElse { fail(it); return@launch }
            repo.uploadVideo(target, mimeType, sizeBytes, readBytes).getOrElse { fail(it); return@launch }
            scanId = target.scanId
            repo.start(locationId, target.scanId).getOrElse { fail(it); return@launch }
            _state.update { it.copy(stage = Stage.ANALYZING) }
            pollUntilReady(target.scanId)
        }
    }

    private suspend fun pollUntilReady(id: String) {
        val deadline = Clock.System.now().toEpochMilliseconds() + POLL_TIMEOUT_MS
        while (true) {
            delay(POLL_INTERVAL_MS)
            val status = repo.status(locationId, id).getOrElse { fail(it); return }
            when (status.status) {
                "READY" -> {
                    val preview = status.preview
                    if (preview == null) {
                        fail(IllegalStateException("Scan finished without results."))
                        return
                    }
                    _state.update {
                        it.copy(stage = Stage.REVIEW, preview = preview, rows = defaultRows(preview), error = null)
                    }
                    return
                }
                "FAILED" -> {
                    fail(IllegalStateException(status.error ?: "We couldn't read equipment from that video."))
                    return
                }
                else -> if (Clock.System.now().toEpochMilliseconds() > deadline) {
                    fail(IllegalStateException("Analysis is taking longer than expected. Try a shorter video."))
                    return
                }
            }
        }
    }

    fun setRowAction(index: Int, action: String) =
        _state.update { s -> s.copy(rows = s.rows + (index to (s.rows[index]?.copy(action = action) ?: Row(action)))) }

    fun setRowName(index: Int, name: String) =
        _state.update { s -> s.copy(rows = s.rows + (index to (s.rows[index]?.copy(nameOverride = name) ?: Row("CREATE_NEW", name)))) }

    fun confirm() {
        val preview = _state.value.preview ?: return
        val id = scanId ?: return
        _state.update { it.copy(stage = Stage.CONFIRMING, error = null) }
        viewModelScope.launch {
            val items = preview.items.map { item ->
                val row = _state.value.rows[item.index] ?: Row(defaultAction(item.action))
                ScanConfirmItem(
                    index = item.index,
                    action = row.action,
                    matchedEquipmentId = if (row.action == "USE_MATCH") item.match?.equipmentId else null,
                    parsed = if (row.action == "CREATE_NEW") item.parsed else null,
                    overrides = if (row.action == "CREATE_NEW" && !row.nameOverride.isNullOrBlank() &&
                        row.nameOverride != item.parsed.name
                    ) {
                        NameOverride(row.nameOverride.trim())
                    } else {
                        null
                    },
                )
            }
            repo.confirm(locationId, id, ScanConfirmRequest(items)).fold(
                onSuccess = { resp -> _state.update { it.copy(stage = Stage.DONE, result = resp) } },
                onFailure = { e -> _state.update { it.copy(stage = Stage.REVIEW, error = e.message ?: "Failed to add equipment") } },
            )
        }
    }

    fun reset() {
        scanId = null
        _state.value = UiState()
    }

    private fun fail(e: Throwable) =
        _state.update { it.copy(stage = Stage.IDLE, error = e.message ?: "Video analysis failed") }

    private fun defaultRows(preview: ScanPreview): Map<Int, Row> =
        preview.items.associate { it.index to Row(defaultAction(it.action)) }

    private fun defaultAction(action: String): String = when (action) {
        "MATCH_AUTO", "MATCH_SUGGESTED" -> "USE_MATCH"
        else -> "CREATE_NEW"
    }

    private companion object {
        const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
        const val POLL_INTERVAL_MS = 3000L
        const val POLL_TIMEOUT_MS = 4 * 60 * 1000L
    }
}
