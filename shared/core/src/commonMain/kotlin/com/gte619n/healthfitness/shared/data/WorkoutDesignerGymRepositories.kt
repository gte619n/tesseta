package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import com.gte619n.healthfitness.shared.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.shared.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.shared.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.shared.domain.workouts.progression.PatternReview
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave D(iii) (Workouts — designer / progression console /
 * gyms) — repository + type contracts the designer, progression-console, and gym
 * ViewModels observe. KMP ports of the Android `data.workouts.LocationRepository`
 * / `EquipmentRepository` / `progression.ProgressionRepository` /
 * `program.chat.WorkoutProgramChatRepository` / `GymScanRepository` (D3:
 * Moshi→kotlinx.serialization).
 *
 * COORDINATION: the Wave D-i sibling owns `WorkoutRepositories.kt` (hub /
 * programs / history / library repo interfaces + the shared `WorkoutsRoute`
 * lives in Swift). This file owns ONLY the gym / designer / progression repo
 * methods and their DTOs, so the three verticals compose without editing a
 * shared file. The SSE chat stream reuses [SseClient] / [ChatStreamEvent] from
 * `GoalsRepositories.kt` (NOT redeclared here) — the workout designer is a
 * second consumer of the same one-interface SSE contract.
 *
 * Location / Equipment / the AI program proposal are co-located here (data
 * layer) rather than in `domain/workouts/` — exactly as Goals kept
 * `GoalProposal` beside the chat repo — because the reference `domain/workouts/`
 * files this vertical does not own carry no gym / proposal types yet. The
 * CONCRETE implementations (Room-KMP mirror reads + Ktor writes through the
 * outbox, and a Ktor SSE reader) are the Phase 1C body; the ViewModels + SwiftUI
 * views depend only on these interfaces and are authored + unit-tested against
 * fakes first.
 */

// --- Gyms (locations) ------------------------------------------------------

/** Opening hours for one day. 24-hour "HH:mm" on 15-min steps; absent = closed. */
@Serializable
data class HoursSlot(val open: String, val close: String)

/**
 * A gym amenity. IDs mirror web's `AMENITIES` exactly and are stored lowercase
 * on the wire (parity with the Android `Amenity` enum's `id`).
 */
@Serializable
data class Amenity(val id: String, val label: String) {
    companion object {
        val CATALOG: List<Amenity> = listOf(
            Amenity("24hr", "24-Hour Access"),
            Amenity("lockers", "Lockers"),
            Amenity("showers", "Showers"),
            Amenity("parking", "Parking"),
            Amenity("wifi", "WiFi"),
            Amenity("towels", "Towels"),
            Amenity("sauna", "Sauna"),
            Amenity("pool", "Pool"),
            Amenity("childcare", "Childcare"),
            Amenity("training", "Personal Training"),
        )

        fun fromId(id: String): Amenity? = CATALOG.firstOrNull { it.id == id }
    }
}

/** A user gym location. [hours] keyed by day; closed days are simply absent. */
@Serializable
data class Location(
    val locationId: String,
    val name: String,
    val address: String? = null,
    val coverPhotoUrl: String? = null,
    val is24Hours: Boolean = false,
    val hours: Map<DayOfWeek, HoursSlot>? = null,
    val amenities: List<Amenity> = emptyList(),
    val equipmentIds: List<String> = emptyList(),
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
)

/** One catalog equipment row attached to a gym. */
@Serializable
data class Equipment(
    val equipmentId: String,
    val name: String,
    val category: String? = null,
)

/** Create payload for a new gym (parity with `CreateLocationRequest`). */
@Serializable
data class CreateLocationRequest(
    val name: String,
    val address: String? = null,
    val is24Hours: Boolean = false,
    val hours: Map<DayOfWeek, HoursSlot>? = null,
    val amenities: List<String> = emptyList(),
)

/** Update payload for an existing gym (parity with `UpdateLocationRequest`). */
@Serializable
data class UpdateLocationRequest(
    val name: String,
    val address: String? = null,
    val is24Hours: Boolean = false,
    val hours: Map<DayOfWeek, HoursSlot>? = null,
    val amenities: List<String> = emptyList(),
)

/**
 * A pending cover-photo upload: the raw bytes + content type. Platform picks the
 * file (iOS PhotosPicker / camera); the repo streams it to the upload endpoint.
 */
class PendingUpload(val bytes: ByteArray, val contentType: String)

/**
 * Gym CRUD + equipment attachment. Offline-first (ADR-0018): [cachedList] /
 * [cached] read the Room mirror synchronously; [list] / [get] revalidate.
 */
interface LocationRepository {
    suspend fun cachedList(): List<Location>
    suspend fun list(): Result<List<Location>>
    suspend fun cached(locationId: String): Location?
    suspend fun get(locationId: String): Result<Location>
    suspend fun create(request: CreateLocationRequest): Result<Location>
    suspend fun update(locationId: String, request: UpdateLocationRequest): Result<Unit>
    suspend fun delete(locationId: String): Result<Unit>
    suspend fun setDefault(locationId: String): Result<Unit>
    suspend fun removeEquipment(locationId: String, equipmentId: String): Result<Unit>
    suspend fun uploadCoverPhoto(locationId: String, file: PendingUpload): Result<String>
    suspend fun deleteCoverPhoto(locationId: String): Result<Unit>
}

/** Catalog equipment lookups (cache-first). */
interface EquipmentRepository {
    suspend fun cached(equipmentId: String): Equipment?
    suspend fun get(equipmentId: String): Result<Equipment>
}

// --- Gym video scan (equipment import) -------------------------------------

/** Where to PUT the recorded video + the scan id to poll. */
@Serializable
data class ScanTarget(val scanId: String, val uploadUrl: String)

/** A parsed equipment name/category the model read out of the video. */
@Serializable
data class ParsedEquipment(val name: String, val category: String? = null)

/** A catalog match the backend suggests for a detected item. */
@Serializable
data class EquipmentMatch(val equipmentId: String, val name: String, val score: Double = 0.0)

/**
 * One detected item in the scan preview. [action] is the backend's default
 * suggestion (`MATCH_AUTO` | `MATCH_SUGGESTED` | `NEW`), which the review UI can
 * override per row.
 */
@Serializable
data class ScanPreviewItem(
    val index: Int,
    val action: String,
    val parsed: ParsedEquipment,
    val match: EquipmentMatch? = null,
)

@Serializable
data class ScanPreview(val items: List<ScanPreviewItem> = emptyList())

/** Poll response: status + the preview once READY. */
@Serializable
data class ScanStatus(
    /** "PENDING" | "ANALYZING" | "READY" | "FAILED". */
    val status: String,
    val preview: ScanPreview? = null,
    val error: String? = null,
)

/** A per-row rename override on confirm. */
@Serializable
data class NameOverride(val name: String)

/** One confirmed row: the chosen action + optional matched id / parsed / override. */
@Serializable
data class ScanConfirmItem(
    val index: Int,
    /** "USE_MATCH" | "CREATE_NEW" | "SKIP". */
    val action: String,
    val matchedEquipmentId: String? = null,
    val parsed: ParsedEquipment? = null,
    val overrides: NameOverride? = null,
)

@Serializable
data class ScanConfirmRequest(val items: List<ScanConfirmItem>)

/** Result of confirming a scan: how many pieces of equipment were added. */
@Serializable
data class ScanConfirmResult(val addedCount: Int)

/**
 * IMPL-GYM-003 gym-video scan: register → upload → start → poll → confirm.
 * [openStream] supplies the bytes lazily so the platform owns the file handle.
 */
interface GymScanRepository {
    suspend fun register(locationId: String, mimeType: String, sizeBytes: Long): Result<ScanTarget>
    suspend fun uploadVideo(
        target: ScanTarget,
        mimeType: String,
        sizeBytes: Long,
        openStream: () -> ByteArray,
    ): Result<Unit>
    suspend fun start(locationId: String, scanId: String): Result<Unit>
    suspend fun status(locationId: String, scanId: String): Result<ScanStatus>
    suspend fun confirm(locationId: String, scanId: String, request: ScanConfirmRequest): Result<ScanConfirmResult>
}

// --- Progression console ---------------------------------------------------
//
// The console is READ-ONLY over the backend progression engine's outputs — the
// per-hand→total reporting, realistic-jumps, and demonstrated-override LOGIC
// lives in the backend engine and is single-sourced there; the client never
// re-derives a jump. What this vertical single-sources on the client is the
// *formatting/derivation* of those engine outputs (load-trend sign/units, the
// pinned-vs-measured-mode divergence, the per-hand→total ×2 display), which the
// ProgressionConsoleViewModel owns so both platforms render one truth.

/** Read the progression console + pin the training mode. */
interface ProgressionRepository {
    suspend fun weekReview(): Result<List<PatternReview>>
    suspend fun blockParameters(): Result<BlockParameters>
    suspend fun strength(): Result<List<ExerciseStrength>>
    suspend fun energyBalance(): Result<EnergyBalance?>
    /** PUT the pinned mode; returns the refreshed block parameters. */
    suspend fun updateBlockParameters(mode: String): Result<BlockParameters>
}

/** A minimal goal read the designer + console need (title / domain / id). */
interface WorkoutGoalsRepository {
    /** Active goals (title + domain), best-effort; empty on failure. */
    suspend fun activeGoals(): List<GoalRef>
}

/** A goal reference for the designer goal-picker + the console's mode↔goal link. */
data class GoalRef(val goalId: String, val title: String, val domain: String)

// --- AI program designer (SSE chat) ----------------------------------------
//
// The SSE stream itself reuses [SseClient] from GoalsRepositories.kt. Only the
// program-proposal shape + the commit / thread JSON half live here.

/** One prescription line in a proposed program (kept flat for the editor card). */
@Serializable
data class ProposalPrescription(
    val exerciseName: String,
    val sets: Int? = null,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val durationSeconds: Int? = null,
    val restSeconds: Int? = null,
    val notes: String? = null,
)

@Serializable
data class ProposalBlock(
    val title: String,
    val prescriptions: List<ProposalPrescription> = emptyList(),
)

@Serializable
data class ProposalDay(
    val label: String,
    val dayOfWeek: DayOfWeek? = null,
    val blocks: List<ProposalBlock> = emptyList(),
)

@Serializable
data class ProgramProposalPhase(
    val title: String,
    val focus: String? = null,
    val weeks: Int? = null,
    val days: List<ProposalDay> = emptyList(),
)

/** The AI-drafted program the designer streams as the `proposal` event's JSON. */
@Serializable
data class ProgramProposal(
    val title: String,
    val description: String? = null,
    val phases: List<ProgramProposalPhase> = emptyList(),
)

/** The full `proposal` event payload: the program + soft advisories + issues. */
@Serializable
data class ProgramProposalPayload(
    val program: ProgramProposal,
    val issues: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** The training schedule sent on the first turn (day → gym). */
@Serializable
data class ScheduleDto(val trainingDays: List<DayOfWeek>, val dayLocations: Map<DayOfWeek, String>) {
    companion object {
        fun of(trainingDays: List<DayOfWeek>, dayLocations: Map<DayOfWeek, String>): ScheduleDto =
            ScheduleDto(trainingDays, dayLocations)
    }
}

/** Outcome of committing a proposed program. */
sealed interface ProgramCommitResult {
    data class Created(val programId: String) : ProgramCommitResult

    /** 422: backend re-validated and returned the actionable issue list. */
    data class Invalid(val issues: List<String>) : ProgramCommitResult
}

/** Mirrors the backend program-chat thread row. */
data class ProgramChatThreadResponse(
    val threadId: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
)

/** One persisted turn when reopening a thread. */
data class ProgramChatMessage(
    val messageId: String,
    /** "USER" | "ASSISTANT". */
    val role: String,
    val content: String? = null,
    val proposalJson: String? = null,
)

/**
 * JSON half of the designer surface (commit + thread list/messages). The SSE
 * stream is [SseClient]. The stream's first turn carries the schedule / goal /
 * edit-program-id; the reader packs those into the POST body.
 */
interface WorkoutProgramChatRepository {
    suspend fun commit(
        threadId: String,
        program: ProgramProposal,
        schedule: ScheduleDto,
        goalId: String?,
    ): ProgramCommitResult
    suspend fun listThreads(): List<ProgramChatThreadResponse>
    suspend fun listMessages(threadId: String): List<ProgramChatMessage>
    suspend fun deleteThread(threadId: String)
}
