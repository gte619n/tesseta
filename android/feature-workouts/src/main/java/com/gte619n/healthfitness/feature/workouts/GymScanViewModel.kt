package com.gte619n.healthfitness.feature.workouts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.workouts.GymScanRepository
import com.gte619n.healthfitness.data.workouts.ImportConfirmItemDto
import com.gte619n.healthfitness.data.workouts.ImportConfirmRequestDto
import com.gte619n.healthfitness.data.workouts.ImportConfirmResponseDto
import com.gte619n.healthfitness.data.workouts.ImportPreviewResponseDto
import com.gte619n.healthfitness.data.workouts.NameOverrideDto
import com.gte619n.healthfitness.feature.workouts.nav.WorkoutsRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.InputStream
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-GYM-003: drives the gym-video scan on Android — pick/record a video →
 * upload → poll → review the detected equipment → confirm. Mirrors the web modal
 * flow and reuses the backend's preview/confirm shapes.
 */
@HiltViewModel
class GymScanViewModel @Inject constructor(
    private val repo: GymScanRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val locationId: String =
        savedStateHandle.get<String>(WorkoutsRoutes.ARG_LOCATION_ID)
            ?: error("locationId arg missing")

    enum class Stage { IDLE, UPLOADING, ANALYZING, REVIEW, CONFIRMING, DONE }

    data class Row(val action: String, val nameOverride: String? = null)

    data class UiState(
        val stage: Stage = Stage.IDLE,
        val preview: ImportPreviewResponseDto? = null,
        val rows: Map<Int, Row> = emptyMap(),
        val result: ImportConfirmResponseDto? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var scanId: String? = null

    fun analyzeVideo(mimeType: String, sizeBytes: Long, openStream: () -> InputStream) {
        if (sizeBytes <= 0 || sizeBytes > MAX_VIDEO_BYTES) {
            _state.update { it.copy(error = "That video is over 200 MB. Record a shorter walkthrough.") }
            return
        }
        _state.update { it.copy(stage = Stage.UPLOADING, error = null) }
        viewModelScope.launch {
            val target = repo.register(locationId, mimeType, sizeBytes).getOrElse { fail(it); return@launch }
            repo.uploadVideo(target, mimeType, sizeBytes, openStream).getOrElse { fail(it); return@launch }
            scanId = target.scanId
            repo.start(locationId, target.scanId).getOrElse { fail(it); return@launch }
            _state.update { it.copy(stage = Stage.ANALYZING) }
            pollUntilReady(target.scanId)
        }
    }

    private suspend fun pollUntilReady(id: String) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
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
                else -> if (System.currentTimeMillis() > deadline) {
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
                ImportConfirmItemDto(
                    index = item.index,
                    action = row.action,
                    matchedEquipmentId = if (row.action == "USE_MATCH") item.match?.equipmentId else null,
                    parsed = if (row.action == "CREATE_NEW") item.parsed else null,
                    overrides = if (row.action == "CREATE_NEW" && !row.nameOverride.isNullOrBlank()
                        && row.nameOverride != item.parsed.name) NameOverrideDto(row.nameOverride.trim()) else null,
                )
            }
            repo.confirm(locationId, id, ImportConfirmRequestDto(items)).fold(
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

    private fun defaultRows(preview: ImportPreviewResponseDto): Map<Int, Row> =
        preview.items.associate { it.index to Row(defaultAction(it.action)) }

    private fun defaultAction(action: String): String =
        when (action) {
            "MATCH_AUTO", "MATCH_SUGGESTED" -> "USE_MATCH"
            else -> "CREATE_NEW"
        }

    private companion object {
        const val MAX_VIDEO_BYTES = 200L * 1024 * 1024
        const val POLL_INTERVAL_MS = 3000L
        const val POLL_TIMEOUT_MS = 4 * 60 * 1000L
    }
}
